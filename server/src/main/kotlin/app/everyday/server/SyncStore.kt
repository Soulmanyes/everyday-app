package app.everyday.server

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/**
 * Event and reminder sync for one member (see CLAUDE.md, "Sync"). A sync applies the pushed rows with
 * last write wins on the phone's updatedAt, then returns everything the member can see. Each pushed
 * row is checked on its own: a bad row is rejected without failing the rest.
 */
class SyncStore(private val db: DataSource) {

    sealed interface Result {
        /** [changed]: something was written, so other phones should sync (instant updates). */
        data class Ok(val response: SyncResponse, val changed: Boolean) : Result
        data object UnknownMember : Result
    }

    fun sync(householdId: UUID, req: SyncRequest): Result = db.transaction { conn ->
        val me = parseId(req.memberId)
        if (me == null || !conn.isActiveMember(householdId, me)) return@transaction Result.UnknownMember
        val rejected = mutableListOf<String>()
        var changed = false
        // Events first, so a new event and the reminders on it can arrive in the same sync.
        req.events.forEach { e -> changed = conn.attempt(rejected, "event:${e.id}") { pushEvent(householdId, me, e) } || changed }
        req.leave.forEach { l -> changed = conn.attempt(rejected, "leave:${l.eventId}") { pushLeave(me, l) } || changed }
        req.reminders.forEach { r -> changed = conn.attempt(rejected, r.id) { pushReminder(householdId, me, r) } || changed }
        req.states.forEach { s -> changed = conn.attempt(rejected, "state:${s.reminderId}") { pushState(me, s) } || changed }
        req.settings?.let { s -> changed = conn.attempt(rejected, "settings") { pushSettings(me, s) } || changed }
        conn.deleteOrphanedEvents()
        Result.Ok(conn.snapshot(me, rejected), changed)
    }

    /** A pushed row that breaks a rule. Only that row is skipped. */
    private class Rejected(message: String) : RuntimeException(message)

    private fun reject(message: String): Nothing = throw Rejected(message)

    /**
     * Runs [block] in a savepoint, so a rejected or failing row is rolled back on its own.
     * Returns whether the row changed anything (an older write that lost changes nothing).
     */
    private fun Connection.attempt(rejected: MutableList<String>, id: String, block: Connection.() -> Boolean): Boolean {
        val savepoint = setSavepoint()
        return try {
            block().also { releaseSavepoint(savepoint) }
        } catch (e: Exception) {
            if (e !is Rejected && e !is SQLException) throw e
            rollback(savepoint)
            rejected += id
            false
        }
    }

    private fun Connection.pushEvent(householdId: UUID, me: UUID, e: EventSyncDto): Boolean {
        val id = parseId(e.id) ?: reject("bad id")
        val existing = prepareStatement("SELECT owner_member_id, updated_at, end_days, end_minute FROM event WHERE id = ? FOR UPDATE").run {
            setObject(1, id)
            executeQuery().use { rs ->
                if (rs.next()) StoredEvent(rs.getObject(1, UUID::class.java), rs.millis(2), rs.getInt(3), rs.getObject(4) as Int?) else null
            }
        }
        // Only the owner writes an event.
        val owner = existing?.owner ?: parseId(e.ownerMemberId)
        if (owner != me) reject("not the owner")
        if (existing != null && existing.updatedAt >= e.updatedAt) return false // An older write loses.

        val onDate = e.onDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: reject("bad date") }
        if (e.weekdays !in 0..127 || (onDate == null) == (e.weekdays == 0)) reject("pick days or a date")
        if (e.startMinute != null && e.startMinute !in 0..1439) reject("bad start")
        // Phones before 2.2 don't send endDays: a multi-day event keeps its stored end (they only saw its first day).
        val (endDays, endMinute) = when {
            e.endDays != null -> e.endDays to if (e.endDays > 0) e.endDayMinute else e.endMinute
            existing != null && existing.endDays > 0 -> existing.endDays to existing.endMinute
            else -> 0 to e.endMinute
        }
        validEnd(endDays, e.startMinute, endMinute, e.weekdays, onDate != null)
        val added = e.members.associate { (parseId(it.memberId) ?: reject("bad member id")) to it.updatedAt }
        // Phones before 2.4 send neither: they keep the stored scheduling members and Everyone.
        val scheduling = e.schedulingMembers?.associate { (parseId(it.memberId) ?: reject("bad member id")) to it.updatedAt }
        if (e.everyone != null && e.everyone !in Everyone.ALL) reject("bad everyone")
        val everyone = e.everyone?.takeIf { it != Everyone.NONE }
        val members = added.keys + scheduling.orEmpty().keys
        val title = e.title.trim()
        val notes = e.notes?.trim()?.takeIf { it.isNotEmpty() }
        if (!e.deleted) {
            if (title.isEmpty() || title.length > 200) reject("bad title")
            if ((notes?.length ?: 0) > 500) reject("notes too long")
            if (me in members) reject("can't share with the owner")
            if (members.size < added.size + scheduling.orEmpty().size) reject("a member is in both lists")
            if (members.isNotEmpty() && !allActiveMembers(householdId, members)) reject("unknown member")
        }
        // Members left out see a shared event as Busy only if the owner chose it (Phase 9.8). Phones before 2.4
        // always send Busy (their editor hides the choice on shared events), so those show nothing.
        val showBusy = if (e.everyone == null && added.isNotEmpty()) false else e.showBusy

