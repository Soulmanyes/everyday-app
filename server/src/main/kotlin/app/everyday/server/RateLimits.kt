package app.everyday.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

val SECRETS_LIMIT = RateLimitName("secrets")
val ADMIN_LIMIT = RateLimitName("admin")

/**
 * Requests allowed per client IP in each [period]. The household's phones usually share one IP
 * (home WiFi), so [other] stays well above what sync and refresh need.
 */
class RateLimits(
    /** Creating the household and joining: the routes that take the household code or setup token. */
    val secrets: Int = 10,
    /** Routes that need the admin PIN. */
    val admin: Int = 30,
    /** Every request, including the two groups above. */
    val other: Int = 300,
    val period: Duration = 1.minutes,
)

fun Application.installRateLimits(limits: RateLimits) {
    install(RateLimit) {
        global {
            rateLimiter(limit = limits.other, refillPeriod = limits.period)
            requestKey { clientIp(it) }
        }
        register(SECRETS_LIMIT) {
            rateLimiter(limit = limits.secrets, refillPeriod = limits.period)
            requestKey { clientIp(it) }
        }
        register(ADMIN_LIMIT) {
            rateLimiter(limit = limits.admin, refillPeriod = limits.period)
            requestKey { clientIp(it) }
        }
    }
}

/**
 * The phone's IP. Caddy replaces any X-Forwarded-For the client sends with the address it sees,
 * and the API port isn't published, so the header can be trusted. Without it (tests, the test
 * stack) every request counts against the connecting address.
 */
private fun clientIp(call: ApplicationCall): String =
    call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: call.request.origin.remoteAddress
