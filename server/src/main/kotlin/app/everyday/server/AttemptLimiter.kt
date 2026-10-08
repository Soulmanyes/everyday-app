package app.everyday.server

import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * Counts failed secret checks (household code, admin PIN, setup token) across all clients.
 * Every request arrives through Caddy from the same address, so the limit is global rather than per IP.
 * After [maxFailures] failures within [window], checks are refused for [lockout].
 */
class AttemptLimiter(
    private val maxFailures: Int = 10,
    private val window: Duration = Duration.ofMinutes(15),
    private val lockout: Duration = Duration.ofMinutes(15),
    private val clock: () -> Instant = Instant::now,
) {
    private val failures = ArrayDeque<Instant>()
    private var lockedUntil: Instant? = null

    /** Seconds until checks are allowed again, or null if not locked. */
    @Synchronized
    fun lockedForSeconds(): Long? {
        val until = lockedUntil ?: return null
        val now = clock()
        if (!now.isBefore(until)) {
            lockedUntil = null
            return null
        }
        return Duration.between(now, until).seconds + 1
    }

    @Synchronized
    fun recordFailure() {
        val now = clock()
        failures.addLast(now)
        while (failures.isNotEmpty() && failures.first().isBefore(now.minus(window))) failures.removeFirst()
        if (failures.size >= maxFailures) {
            lockedUntil = now.plus(lockout)
            failures.clear()
        }
    }
}
