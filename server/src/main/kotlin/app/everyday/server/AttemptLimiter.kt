package app.everyday.server

import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * Counts failed secret checks and locks them for a while after too many. Every request arrives through
 * Caddy, so counting is per key rather than per IP (per-IP limits are in RateLimits). Wrong join codes and
 * admin PINs count per household (key = household id), so one household's mistakes never lock out another;
 * setup codes, invites and unknown join ids share the [GLOBAL] key.
 * After [maxFailures] failures within [window], checks for that key are refused for [lockout].
 */
class AttemptLimiter(
    private val maxFailures: Int = 10,
    private val window: Duration = Duration.ofMinutes(15),
    private val lockout: Duration = Duration.ofMinutes(15),
    private val clock: () -> Instant = Instant::now,
) {
    private class State {
        val failures = ArrayDeque<Instant>()
        var lockedUntil: Instant? = null
    }

    private val states = HashMap<String, State>()

    /** Seconds until checks for [key] are allowed again, or null if not locked. */
    @Synchronized
    fun lockedForSeconds(key: String = GLOBAL): Long? {
        val state = states[key] ?: return null
        val until = state.lockedUntil ?: return null
        val now = clock()
        if (!now.isBefore(until)) {
            state.lockedUntil = null
            return null
        }
        return Duration.between(now, until).seconds + 1
    }

    @Synchronized
    fun recordFailure(key: String = GLOBAL) {
        val now = clock()
        val state = states.getOrPut(key, ::State)
        state.failures.addLast(now)
        while (state.failures.isNotEmpty() && state.failures.first().isBefore(now.minus(window))) state.failures.removeFirst()
        if (state.failures.size >= maxFailures) {
            state.lockedUntil = now.plus(lockout)
            state.failures.clear()
        }
        // Keys of households that stopped failing long ago aren't kept forever.
        if (states.size > 1_000) states.entries.removeIf { (_, s) -> s.lockedUntil == null && s.failures.all { it.isBefore(now.minus(window)) } }
    }

    companion object {
        const val GLOBAL = "global"
    }
}
