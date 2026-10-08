package app.everyday.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

data class DatabaseConfig(val url: String, val user: String, val password: String) {
    companion object {
        fun fromEnv() = DatabaseConfig(
            url = requireEnv("DATABASE_URL"),
            user = requireEnv("DATABASE_USER"),
            password = requireEnv("DATABASE_PASSWORD"),
        )

        private fun requireEnv(name: String): String =
            System.getenv(name) ?: error("Missing required environment variable $name")
    }
}

/** Connects to Postgres, applies pending Flyway migrations, and registers the pool with Exposed. */
fun Application.configureDatabase(config: DatabaseConfig) {
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = 5
        }
    )
    Flyway.configure().dataSource(dataSource).load().migrate()
    Database.connect(dataSource)
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { dataSource.close() }
}