        prepareStatement(
            """
            INSERT INTO event (id, owner_member_id, title, notes, weekdays, on_date, start_minute, end_minute, created_at, updated_at, deleted,
                show_busy, end_days, everyone)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, TRUE), ?, ?)
            ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, notes = EXCLUDED.notes, weekdays = EXCLUDED.weekdays,
                on_date = EXCLUDED.on_date, start_minute = EXCLUDED.start_minute, end_minute = EXCLUDED.end_minute,
                updated_at = EXCLUDED.updated_at, deleted = EXCLUDED.deleted, show_busy = COALESCE(?, event.show_busy),
                end_days = EXCLUDED.end_days, everyone = CASE WHEN ? THEN event.everyone ELSE EXCLUDED.everyone END
            """.trimIndent(),
        ).run {
            setObject(1, id); setObject(2, me); setString(3, title); setString(4, notes); setInt(5, e.weekdays); setObject(6, onDate)
            setObject(7, e.startMinute); setObject(8, endMinute)
            setObject(9, e.createdAt.toTime()); setObject(10, e.updatedAt.toTime()); setBoolean(11, e.deleted)
            // Phones before 1.4.0 don't send showBusy: keep what's stored.
            setObject(12, showBusy); setInt(13, endDays); setString(14, everyone); setObject(15, showBusy)
            setBoolean(16, e.everyone == null)
            executeUpdate()
        }
        if (e.deleted) {
            // Everyone's reminders go with the event.
            prepareStatement("UPDATE reminder SET deleted = TRUE, updated_at = ? WHERE event_id = ? AND NOT deleted").run {
                setObject(1, e.updatedAt.toTime()); setObject(2, id); executeUpdate()
            }
            return true
        }

