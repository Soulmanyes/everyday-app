package app.everyday.server

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Deletes households nobody uses any more (Phase 9). A household is in use while any of its phones talks to
 * the server, and every installed app checks in every 15 minutes, so "unused" means no phone has been seen for
 * [unusedFor]. Such a household is marked first, and deleted only if it's still unused [grace] later; a phone
 * coming back clears the mark. The server owner's household ([HouseholdRow.protected]) is never deleted.
 */
class Cleanup(
    private val db: DataSource,
    private val unusedFor: Duration = Duration.ofDays(365),
    private val grace: Duration = Duration.ofDays(30),
) {
    data class Outcome(val marked: List<UUID>, val cleared: List<UUID>, val deleted: List<UUID>)

    fun run(now: Instant): Outcome = db.transaction { conn ->
        // Last activity: the newest phone contact, or the creation date for a household no phone has used.
        val households = conn.prepareStatement(
            """
            SELECT h.id, h.cleanup_warned_at, greatest(h.created_at, max(d.last_seen_at)) AS last_active
            FROM household h LEFT JOIN device d ON d.household_id = h.id
            WHERE NOT h.protected
            GROUP BY h.id
            """.trimIndent(),
        ).executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        Triple(
                            rs.getObject(1, UUID::class.java),
                            rs.getObject(2, java.time.OffsetDateTime::class.java)?.toInstant(),
                            rs.getObject(3, java.time.OffsetDateTime::class.java).toInstant(),
                        ),
                    )
                }
            }
        }
        val marked = mutableListOf<UUID>()
        val cleared = mutableListOf<UUID>()
        val deleted = mutableListOf<UUID>()
        for ((id, warnedAt, lastActive) in households) {
            val unused = lastActive.isBefore(now.minus(unusedFor))
            when {
                warnedAt != null && lastActive.isAfter(warnedAt) -> cleared += id
                !unused -> Unit
                warnedAt == null -> marked += id
                !warnedAt.isAfter(now.minus(grace)) -> deleted += id
            }
        }
        conn.prepareStatement("UPDATE household SET cleanup_warned_at = ? WHERE id = ?").run {
            marked.forEach { setObject(1, now.toOffset()); setObject(2, it); addBatch() }
            cleared.forEach { setObject(1, null); setObject(2, it); addBatch() }
            executeBatch()
        }
        deleted.forEach { conn.deleteHousehold(it) }
        // Calls (Phase 12) are only needed while they ring and get answered.
        conn.prepareStatement("DELETE FROM call WHERE created_at < ?").run {
            setObject(1, now.minus(CallStore.keepFor).toOffset()); executeUpdate()
        }
        Outcome(marked, cleared, deleted)
    }

    /** Runs once an hour after start, then daily. Only counts are logged, never names. */
    fun schedule(clock: () -> Instant = Instant::now) {
        val log = LoggerFactory.getLogger(Cleanup::class.java)
        Executors.newSingleThreadScheduledExecutor { Thread(it, "cleanup").apply { isDaemon = true } }.scheduleAtFixedRate(
            {
                try {
                    val outcome = run(clock())
                    if (outcome.marked.isNotEmpty() || outcome.deleted.isNotEmpty() || outcome.cleared.isNotEmpty()) {
                        log.info("Cleanup: marked {}, cleared {}, deleted {} unused households", outcome.marked.size, outcome.cleared.size, outcome.deleted.size)
                    }
                } catch (e: Exception) {
                    log.warn("Cleanup failed: {}", e.toString())
                }
            },
            1, 24, TimeUnit.HOURS,
        )
    }
}
