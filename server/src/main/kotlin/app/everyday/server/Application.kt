package app.everyday.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataSource = createDataSource(DatabaseConfig.fromEnv())
    val pushStore = PushStore(dataSource)
    // Instant updates need the Firebase key (FCM_CREDENTIALS); without it phones rely on their 15-minute refresh.
    val push = FcmSender.fromEnv(pushStore)?.let { Push(pushStore, it) }
    LoggerFactory.getLogger("app.everyday.server").info("Instant updates (FCM): {}", if (push != null) "on" else "off")
    val api = ApiContext(
        store = HouseholdStore(dataSource),
        chores = ChoreStore(dataSource),
        sync = SyncStore(dataSource),
        secrets = Secrets(),
        limiter = AttemptLimiter(),
        setupToken = System.getenv("SETUP_TOKEN")?.takeIf { it.isNotBlank() },
        push = push,
    )
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(api)
        monitor.subscribe(ApplicationStopped) { dataSource.close() }
    }.start(wait = true)
}

/** Installs plugins and routes. [api] is null in tests that only need /health. */
fun Application.module(api: ApiContext? = null) {
    // encodeDefaults: the phone's parser expects every field, including ones equal to their default.
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    // Default call logging records method, path and status only: no headers or bodies, so no secrets.
    install(CallLogging)
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorResponse(e.message)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request.")) }
        // The rate limiter answers 429 without a body; the app shows the "error" field.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorResponse("Too many requests. Try again in a minute."))
        }
    }
    installRateLimits(api?.rateLimits ?: RateLimits())
    configureRouting(api)
}