        // Members: new ones are added, removed ones are tombstoned (Everyone may still apply to them). Someone who
        // left is only added back if the owner added them after they left; a live row can change role. Phones
        // before 2.4 only send the members taking part, so they leave scheduling rows alone.
        prepareStatement(
            """
            UPDATE event_member SET deleted = TRUE, left_event = FALSE, updated_at = ?
            WHERE event_id = ? AND NOT deleted AND NOT (member_id = ANY (?)) AND (? OR role = 'MEMBER')
            """.trimIndent(),
        ).run {
            setObject(1, e.updatedAt.toTime()); setObject(2, id); setArray(3, createArrayOf("uuid", members.toTypedArray()))
            setBoolean(4, scheduling != null); executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO event_member (event_id, member_id, updated_at, role) VALUES (?, ?, ?, ?)
            ON CONFLICT (event_id, member_id) DO UPDATE SET deleted = FALSE, left_event = FALSE, role = EXCLUDED.role,
                updated_at = GREATEST(event_member.updated_at, EXCLUDED.updated_at)
            WHERE (event_member.deleted AND event_member.updated_at < EXCLUDED.updated_at)
                OR (NOT event_member.deleted AND event_member.role <> EXCLUDED.role)
            """.trimIndent(),
        ).run {
            fun add(rows: Map<UUID, Long>, role: String) = rows.forEach { (member, at) ->
                setObject(1, id); setObject(2, member); setObject(3, at.toTime()); setString(4, role); addBatch()
            }
            add(added, "MEMBER"); add(scheduling.orEmpty(), "SCHEDULING")
            executeBatch()
        }
        // Anyone no longer taking part loses their reminders on it.
        prepareStatement(
            """
            UPDATE reminder SET deleted = TRUE, updated_at = ? WHERE event_id = ? AND NOT deleted AND owner_member_id <> ?
                AND event_role(event_id, owner_member_id) IS DISTINCT FROM 'MEMBER'
            """.trimIndent(),
        ).run { setObject(1, e.updatedAt.toTime()); setObject(2, id); setObject(3, me); executeUpdate() }
        return true
    }

    private data class StoredEvent(val owner: UUID, val updatedAt: Long, val endDays: Int, val endMinute: Int?)

    /**
     * An event's end: on its start day after the start, or (multi-day, Phase 9.6) [endDays] later at any time,
     * needing an end time exactly when it has a start. One-off events last up to 31 days; a weekly one starts
     * on one weekday and ends within 6 days, before its next week.
     */
    private fun validEnd(endDays: Int, start: Int?, end: Int?, weekdays: Int, oneOff: Boolean) {
        if (endDays !in 0..(if (oneOff) 31 else 6)) reject("too long")
        if (endDays == 0) {
            if (end != null && (start == null || end !in 1..1440 || end <= start)) reject("bad end")
            return
        }
        if (!oneOff && Integer.bitCount(weekdays) != 1) reject("a multi-day weekly event starts on one weekday")
        if ((start == null) != (end == null) || (end != null && end !in 1..1440)) reject("bad end")
    }

    /**
     * A member leaves (or declines) an event shared with them, or hides one shared for scheduling, which deletes
     * their reminders on it. It stays gone for them under Everyone too, until the owner adds them again.
     */
    private fun Connection.pushLeave(me: UUID, l: LeaveSyncDto): Boolean {
        val eventId = parseId(l.eventId) ?: reject("bad id")
        val row = prepareStatement("SELECT deleted, updated_at FROM event_member WHERE event_id = ? AND member_id = ? FOR UPDATE").run {
            setObject(1, eventId); setObject(2, me)
            executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) to rs.millis(2) else null }
        }
        // event_role also checks the event is in my household.
        val role = prepareStatement("SELECT event_role(?, ?)").run {
            setObject(1, eventId); setObject(2, me); executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
        if (role == null) {
            if (row == null) reject("not shared with you")
            return false // Already gone.
        }
        // Added again after this phone left: nothing to do.
        if (row != null && !row.first && row.second >= l.updatedAt) return false
        prepareStatement(
            """
            INSERT INTO event_member (event_id, member_id, updated_at, deleted, left_event) VALUES (?, ?, ?, TRUE, TRUE)
            ON CONFLICT (event_id, member_id) DO UPDATE SET deleted = TRUE, left_event = TRUE, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).run { setObject(1, eventId); setObject(2, me); setObject(3, l.updatedAt.toTime()); executeUpdate() }
        prepareStatement("UPDATE reminder SET deleted = TRUE, updated_at = ? WHERE event_id = ? AND owner_member_id = ? AND NOT deleted").run {
            setObject(1, l.updatedAt.toTime()); setObject(2, eventId); setObject(3, me); executeUpdate()
        }
        return true
    }

    private fun Connection.pushReminder(householdId: UUID, me: UUID, r: ReminderSyncDto): Boolean {
        val id = parseId(r.id) ?: reject("bad id")
        val existing = prepareStatement("SELECT owner_member_id, updated_at FROM reminder WHERE id = ? FOR UPDATE").run {
            setObject(1, id)
            executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) to rs.millis(2) else null }
        }
        // Only the owner writes a reminder.
        val owner = existing?.first ?: parseId(r.ownerMemberId)
        if (owner != me) reject("not the owner")
        if (existing != null && existing.second >= r.updatedAt) return false // An older write loses.

        val eventId = r.eventId?.let { parseId(it) ?: reject("bad event id") }
        val choreId = r.choreId?.let { parseId(it) ?: reject("bad chore id") }
        if ((eventId == null) == (choreId == null)) reject("needs an event or a chore")
        if (r.mode !in MODES) reject("bad mode")
        if (r.weekdays !in 0..127) reject("bad weekdays")
        if (!r.deleted) {
            if (eventId != null) {
                val start = eventStart(me, eventId) ?: reject("event not visible")
                if (r.windows.isNotEmpty()) reject("event reminders have no windows")
                if (r.mode == "TIMED") {
                    if (r.leadMinutes != null) reject("lead is for persistent reminders")
                    if (r.eachDay == true) reject("each day is for persistent reminders")
                    if (r.times.any { it.minute !in 0..1439 }) reject("bad time")
                    if (r.times.any { it.relative } && start.isEmpty) reject("all-day events have no start")
                } else {
                    if (r.times.isNotEmpty()) reject("persistent reminders have no times")
                    if (r.eachDay == true && r.leadMinutes != null) reject("each day is all day")
                    if (r.leadMinutes != null && (r.leadMinutes !in 0..1439 || start.isEmpty)) reject("bad lead")
                }
            } else {
                val message = r.message.trim()
                if (message.isEmpty() || message.length > 200) reject("bad message")
                if (r.times.any { it.relative || it.minute !in 0..1439 }) reject("bad time")
                if (r.leadMinutes != null) reject("chore reminders have no lead")
                if (r.eachDay == true) reject("chore reminders have no each day")
                validWindows(r.windows)
                if (!isAssigned(householdId, choreId!!, me)) reject("not assigned to the chore")
            }
        }

        prepareStatement(
            """
            INSERT INTO reminder (id, owner_member_id, event_id, chore_id, message, mode, weekdays, active, lead_minutes, created_at, updated_at,
                deleted, each_day)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, FALSE))
            ON CONFLICT (id) DO UPDATE SET event_id = EXCLUDED.event_id, chore_id = EXCLUDED.chore_id, message = EXCLUDED.message,
                mode = EXCLUDED.mode, weekdays = EXCLUDED.weekdays, active = EXCLUDED.active, lead_minutes = EXCLUDED.lead_minutes,
                updated_at = EXCLUDED.updated_at, deleted = EXCLUDED.deleted, each_day = COALESCE(?, reminder.each_day)
            """.trimIndent(),
        ).run {
            setObject(1, id); setObject(2, me); setObject(3, eventId); setObject(4, choreId); setString(5, r.message.trim())
            setString(6, r.mode); setInt(7, r.weekdays); setBoolean(8, r.active); setObject(9, r.leadMinutes)
            setObject(10, r.createdAt.toTime()); setObject(11, r.updatedAt.toTime()); setBoolean(12, r.deleted)
            // Phones before 2.2 don't send eachDay: keep what's stored, unless they set a lead (that's never each day).
            val eachDay = r.eachDay ?: if (r.leadMinutes != null) false else null
            setObject(13, eachDay); setObject(14, eachDay)
            executeUpdate()
        }
        if (r.deleted) return true

        // Times and windows are replaced as a whole with their reminder.
        prepareStatement("DELETE FROM reminder_time WHERE reminder_id = ?").run { setObject(1, id); executeUpdate() }
        prepareStatement("DELETE FROM reminder_window WHERE reminder_id = ?").run { setObject(1, id); executeUpdate() }
        prepareStatement("INSERT INTO reminder_time (id, reminder_id, minute_of_day, relative) VALUES (?, ?, ?, ?)").run {
            r.times.distinct().forEach { setObject(1, UUID.randomUUID()); setObject(2, id); setInt(3, it.minute); setBoolean(4, it.relative); addBatch() }
            executeBatch()
        }
        prepareStatement("INSERT INTO reminder_window (id, reminder_id, start_minute, end_minute) VALUES (?, ?, ?, ?)").run {
            r.windows.forEach { setObject(1, UUID.randomUUID()); setObject(2, id); setInt(3, it.startMinute); setInt(4, it.endMinute); addBatch() }
            executeBatch()
        }
        return true
    }

    /**
     * The start minute of an event [me] can put reminders on (theirs, or one they take part in), empty for an
     * all-day event, or null if they can't.
     */
    private fun Connection.eventStart(me: UUID, eventId: UUID): Optional<Int>? = prepareStatement(
        """
        SELECT e.start_minute FROM event e WHERE e.id = ? AND NOT e.deleted
            AND (e.owner_member_id = ? OR event_role(e.id, ?) = 'MEMBER')
        """.trimIndent(),
    ).run {
        setObject(1, eventId); setObject(2, me); setObject(3, me)
        executeQuery().use { rs -> if (rs.next()) Optional.ofNullable(rs.getObject(1) as Int?) else null }
    }

    private fun validWindows(windows: List<WindowDto>) {
        for (w in windows) {
            if (w.startMinute !in 0..1439 || w.endMinute !in 1..1440 || w.endMinute <= w.startMinute) reject("bad window")
        }
        val sorted = windows.sortedBy { it.startMinute }
        if (sorted.zipWithNext().any { (a, b) -> b.startMinute < a.endMinute }) reject("overlapping windows")
    }

    private fun Connection.pushState(me: UUID, s: StateSyncDto): Boolean {
        val reminderId = parseId(s.reminderId) ?: reject("bad id")
        val mine = prepareStatement("SELECT 1 FROM reminder WHERE id = ? AND owner_member_id = ?").run {
            setObject(1, reminderId); setObject(2, me); executeQuery().use { it.next() }
        }
        if (!mine) reject("not your reminder")
        val doneOn = s.doneOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: reject("bad date") }
        return prepareStatement(
            """
            INSERT INTO reminder_state (reminder_id, member_id, done_on, snooze_until, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (reminder_id, member_id) DO UPDATE SET done_on = EXCLUDED.done_on,
                snooze_until = EXCLUDED.snooze_until, updated_at = EXCLUDED.updated_at
            WHERE reminder_state.updated_at < EXCLUDED.updated_at
            """.trimIndent(),
        ).run {
            setObject(1, reminderId); setObject(2, me); setObject(3, doneOn); setObject(4, s.snoozeUntil?.toTime())
            setObject(5, s.updatedAt.toTime()); executeUpdate() > 0
        }
    }

    private fun Connection.pushSettings(me: UUID, s: SettingsSyncDto): Boolean {
        if (s.snoozeMinutes !in 1..240 || s.timedSound !in SOUNDS || s.persistentSound !in SOUNDS) reject("bad settings")
        return prepareStatement(
            """
            INSERT INTO member_settings (member_id, snooze_minutes, timed_sound, persistent_sound, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (member_id) DO UPDATE SET snooze_minutes = EXCLUDED.snooze_minutes, timed_sound = EXCLUDED.timed_sound,
                persistent_sound = EXCLUDED.persistent_sound, updated_at = EXCLUDED.updated_at
            WHERE member_settings.updated_at < EXCLUDED.updated_at
            """.trimIndent(),
        ).run {
            setObject(1, me); setInt(2, s.snoozeMinutes); setString(3, s.timedSound); setString(4, s.persistentSound)
            setObject(5, s.updatedAt.toTime()); executeUpdate() > 0
        }
    }

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
        // Events I own (with tombstones), and events I take part in (Everyone ones too, so only my household's).
        val eventIds = mutableListOf<UUID>()
        val events = prepareStatement(
            """
            SELECT e.id, e.owner_member_id, e.title, e.notes, e.weekdays, e.on_date, e.start_minute, e.end_minute,
                   e.created_at, e.updated_at, e.deleted, e.show_busy, e.end_days, e.everyone
            FROM event e JOIN member o ON o.id = e.owner_member_id
            WHERE e.owner_member_id = ? OR (o.household_id = (SELECT household_id FROM member WHERE id = ?)
                AND event_role(e.id, ?) = 'MEMBER')
            ORDER BY e.created_at, e.id
            """.trimIndent(),
        ).run {
            setObject(1, me); setObject(2, me); setObject(3, me)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        eventIds += rs.getObject("id", UUID::class.java)
                        val end = rs.end()
                        add(
                            EventSyncDto(
                                id = rs.getString("id"),
                                ownerMemberId = rs.getString("owner_member_id"),
                                title = rs.getString("title"),
                                notes = rs.getString("notes"),
                                weekdays = rs.getInt("weekdays"),
                                onDate = rs.getObject("on_date", LocalDate::class.java)?.toString(),
                                startMinute = rs.getObject("start_minute") as Int?,
                                endMinute = end.forOldPhones,
                                endDays = end.days,
                                endDayMinute = end.dayMinute,
                                createdAt = rs.millis("created_at"),
                                updatedAt = rs.millis("updated_at"),
                                deleted = rs.getBoolean("deleted"),
                                showBusy = rs.getBoolean("show_busy"),
                                everyone = rs.getString("everyone") ?: Everyone.NONE,
                            ),
                        )
                    }
                }
            }
        }
        // (event id, scheduling) to its members in that role.
        val members = prepareStatement(
            """
            SELECT event_id, member_id, updated_at, role = 'SCHEDULING' FROM event_member
            WHERE event_id = ANY (?) AND NOT deleted ORDER BY member_id
            """.trimIndent(),
        ).run {
            setArray(1, createArrayOf("uuid", eventIds.toTypedArray()))
            executeQuery().use { rs ->
                buildList { while (rs.next()) add((rs.getString(1) to rs.getBoolean(4)) to EventMemberDto(rs.getString(2), rs.millis(3))) }
            }
        }.groupBy({ it.first }, { it.second })

        // Only my own reminders: everyone sees just their own reminder times on an event.
        val reminderIds = mutableListOf<UUID>()
        val rows = prepareStatement(
            """
            SELECT id, owner_member_id, event_id, chore_id, message, mode, weekdays, active, lead_minutes, created_at, updated_at, deleted,
                   each_day
            FROM reminder WHERE owner_member_id = ? AND (event_id IS NOT NULL OR chore_id IS NOT NULL)
            ORDER BY created_at, id
            """.trimIndent(),
        ).run {
            setObject(1, me)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        reminderIds += rs.getObject("id", UUID::class.java)
                        add(
                            ReminderSyncDto(
                                id = rs.getString("id"),
                                ownerMemberId = rs.getString("owner_member_id"),
                                eventId = rs.getString("event_id"),
                                choreId = rs.getString("chore_id"),
                                message = rs.getString("message"),
                                mode = rs.getString("mode"),
                                weekdays = rs.getInt("weekdays"),
                                active = rs.getBoolean("active"),
                                leadMinutes = rs.getObject("lead_minutes") as Int?,
                                createdAt = rs.millis("created_at"),
                                updatedAt = rs.millis("updated_at"),
                                deleted = rs.getBoolean("deleted"),
                                eachDay = rs.getBoolean("each_day"),
                            ),
                        )
                    }
                }
            }
        }
        val ids = createArrayOf("uuid", reminderIds.toTypedArray())
        val times = prepareStatement(
            "SELECT reminder_id, minute_of_day, relative FROM reminder_time WHERE reminder_id = ANY (?) ORDER BY relative, minute_of_day",
        ).run {
            setArray(1, ids)
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to TimeDto(rs.getInt(2), rs.getBoolean(3))) } }
        }.groupBy({ it.first }, { it.second })
        val windows = prepareStatement(
            "SELECT reminder_id, start_minute, end_minute FROM reminder_window WHERE reminder_id = ANY (?) ORDER BY start_minute",
        ).run {
            setArray(1, ids)
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to WindowDto(rs.getInt(2), rs.getInt(3))) } }
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
        // Other members' events: shared with me for scheduling (with the title), or that I'm left out of and
        // that show as Busy (days and times only). Notes never leave the server, and an event I left or hid
        // shows nothing.
        val busy = prepareStatement(
            """
            SELECT * FROM (
                SELECT e.id, e.owner_member_id, e.weekdays, e.on_date, e.start_minute, e.end_minute, e.end_days, e.title,
                       e.show_busy, e.created_at, event_role(e.id, ?) AS role,
                       EXISTS (SELECT 1 FROM event_member em WHERE em.event_id = e.id AND em.member_id = ? AND em.left_event) AS left_it
                FROM event e JOIN member m ON m.id = e.owner_member_id
                WHERE e.owner_member_id <> ? AND NOT e.deleted AND NOT m.deleted
                    AND m.household_id = (SELECT household_id FROM member WHERE id = ?)
            ) e
            WHERE e.role = 'SCHEDULING' OR (e.role IS NULL AND e.show_busy AND NOT e.left_it)
            ORDER BY e.created_at, e.id
            """.trimIndent(),
        ).run {
            setObject(1, me); setObject(2, me); setObject(3, me); setObject(4, me)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val end = rs.end()
                        add(
                            BusyDto(
                                id = rs.getString("id"),
                                ownerMemberId = rs.getString("owner_member_id"),
                                weekdays = rs.getInt("weekdays"),
                                onDate = rs.getObject("on_date", LocalDate::class.java)?.toString(),
                                startMinute = rs.getObject("start_minute") as Int?,
                                endMinute = end.forOldPhones,
                                endDays = end.days,
                                endDayMinute = end.dayMinute,
                                title = rs.getString("title").takeIf { rs.getString("role") == "SCHEDULING" },
                            ),
                        )
                    }
                }
            }
        }
        return SyncResponse(
            busy = busy,
            events = events.map {
                // Under Everyone I'm on another member's event without a row: name me anyway, as phones before
                // 2.4 only show (and let me leave) events whose members include me.
                val taking = members[it.id to false].orEmpty()
                val named = it.ownerMemberId == me.toString() || taking.any { m -> m.memberId == me.toString() }
                it.copy(members = if (named) taking else taking + EventMemberDto(me.toString()), schedulingMembers = members[it.id to true].orEmpty())
            },
            reminders = rows.map { r -> r.copy(times = times[r.id].orEmpty(), windows = windows[r.id].orEmpty()) },
            states = states,
            settings = settings,
            rejected = rejected,
        )
    }

    private companion object {
        val MODES = setOf("TIMED", "PERSISTENT")
        val SOUNDS = setOf("SOUND", "VIBRATE", "SOUND_VIBRATE", "SILENT")
    }
}

