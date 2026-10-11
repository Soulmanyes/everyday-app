package app.everyday.server

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * One-tap invites into a household (Phase 9.5): an admin makes a one-time invite and shares it as a link.
 * Joining with it works like joining with the join code; the admin still approves. At most [maxOpen]
 * unused, unexpired invites per household.
 */
class MemberInviteStore(private val db: DataSource, private val maxOpen: Int = 20) {

    /** Null if the household already has [maxOpen] open invites. */
    fun create(householdId: UUID, codeHash: String, now: Instant, expiresAt: Instant): InviteDto? = db.transaction { conn ->
        // Locking the household row serialises invites, so two at once can't both pass the limit.
        conn.prepareStatement("SELECT 1 FROM household WHERE id = ? FOR UPDATE").run { setObject(1, householdId); executeQuery().close() }
        val open = conn.prepareStatement(
            "SELECT count(*) FROM member_invite WHERE household_id = ? AND used_at IS NULL AND NOT cancelled AND expires_at > ?",
        ).run {
            setObject(1, householdId); setObject(2, now.toOffset())
            executeQuery().use { it.next(); it.getInt(1) }
        }
        if (open >= maxOpen) return@transaction null
        conn.prepareStatement(
            "INSERT INTO member_invite (household_id, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?) RETURNING id",
        ).run {
            setObject(1, householdId); setString(2, codeHash); setObject(3, now.toOffset()); setObject(4, expiresAt.toOffset())
            executeQuery().use { it.next(); InviteDto(it.getString(1), now.toEpochMilli(), expiresAt.toEpochMilli()) }
        }
    }

    /** The household's open invites (unused, not cancelled, not expired), newest first. */
    fun listOpen(householdId: UUID, now: Instant): List<InviteDto> = db.transaction { conn ->
        conn.prepareStatement(
            """
            SELECT id, created_at, expires_at FROM member_invite
            WHERE household_id = ? AND used_at IS NULL AND NOT cancelled AND expires_at > ? ORDER BY created_at DESC
            """.trimIndent(),
        ).run {
            setObject(1, householdId); setObject(2, now.toOffset())
            executeQuery().use { rs ->
                buildList { while (rs.next()) add(InviteDto(id = rs.getString(1), createdAt = rs.millis(2), expiresAt = rs.millis(3))) }
            }
        }
    }

    /** Cancels an unused invite. Returns false if there's no such unused invite in the household. */
    fun cancel(householdId: UUID, inviteId: UUID): Boolean = db.transaction { conn ->
        conn.prepareStatement(
            "UPDATE member_invite SET cancelled = TRUE WHERE id = ? AND household_id = ? AND used_at IS NULL AND NOT cancelled",
        ).run { setObject(1, inviteId); setObject(2, householdId); executeUpdate() > 0 }
    }

    /**
     * The household and id of a usable invite (unused, not cancelled, not expired), or null. Doesn't lock:
     * [HouseholdStore.joinWithInvite] uses it up and checks again.
     */
    fun find(codeHash: String, now: Instant): Pair<UUID, UUID>? = db.transaction { conn ->
        conn.prepareStatement(
            "SELECT household_id, id FROM member_invite WHERE code_hash = ? AND used_at IS NULL AND NOT cancelled AND expires_at > ?",
        ).run {
            setString(1, codeHash); setObject(2, now.toOffset())
            executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) else null }
        }
    }

    private fun java.sql.ResultSet.millis(column: Int): Long = getObject(column, OffsetDateTime::class.java).toInstant().toEpochMilli()
}
