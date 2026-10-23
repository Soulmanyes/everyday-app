package app.everyday.server

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Calls (Phase 12): a member the admin allows (or the admin) rings other members' phones with a short
 * message, and each person rung answers "on my way" or "can't now". Calls are only needed while they ring
 * and get answered; the daily [Cleanup] deletes them after [keepFor].
 */
class CallStore(
    private val db: DataSource,
    private val minGap: Duration = Duration.ofSeconds(30),
    private val answerFor: Duration = Duration.ofMinutes(10),
) {
    sealed interface Start {
        data class Ok(val call: CallDto, val targets: List<UUID>) : Start
        /** The caller rang less than [minGap] ago: they can ring again in [seconds]. */
        data class TooSoon(val seconds: Long) : Start
        data object NotAllowed : Start
        data object UnknownMember : Start
    }

    /** Rings [targets] (other active members of the household) from [callerId]. */
    fun start(householdId: UUID, callerId: UUID, isAdmin: Boolean, targets: Set<UUID>, message: String, now: Instant): Start = db.transaction { conn ->
        // Locking the caller's row serialises their calls, so two at once can't both pass the gap.
        val canCall = conn.prepareStatement("SELECT can_call FROM member WHERE id = ? AND household_id = ? AND NOT deleted FOR UPDATE").run {
            setObject(1, callerId); setObject(2, householdId)
            executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else return@transaction Start.NotAllowed }
        }
        if (!canCall && !isAdmin) return@transaction Start.NotAllowed
        val last = conn.prepareStatement("SELECT max(created_at) FROM call WHERE caller_id = ?").run {
            setObject(1, callerId)
            executeQuery().use { rs -> rs.next(); rs.getObject(1, OffsetDateTime::class.java)?.toInstant() }
        }
        if (last != null) {
            val wait = Duration.between(now, last.plus(minGap))
            if (!wait.isNegative && !wait.isZero) return@transaction Start.TooSoon((wait.toMillis() + 999) / 1000)
        }
        if (callerId in targets) return@transaction Start.UnknownMember
        val known = conn.prepareStatement("SELECT count(*) FROM member WHERE household_id = ? AND NOT deleted AND id = ANY(?)").run {
            setObject(1, householdId); setArray(2, conn.createArrayOf("uuid", targets.toTypedArray()))
            executeQuery().use { it.next(); it.getInt(1) }
        }
        if (known != targets.size) return@transaction Start.UnknownMember
        val id = conn.prepareStatement("INSERT INTO call (household_id, caller_id, message, created_at) VALUES (?, ?, ?, ?) RETURNING id").run {
            setObject(1, householdId); setObject(2, callerId); setString(3, message); setObject(4, now.toOffset())
            executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        }
        conn.prepareStatement("INSERT INTO call_target (call_id, member_id) VALUES (?, ?)").run {
            targets.forEach { setObject(1, id); setObject(2, it); addBatch() }
            executeBatch()
        }
        Start.Ok(conn.call(householdId, id)!!, targets.toList())
    }

    /** The call, if [memberId] made it or was rung by it; null otherwise, so other members learn nothing. */
    fun get(householdId: UUID, callId: UUID, memberId: UUID): CallDto? = db.transaction { conn ->
        conn.call(householdId, callId)?.takeIf { it.isFor(memberId) }
    }

    /**
     * [memberId] answers [answer] (`COMING` or `CANT`); they can change their mind. Null if they weren't rung,
     * or the call is older than [answerFor].
     */
    fun answer(householdId: UUID, callId: UUID, memberId: UUID, answer: String, now: Instant): CallDto? = db.transaction { conn ->
        val updated = conn.prepareStatement(
            """
            UPDATE call_target t SET answer = ?, answered_at = ?
            FROM call c WHERE c.id = t.call_id AND t.call_id = ? AND t.member_id = ? AND c.household_id = ? AND c.created_at > ?
            """.trimIndent(),
        ).run {
            setString(1, answer); setObject(2, now.toOffset()); setObject(3, callId); setObject(4, memberId)
            setObject(5, householdId); setObject(6, now.minus(answerFor).toOffset())
            executeUpdate()
        }
        if (updated == 0) null else conn.call(householdId, callId)
    }

    /** Members of the household with an approved phone, the ones a call can ring. */
    fun reachable(householdId: UUID): List<String> = db.transaction { conn ->
        conn.prepareStatement(
            """
            SELECT DISTINCT d.member_id FROM device d JOIN member m ON m.id = d.member_id
            WHERE d.household_id = ? AND d.status = 'approved' AND NOT d.revoked AND NOT m.deleted
            """.trimIndent(),
        ).run {
            setObject(1, householdId)
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun Connection.call(householdId: UUID, callId: UUID): CallDto? {
        val head = prepareStatement(
            """
            SELECT c.caller_id, m.display_name, c.message, c.created_at FROM call c JOIN member m ON m.id = c.caller_id
            WHERE c.id = ? AND c.household_id = ?
            """.trimIndent(),
        ).run {
            setObject(1, callId); setObject(2, householdId)
            executeQuery().use { rs ->
                if (!rs.next()) return null
                listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java).toInstant().toEpochMilli())
            }
        }
        val targets = prepareStatement(
            """
            SELECT t.member_id, m.display_name, t.answer FROM call_target t JOIN member m ON m.id = t.member_id
            WHERE t.call_id = ? ORDER BY m.created_at
            """.trimIndent(),
        ).run {
            setObject(1, callId)
            executeQuery().use { rs -> buildList { while (rs.next()) add(CallTargetDto(rs.getString(1), rs.getString(2), rs.getString(3))) } }
        }
        return CallDto(callId.toString(), head[0] as String, head[1] as String, head[2] as String, head[3] as Long, targets)
    }

    private fun CallDto.isFor(memberId: UUID) = callerId == memberId.toString() || targets.any { it.memberId == memberId.toString() }

    companion object {
        /** How long calls are kept (decided 2026-10-23). */
        val keepFor: Duration = Duration.ofDays(7)
    }
}