/**
 * A removed member's shared events live on, read-only, for the members still taking part (under Everyone,
 * everyone who hasn't left). Once nobody takes part, they are deleted, and reminders on deleted events go with them.
 */
fun Connection.deleteOrphanedEvents() {
    prepareStatement(
        """
        UPDATE event e SET deleted = TRUE, updated_at = now()
        FROM member m
        WHERE m.id = e.owner_member_id AND m.deleted AND NOT e.deleted AND NOT EXISTS (
            SELECT 1 FROM member mm WHERE mm.household_id = m.household_id AND NOT mm.deleted
                AND event_role(e.id, mm.id) = 'MEMBER')
        """.trimIndent(),
    ).executeUpdate()
    prepareStatement(
        "UPDATE reminder r SET deleted = TRUE, updated_at = now() FROM event e WHERE e.id = r.event_id AND e.deleted AND NOT r.deleted",
    ).executeUpdate()
}

private fun parseId(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

private fun Long.toTime(): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneOffset.UTC)

private fun java.sql.ResultSet.millis(column: Int): Long = getObject(column, OffsetDateTime::class.java).toInstant().toEpochMilli()

private fun java.sql.ResultSet.millis(column: String): Long = getObject(column, OffsetDateTime::class.java).toInstant().toEpochMilli()


/**
 * An event's end as sent to phones. Phones before 2.2 only know [forOldPhones]: a multi-day event lasts for
 * them from its start until midnight of its first day.
 */
private data class SentEnd(val forOldPhones: Int?, val days: Int, val dayMinute: Int?)

private fun java.sql.ResultSet.end(): SentEnd {
    val days = getInt("end_days")
    val minute = getObject("end_minute") as Int?
    if (days == 0) return SentEnd(minute, 0, null)
    val start = getObject("start_minute") as Int?
    return SentEnd(if (start != null) 1440 else null, days, minute)
}
