package app.everyday.server

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Reminder sync for one member (see CLAUDE.md, "Sync (Phase 4)"). A sync applies the pushed rows
 * with last write wins on the phone's updatedAt, then returns everything the member can see.
 * Each pushed row is checked on its own: a bad row is rejected without failing the rest.
 */
class SyncStore(private val db: DataSource) {

    sealed interface Result {
        data class Ok(val response: SyncResponse) : Result
        data object UnknownMember : Result
    }

    fun sync(householdId: UUID, req: SyncRequest): Result = db.transaction { conn ->
        val me = parseId(req.memberId)
        if (me == null || !conn.isActiveMember(householdId, me)) return@transaction Result.UnknownMember
        val rejected = mutableListOf<String>()
        req.reminders.forEach { r -> conn.attempt(rejected, r.id) { pushReminder(householdId, me, r) } }
        req.states.forEach { s -> conn.attempt(rejected, "state:${s.reminderId}") { pushState(me, s) } }
        req.settings?.let { s -> conn.attempt(rejected, "settings") { pushSettings(me, s) } }
        req.recipientStatuses.forEach { s -> conn.attempt(rejected, "recipient:${s.reminderId}") { pushStatus(me, s) } }
        conn.deleteOrphanedSharedReminders()
        Result.Ok(conn.snapshot(me, rejected))
    }

    /** A pushed row that breaks a rule. Only that row is skipped. */
    private class Rejected(message: String) : RuntimeException(message)

    private fun reject(message: String): Nothing = throw Rejected(message)

    /** Runs [block] in a savepoint, so a rejected or failing row is rolled back on its own. */
    private fun Connection.attempt(rejected: MutableList<String>, id: String, block: Connection.() -> Unit) {
        val savepoint = setSavepoint()
        try {
            block()
            releaseSavepoint(savepoint)
        } catch (e: Exception) {
            if (e !is Rejected && e !is SQLException) throw e
            rollback(savepoint)
            rejected += id
        }
    }

