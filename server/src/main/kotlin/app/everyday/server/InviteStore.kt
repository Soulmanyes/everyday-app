package app.everyday.server

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Invites an admin makes for a friend to create their own household (Phase 9). Each household has
 * [limit] slots: a used invite keeps its slot, while a cancelled or expired unused one gives it back.
 */
class InviteStore(private val db: DataSource, private val limit: Int = 3) {

    /** Null if the household has no free slot. */
    fun create(householdId: UUID, codeHash: String, now: Instant, expiresAt: Instant): InviteDto? = db.transaction { conn ->
        // Locking the household row serialises invites, so two at once can't both take the last slot.
        conn.prepareStatement("SELECT 1 FROM household WHERE id = ? FOR UPDATE").run { setObject(1, householdId); executeQuery().close() }
        if (conn.taken(householdId, now) >= limit) return@transaction null
        conn.prepareStatement(
            "INSERT INTO household_invite (household_id, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?) RETURNING id",
        ).run {
            setObject(1, householdId); setString(2, codeHash); setObject(3, now.toOffset()); setObject(4, expiresAt.toOffset())
            executeQuery().use { it.next(); InviteDto(it.getString(1), now.toEpochMilli(), expiresAt.toEpochMilli()) }
        }
    }

    /** The household's invites, newest first, without cancelled ones. */
    fun list(householdId: UUID): List<InviteDto> = db.transaction { conn ->
        conn.prepareStatement(
            "SELECT id, created_at, expires_at, used_at FROM household_invite WHERE household_id = ? AND NOT cancelled ORDER BY created_at DESC",
        ).run {
            setObject(1, householdId)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            InviteDto(
                                id = rs.getString(1),
                                createdAt = rs.millis(2),
                                expiresAt = rs.millis(3),
                                usedAt = rs.getObject(4, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Cancels an unused invite. Returns false if there's no such unused invite in the household. */
    fun cancel(householdId: UUID, inviteId: UUID): Boolean = db.transaction { conn ->
        conn.prepareStatement(
            "UPDATE household_invite SET cancelled = TRUE WHERE id = ? AND household_id = ? AND used_at IS NULL AND NOT cancelled",
        ).run { setObject(1, inviteId); setObject(2, householdId); executeUpdate() > 0 }
    }

    /** How many invites the household can still make. */
    fun remaining(householdId: UUID, now: Instant): Int = db.transaction { conn -> (limit - conn.taken(householdId, now)).coerceAtLeast(0) }

    /** Slots in use: used invites, and unused ones that are neither cancelled nor expired. */
    private fun java.sql.Connection.taken(householdId: UUID, now: Instant): Int = prepareStatement(
        """
        SELECT count(*) FROM household_invite
        WHERE household_id = ? AND (used_at IS NOT NULL OR (NOT cancelled AND expires_at > ?))
        """.trimIndent(),
    ).run {
        setObject(1, householdId); setObject(2, now.toOffset())
        executeQuery().use { it.next(); it.getInt(1) }
    }

    private fun java.sql.ResultSet.millis(column: Int): Long = getObject(column, OffsetDateTime::class.java).toInstant().toEpochMilli()
}
