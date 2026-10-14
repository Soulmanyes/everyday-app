package app.everyday.server

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Wrong admin PINs (V17). Counted in the database, so a restart doesn't reset them. After [maxFailures] wrong
 * PINs within [window] the key is locked; a lockout that starts within a day of the previous one ending lasts
 * the next of [lockouts] (15 min, 1 h, then 24 h each time), so a 4-digit PIN can't be guessed in days.
 * A right PIN clears the key. Keys are per household for joining and per phone for admin routes ([joinKey],
 * [deviceKey]), so wrong PINs on one path never lock the admin out of the other.
 */
class PinGuard(
    private val db: DataSource,
    private val maxFailures: Int = 10,
    private val window: Duration = Duration.ofMinutes(15),
    private val lockouts: List<Duration> = listOf(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(24)),
    private val clock: () -> Instant = Instant::now,
) {
    /** Seconds until PINs for [key] are checked again, or null if not locked. */
    fun lockedForSeconds(key: String): Long? = db.transaction { conn ->
        conn.prepareStatement("SELECT locked_until FROM pin_lock WHERE key = ?").run {
            setString(1, key)
            executeQuery().use { rs -> if (rs.next()) rs.getObject(1, OffsetDateTime::class.java)?.toInstant() else null }
        }?.let { until ->
            val now = clock()
            if (now.isBefore(until)) Duration.between(now, until).seconds + 1 else null
        }
    }

    /**
     * Counts a wrong PIN for [key] in [householdId]. Returns true when it locked the key: the household's
     * pin_locked_at is set, so the admin's phones can say so.
     */
    fun recordFailure(householdId: UUID, key: String): Boolean = db.transaction { conn ->
        val now = clock()
        conn.prepareStatement(
            "INSERT INTO pin_lock (key, household_id, window_start) VALUES (?, ?, ?) ON CONFLICT (key) DO NOTHING",
        ).run { setString(1, key); setObject(2, householdId); setObject(3, now.utc()); executeUpdate() }
        val (failures, windowStart, lockedUntil, strikes) = conn.prepareStatement(
            "SELECT failures, window_start, locked_until, strikes FROM pin_lock WHERE key = ? FOR UPDATE",
        ).run {
            setString(1, key)
            executeQuery().use { rs ->
                rs.next()
                Row(
                    rs.getInt(1),
                    rs.getObject(2, OffsetDateTime::class.java).toInstant(),
                    rs.getObject(3, OffsetDateTime::class.java)?.toInstant(),
                    rs.getInt(4),
                )
            }
        }
        val fresh = windowStart.isBefore(now.minus(window))
        val count = (if (fresh) 0 else failures) + 1
        val start = if (fresh) now else windowStart
        if (count < maxFailures) {
            conn.prepareStatement("UPDATE pin_lock SET failures = ?, window_start = ? WHERE key = ?").run {
                setInt(1, count); setObject(2, start.utc()); setString(3, key); executeUpdate()
            }
            return@transaction false
        }
        // Locked again within a day of the last lockout ending: the next, longer one.
        val strike = if (lockedUntil != null && lockedUntil.isAfter(now.minus(Duration.ofDays(1)))) strikes + 1 else 1
        val until = now.plus(lockouts[minOf(strike, lockouts.size) - 1])
        conn.prepareStatement("UPDATE pin_lock SET failures = 0, window_start = ?, locked_until = ?, strikes = ? WHERE key = ?").run {
            setObject(1, now.utc()); setObject(2, until.utc()); setInt(3, strike); setString(4, key); executeUpdate()
        }
        conn.prepareStatement("UPDATE household SET pin_locked_at = ? WHERE id = ?").run {
            setObject(1, now.utc()); setObject(2, householdId); executeUpdate()
        }
        true
    }

    /** The right PIN: the key starts over. */
    fun clear(key: String) = db.transaction { conn ->
        conn.prepareStatement("DELETE FROM pin_lock WHERE key = ?").run { setString(1, key); executeUpdate() }
    }

    private data class Row(val failures: Int, val windowStart: Instant, val lockedUntil: Instant?, val strikes: Int)

    private fun Instant.utc() = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    companion object {
        fun joinKey(householdId: UUID) = "join:$householdId"
        fun deviceKey(deviceId: UUID) = "device:$deviceId"
    }
}