    private fun Connection.pushReminder(householdId: UUID, me: UUID, r: ReminderSyncDto) {
        val id = parseId(r.id) ?: reject("bad id")
        val existing = prepareStatement("SELECT owner_member_id, updated_at FROM reminder WHERE id = ? FOR UPDATE").run {
            setObject(1, id)
            executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) to rs.millis(2) else null }
        }
        // Only the owner writes a reminder.
        val owner = existing?.first ?: parseId(r.ownerMemberId)
        if (owner != me) reject("not the owner")
        if (existing != null && existing.second >= r.updatedAt) return // An older write loses.

        val choreId = r.choreId?.let { parseId(it) ?: reject("bad chore id") }
        if (r.mode !in MODES) reject("bad mode")
        if (r.weekdays !in 0..127) reject("bad weekdays")
        val recipients = r.recipients.map { parseId(it.memberId) ?: reject("bad member id") }.toSet()
        if (!r.deleted) {
            val message = r.message.trim()
            if (message.isEmpty() || message.length > 200) reject("bad message")
            if (r.times.any { it !in 0..1439 }) reject("bad time")
            validWindows(r.windows)
            if (choreId != null) {
                if (recipients.isNotEmpty()) reject("chore reminders can't be shared")
                if (!isAssigned(householdId, choreId, me)) reject("not assigned to the chore")
            }
            if (me in recipients) reject("can't invite the owner")
            if (recipients.isNotEmpty() && !allActiveMembers(householdId, recipients)) reject("unknown member")
        }

        prepareStatement(
            """
            INSERT INTO reminder (id, owner_member_id, chore_id, message, mode, weekdays, active, owner_receives, created_at, updated_at, deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET chore_id = EXCLUDED.chore_id, message = EXCLUDED.message, mode = EXCLUDED.mode,
                weekdays = EXCLUDED.weekdays, active = EXCLUDED.active, owner_receives = EXCLUDED.owner_receives,
                updated_at = EXCLUDED.updated_at, deleted = EXCLUDED.deleted
            """.trimIndent(),
        ).run {
            setObject(1, id); setObject(2, me); setObject(3, choreId); setString(4, r.message.trim()); setString(5, r.mode)
            setInt(6, r.weekdays); setBoolean(7, r.active); setBoolean(8, r.ownerReceives)
            setObject(9, r.createdAt.toTime()); setObject(10, r.updatedAt.toTime()); setBoolean(11, r.deleted)
            executeUpdate()
        }
        if (r.deleted) return

        // Times and windows are replaced as a whole with their reminder.
        prepareStatement("DELETE FROM reminder_time WHERE reminder_id = ?").run { setObject(1, id); executeUpdate() }
        prepareStatement("DELETE FROM reminder_window WHERE reminder_id = ?").run { setObject(1, id); executeUpdate() }
        prepareStatement("INSERT INTO reminder_time (id, reminder_id, minute_of_day) VALUES (?, ?, ?)").run {
            r.times.distinct().forEach { setObject(1, UUID.randomUUID()); setObject(2, id); setInt(3, it); addBatch() }
            executeBatch()
        }
        prepareStatement("INSERT INTO reminder_window (id, reminder_id, start_minute, end_minute) VALUES (?, ?, ?, ?)").run {
            r.windows.forEach { setObject(1, UUID.randomUUID()); setObject(2, id); setInt(3, it.startMinute); setInt(4, it.endMinute); addBatch() }
            executeBatch()
        }

        // Invites: new ones start pending, removed ones are tombstoned, existing ones keep their answer.
        prepareStatement(
            "UPDATE reminder_recipient SET deleted = TRUE, updated_at = ? WHERE reminder_id = ? AND NOT deleted AND NOT (member_id = ANY (?))",
        ).run {
            setObject(1, r.updatedAt.toTime()); setObject(2, id); setArray(3, createArrayOf("uuid", recipients.toTypedArray()))
            executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO reminder_recipient (reminder_id, member_id, status, updated_at) VALUES (?, ?, 'pending', ?)
            ON CONFLICT (reminder_id, member_id) DO UPDATE SET deleted = FALSE, status = 'pending', updated_at = EXCLUDED.updated_at
            WHERE reminder_recipient.deleted
            """.trimIndent(),
        ).run {
            recipients.forEach { setObject(1, id); setObject(2, it); setObject(3, r.updatedAt.toTime()); addBatch() }
            executeBatch()
        }
    }

    private fun validWindows(windows: List<WindowDto>) {
        for (w in windows) {
            if (w.startMinute !in 0..1439 || w.endMinute !in 1..1440 || w.endMinute <= w.startMinute) reject("bad window")
        }
        val sorted = windows.sortedBy { it.startMinute }
        if (sorted.zipWithNext().any { (a, b) -> b.startMinute < a.endMinute }) reject("overlapping windows")
    }

    private fun Connection.pushState(me: UUID, s: StateSyncDto) {
        val reminderId = parseId(s.reminderId) ?: reject("bad id")
        if (!canSee(me, reminderId)) reject("not your reminder")
        val doneOn = s.doneOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: reject("bad date") }
        prepareStatement(
            """
            INSERT INTO reminder_state (reminder_id, member_id, done_on, snooze_until, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (reminder_id, member_id) DO UPDATE SET done_on = EXCLUDED.done_on,
                snooze_until = EXCLUDED.snooze_until, updated_at = EXCLUDED.updated_at
            WHERE reminder_state.updated_at < EXCLUDED.updated_at
            """.trimIndent(),
        ).run {
            setObject(1, reminderId); setObject(2, me); setObject(3, doneOn); setObject(4, s.snoozeUntil?.toTime())
            setObject(5, s.updatedAt.toTime()); executeUpdate()
        }
    }

    private fun Connection.pushSettings(me: UUID, s: SettingsSyncDto) {
        if (s.snoozeMinutes !in 1..240 || s.timedSound !in SOUNDS || s.persistentSound !in SOUNDS) reject("bad settings")
        prepareStatement(
            """
            INSERT INTO member_settings (member_id, snooze_minutes, timed_sound, persistent_sound, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (member_id) DO UPDATE SET snooze_minutes = EXCLUDED.snooze_minutes, timed_sound = EXCLUDED.timed_sound,
                persistent_sound = EXCLUDED.persistent_sound, updated_at = EXCLUDED.updated_at
            WHERE member_settings.updated_at < EXCLUDED.updated_at
            """.trimIndent(),
        ).run {
            setObject(1, me); setInt(2, s.snoozeMinutes); setString(3, s.timedSound); setString(4, s.persistentSound)
            setObject(5, s.updatedAt.toTime()); executeUpdate()
        }
    }

    /** An invitee changes only their own answer. */
    private fun Connection.pushStatus(me: UUID, s: RecipientStatusDto) {
        val reminderId = parseId(s.reminderId) ?: reject("bad id")
        if (s.status !in STATUSES) reject("bad status")
        val exists = prepareStatement("SELECT 1 FROM reminder_recipient WHERE reminder_id = ? AND member_id = ? AND NOT deleted").run {
            setObject(1, reminderId); setObject(2, me); executeQuery().use { it.next() }
        }
        if (!exists) reject("not invited")
        prepareStatement(
            "UPDATE reminder_recipient SET status = ?, updated_at = ? WHERE reminder_id = ? AND member_id = ? AND updated_at < ?",
        ).run {
            setString(1, s.status); setObject(2, s.updatedAt.toTime()); setObject(3, reminderId); setObject(4, me)
            setObject(5, s.updatedAt.toTime()); executeUpdate()
        }
    }

    private fun Connection.canSee(me: UUID, reminderId: UUID): Boolean = prepareStatement(
        """
        SELECT 1 FROM reminder r WHERE r.id = ? AND (r.owner_member_id = ? OR EXISTS (
            SELECT 1 FROM reminder_recipient rr WHERE rr.reminder_id = r.id AND rr.member_id = ? AND NOT rr.deleted))
        """.trimIndent(),
    ).run { setObject(1, reminderId); setObject(2, me); setObject(3, me); executeQuery().use { it.next() } }

    private fun Connection.isActiveMember(householdId: UUID, id: UUID) = allActiveMembers(householdId, setOf(id))

    private fun Connection.allActiveMembers(householdId: UUID, ids: Set<UUID>): Boolean =
        prepareStatement("SELECT count(*) FROM member WHERE household_id = ? AND NOT deleted AND id = ANY (?)").run {
            setObject(1, householdId); setArray(2, createArrayOf("uuid", ids.toTypedArray()))
            executeQuery().use { it.next(); it.getInt(1) == ids.size }
        }

    private fun Connection.isAssigned(householdId: UUID, choreId: UUID, memberId: UUID): Boolean = prepareStatement(
        """
        SELECT 1 FROM chore_assignment a JOIN chore c ON c.id = a.chore_id
        WHERE a.chore_id = ? AND a.member_id = ? AND NOT a.deleted AND NOT c.deleted AND c.household_id = ?
        """.trimIndent(),
    ).run { setObject(1, choreId); setObject(2, memberId); setObject(3, householdId); executeQuery().use { it.next() } }

    private fun Connection.snapshot(me: UUID, rejected: List<String>): SyncResponse {
        val reminderIds = mutableListOf<UUID>()
        val rows = prepareStatement(
            """
            SELECT r.id, r.owner_member_id, r.chore_id, r.message, r.mode, r.weekdays, r.active, r.owner_receives,
                   r.created_at, r.updated_at, r.deleted
            FROM reminder r
            WHERE r.owner_member_id = ? OR EXISTS (
                SELECT 1 FROM reminder_recipient rr WHERE rr.reminder_id = r.id AND rr.member_id = ? AND NOT rr.deleted)
            ORDER BY r.created_at, r.id
            """.trimIndent(),
        ).run {
            setObject(1, me); setObject(2, me)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        reminderIds += rs.getObject("id", UUID::class.java)
                        add(
                            ReminderSyncDto(
                                id = rs.getString("id"),
                                ownerMemberId = rs.getString("owner_member_id"),
                                choreId = rs.getString("chore_id"),
                                message = rs.getString("message"),
                                mode = rs.getString("mode"),
                                weekdays = rs.getInt("weekdays"),
                                active = rs.getBoolean("active"),
                                ownerReceives = rs.getBoolean("owner_receives"),
                                createdAt = rs.millis("created_at"),
                                updatedAt = rs.millis("updated_at"),
                                deleted = rs.getBoolean("deleted"),
                            ),
                        )
                    }
                }
            }
        }
        val ids = createArrayOf("uuid", reminderIds.toTypedArray())
        val times = prepareStatement("SELECT reminder_id, minute_of_day FROM reminder_time WHERE reminder_id = ANY (?) ORDER BY minute_of_day").run {
            setArray(1, ids)
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) } }
        }.groupBy({ it.first }, { it.second })
        val windows = prepareStatement(
            "SELECT reminder_id, start_minute, end_minute FROM reminder_window WHERE reminder_id = ANY (?) ORDER BY start_minute",
        ).run {
            setArray(1, ids)
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to WindowDto(rs.getInt(2), rs.getInt(3))) } }
        }.groupBy({ it.first }, { it.second })
        val recipients = prepareStatement(
            "SELECT reminder_id, member_id, status, updated_at FROM reminder_recipient WHERE reminder_id = ANY (?) AND NOT deleted",
        ).run {
            setArray(1, ids)
            executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to RecipientDto(rs.getString(2), rs.getString(3), rs.millis(4))) }
            }
        }.groupBy({ it.first }, { it.second })

        val states = prepareStatement(
            "SELECT reminder_id, done_on, snooze_until, updated_at FROM reminder_state WHERE member_id = ?",
        ).run {
            setObject(1, me)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StateSyncDto(
                                reminderId = rs.getString(1),
                                doneOn = rs.getObject(2, LocalDate::class.java)?.toString(),
                                snoozeUntil = rs.getObject(3, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli(),
                                updatedAt = rs.millis(4),
                            ),
                        )
                    }
                }
            }
        }
        val settings = prepareStatement(
            "SELECT snooze_minutes, timed_sound, persistent_sound, updated_at FROM member_settings WHERE member_id = ?",
        ).run {
            setObject(1, me)
            executeQuery().use { rs -> if (rs.next()) SettingsSyncDto(rs.getInt(1), rs.getString(2), rs.getString(3), rs.millis(4)) else null }
        }
        return SyncResponse(
            reminders = rows.map { r ->
                r.copy(times = times[r.id].orEmpty(), windows = windows[r.id].orEmpty(), recipients = recipients[r.id].orEmpty())
            },
            states = states,
            settings = settings,
            rejected = rejected,
        )
    }

    private companion object {
        val MODES = setOf("TIMED", "PERSISTENT")
        val SOUNDS = setOf("SOUND", "VIBRATE", "SOUND_VIBRATE", "SILENT")
        val STATUSES = setOf("pending", "accepted", "declined")
    }
}

/**
 * A removed member's shared reminders live on for invitees who still have them (pending or
 * accepted). Once nobody does, they are deleted.
 */
fun Connection.deleteOrphanedSharedReminders() {
    prepareStatement(
        """
        UPDATE reminder r SET deleted = TRUE, updated_at = now()
        FROM member m
        WHERE m.id = r.owner_member_id AND m.deleted AND NOT r.deleted AND NOT EXISTS (
            SELECT 1 FROM reminder_recipient rr
            WHERE rr.reminder_id = r.id AND NOT rr.deleted AND rr.status IN ('pending', 'accepted'))
        """.trimIndent(),
    ).executeUpdate()
}

private fun parseId(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

private fun Long.toTime(): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneOffset.UTC)

private fun java.sql.ResultSet.millis(column: Int): Long = getObject(column, OffsetDateTime::class.java).toInstant().toEpochMilli()

private fun java.sql.ResultSet.millis(column: String): Long = getObject(column, OffsetDateTime::class.java).toInstant().toEpochMilli()
