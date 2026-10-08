package app.everyday.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureDatabase(DatabaseConfig.fromEnv())
        module()
    }.start(wait = true)
}

/** Everything except the database, so tests can start the app without Postgres. */
fun Application.module() {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    configureRouting()
}
