package app.everyday.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

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

/** Creates the connection pool and applies pending Flyway migrations. */
fun createDataSource(config: DatabaseConfig): HikariDataSource {
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = 5
        }
    )
    migrate(dataSource)
    return dataSource
}

fun migrate(dataSource: DataSource) {
    Flyway.configure().dataSource(dataSource).load().migrate()
}

/** Runs [block] in a transaction, committing on success and rolling back on any exception. */
fun <T> DataSource.transaction(block: (Connection) -> T): T = connection.use { conn ->
    conn.autoCommit = false
    try {
        block(conn).also { conn.commit() }
    } catch (e: Throwable) {
        conn.rollback()
        throw e
    }
}
