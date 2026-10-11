package app.everyday.server

import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * The server owner's tool (Phase 9). Shows no names, only what's needed to spot and remove abuse:
 *
 *     docker compose exec api java -cp app.jar app.everyday.server.OwnerToolKt list
 *     docker compose exec api java -cp app.jar app.everyday.server.OwnerToolKt remove <household id>
 *
 * `remove` deletes the household and every household created through its invites, down the chain.
 * The owner's own (protected) household can't be removed.
 */
fun main(args: Array<String>) {
    val dataSource = createDataSource(DatabaseConfig.fromEnv())
    val tool = OwnerTool(dataSource)
    val output = when (args.firstOrNull()) {
        "list" -> tool.list()
        "remove" -> {
            val id = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: fail("Usage: remove <household id>")
            when (val result = tool.remove(id)) {
                OwnerTool.Removal.NotFound -> fail("No such household.")
                OwnerTool.Removal.Protected -> fail("That household (or one it invited) is protected.")
                is OwnerTool.Removal.Removed -> "Removed ${result.ids.size} household(s): ${result.ids.joinToString()}"
            }
        }
        else -> fail("Usage: list | remove <household id>")
    }
    println(output)
    dataSource.close()
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

class OwnerTool(private val db: DataSource) {

    data class Row(
        val id: UUID,
        val invitedBy: UUID?,
        val members: Int,
        val createdAt: OffsetDateTime,
        val lastActive: OffsetDateTime,
        val protected: Boolean,
        val markedForCleanup: Boolean,
    )

    fun rows(): List<Row> = db.transaction { conn ->
        conn.prepareStatement(
            """
            SELECT h.id, h.invited_by, h.created_at, h.protected, h.cleanup_warned_at IS NOT NULL,
                (SELECT count(*) FROM member m WHERE m.household_id = h.id AND NOT m.deleted),
                greatest(h.created_at, (SELECT max(d.last_seen_at) FROM device d WHERE d.household_id = h.id))
            FROM household h ORDER BY h.created_at
            """.trimIndent(),
        ).executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        Row(
                            id = rs.getObject(1, UUID::class.java),
                            invitedBy = rs.getObject(2, UUID::class.java),
                            createdAt = rs.getObject(3, OffsetDateTime::class.java),
                            protected = rs.getBoolean(4),
                            markedForCleanup = rs.getBoolean(5),
                            members = rs.getInt(6),
                            lastActive = rs.getObject(7, OffsetDateTime::class.java),
                        ),
                    )
                }
            }
        }
    }

    fun list(): String = rows().joinToString("\n", prefix = "id | invited by | members | created | last active | flags\n") { r ->
        val flags = listOfNotNull("protected".takeIf { r.protected }, "marked for cleanup".takeIf { r.markedForCleanup }).joinToString()
        "${r.id} | ${r.invitedBy ?: "-"} | ${r.members} | ${r.createdAt.toLocalDate()} | ${r.lastActive.toLocalDate()} | $flags"
    }

    sealed interface Removal {
        data object NotFound : Removal
        data object Protected : Removal
        data class Removed(val ids: List<UUID>) : Removal
    }

    /** Removes [id] and every household it invited, directly or further down the chain. */
    fun remove(id: UUID): Removal {
        val rows = rows()
        if (rows.none { it.id == id }) return Removal.NotFound
        val chain = mutableListOf(id)
        var i = 0
        while (i < chain.size) {
            chain += rows.filter { it.invitedBy == chain[i] && it.id !in chain }.map { it.id }
            i++
        }
        if (rows.any { it.id in chain && it.protected }) return Removal.Protected
        db.transaction { conn -> chain.forEach { conn.deleteHousehold(it) } }
        return Removal.Removed(chain)
    }
}
