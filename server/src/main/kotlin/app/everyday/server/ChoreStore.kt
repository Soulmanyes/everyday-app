package app.everyday.server

import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** SQL for chores, assignments and completions. Every method runs in its own transaction. */
class ChoreStore(private val db: DataSource) {

    /** Every chore that isn't deleted, with its assignees, plus the completions on [today]. */
    fun snapshot(householdId: UUID, today: LocalDate): ChoresResponse = db.transaction { conn ->
        conn.snapshot(householdId, today)
    }

    private data class Existing(val householdId: UUID, val deleted: Boolean, val completion: ChoreCompletion)

    sealed interface Result {
        data class Ok(val snapshot: ChoresResponse) : Result
        data object NotFound : Result
        data class Invalid(val message: String) : Result
    }

    /** Creates the chore with this client-generated [id], or updates it. A deleted chore stays deleted. */
    fun upsert(householdId: UUID, id: UUID, req: ChoreRequest, assignees: Set<UUID>, today: LocalDate): Result =
        db.transaction { conn ->
            if (!conn.allActiveMembers(householdId, assignees)) return@transaction Result.Invalid("Unknown member.")
            val existing = conn.prepareStatement("SELECT household_id, deleted, completion FROM chore WHERE id = ? FOR UPDATE").run {
                setObject(1, id)
                executeQuery().use { rs ->
                    if (rs.next()) Existing(rs.getObject(1, UUID::class.java), rs.getBoolean(2), ChoreCompletion.valueOf(rs.getString(3))) else null
                }
            }
            if (existing != null && (existing.householdId != householdId || existing.deleted)) return@transaction Result.NotFound

            conn.prepareStatement(
                """
                INSERT INTO chore (id, household_id, title, notes, weekdays, completion) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, notes = EXCLUDED.notes,
                    weekdays = EXCLUDED.weekdays, completion = EXCLUDED.completion, updated_at = now()
                """.trimIndent(),
            ).run {
                setObject(1, id); setObject(2, householdId); setString(3, req.title); setString(4, req.notes)
                setInt(5, req.weekdays); setString(6, req.completion.name); executeUpdate()
            }
            conn.prepareStatement(
                "UPDATE chore_assignment SET deleted = TRUE, updated_at = now() WHERE chore_id = ? AND NOT deleted AND NOT (member_id = ANY (?))",
            ).run {
                setObject(1, id); setArray(2, conn.createArrayOf("uuid", assignees.toTypedArray())); executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO chore_assignment (chore_id, member_id) VALUES (?, ?)
                ON CONFLICT (chore_id, member_id) DO UPDATE SET deleted = FALSE, updated_at = now()
                WHERE chore_assignment.deleted
                """.trimIndent(),
            ).run {
                assignees.forEach { setObject(1, id); setObject(2, it); addBatch() }
                executeBatch()
            }
            // After a type switch, today's completions would mean something else, so the day starts over.
            if (existing != null && existing.completion != req.completion) {
                conn.prepareStatement("DELETE FROM chore_completion WHERE chore_id = ? AND done_on = ?").run {
                    setObject(1, id); setObject(2, today); executeUpdate()
                }
            }
            Result.Ok(conn.snapshot(householdId, today))
        }

    fun delete(householdId: UUID, id: UUID, today: LocalDate): Result = db.transaction { conn ->
        val updated = conn.prepareStatement(
            "UPDATE chore SET deleted = TRUE, updated_at = now() WHERE id = ? AND household_id = ? AND NOT deleted",
        ).run { setObject(1, id); setObject(2, householdId); executeUpdate() }
        if (updated == 0) Result.NotFound else Result.Ok(conn.snapshot(householdId, today))
    }

    /**
     * Records that [memberId] did the chore on [today]. ANY: anyone, at most once per day (a second
     * mark is a no-op). EACH: only an assignee, for their own part.
     */
    fun markDone(householdId: UUID, choreId: UUID, memberId: UUID, today: LocalDate): Result = db.transaction { conn ->
        // Locking the chore row serialises marks, so an ANY chore can't get two completions for a day.
        val completion = conn.lockChore(householdId, choreId) ?: return@transaction Result.NotFound
        if (!conn.allActiveMembers(householdId, setOf(memberId))) return@transaction Result.Invalid("Unknown member.")
        when (completion) {
            ChoreCompletion.ANY -> {
                val alreadyDone = conn.prepareStatement("SELECT 1 FROM chore_completion WHERE chore_id = ? AND done_on = ?").run {
                    setObject(1, choreId); setObject(2, today); executeQuery().use { it.next() }
                }
                if (alreadyDone) return@transaction Result.Ok(conn.snapshot(householdId, today))
            }
            ChoreCompletion.EACH -> if (!conn.isAssigned(choreId, memberId)) {
                return@transaction Result.Invalid("Only people assigned to this chore can tick their part.")
            }
        }
        conn.prepareStatement(
            "INSERT INTO chore_completion (chore_id, member_id, done_on) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        ).run { setObject(1, choreId); setObject(2, memberId); setObject(3, today); executeUpdate() }
        Result.Ok(conn.snapshot(householdId, today))
    }

    /** ANY: clears today's completion, whoever marked it. EACH: clears only [memberId]'s part. */
    fun undoDone(householdId: UUID, choreId: UUID, memberId: UUID, today: LocalDate): Result = db.transaction { conn ->
        val completion = conn.lockChore(householdId, choreId) ?: return@transaction Result.NotFound
        val sql = when (completion) {
            ChoreCompletion.ANY -> "DELETE FROM chore_completion WHERE chore_id = ? AND done_on = ?"
            ChoreCompletion.EACH -> "DELETE FROM chore_completion WHERE chore_id = ? AND done_on = ? AND member_id = ?"
        }
        conn.prepareStatement(sql).run {
            setObject(1, choreId); setObject(2, today)
            if (completion == ChoreCompletion.EACH) setObject(3, memberId)
            executeUpdate()
        }
        Result.Ok(conn.snapshot(householdId, today))
    }

    private fun Connection.lockChore(householdId: UUID, choreId: UUID): ChoreCompletion? =
        prepareStatement("SELECT completion FROM chore WHERE id = ? AND household_id = ? AND NOT deleted FOR UPDATE").run {
            setObject(1, choreId); setObject(2, householdId)
            executeQuery().use { rs -> if (rs.next()) ChoreCompletion.valueOf(rs.getString(1)) else null }
        }

    private fun Connection.isAssigned(choreId: UUID, memberId: UUID): Boolean =
        prepareStatement("SELECT 1 FROM chore_assignment WHERE chore_id = ? AND member_id = ? AND NOT deleted").run {
            setObject(1, choreId); setObject(2, memberId); executeQuery().use { it.next() }
        }

    private fun Connection.allActiveMembers(householdId: UUID, ids: Set<UUID>): Boolean =
        prepareStatement("SELECT count(*) FROM member WHERE household_id = ? AND NOT deleted AND id = ANY (?)").run {
            setObject(1, householdId); setArray(2, createArrayOf("uuid", ids.toTypedArray()))
            executeQuery().use { it.next(); it.getInt(1) == ids.size }
        }

    private fun Connection.snapshot(householdId: UUID, today: LocalDate): ChoresResponse {
        val assignees = prepareStatement(
            """
            SELECT a.chore_id, a.member_id FROM chore_assignment a
            JOIN chore c ON c.id = a.chore_id JOIN member m ON m.id = a.member_id
            WHERE c.household_id = ? AND NOT c.deleted AND NOT a.deleted AND NOT m.deleted
            ORDER BY m.created_at
            """.trimIndent(),
        ).run {
            setObject(1, householdId)
            executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }.groupBy({ it.first }, { it.second })

        val chores = prepareStatement(
            "SELECT id, title, notes, weekdays, completion FROM chore WHERE household_id = ? AND NOT deleted ORDER BY lower(title), id",
        ).run {
            setObject(1, householdId)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString("id")
                        add(
                            ChoreDto(
                                id = id,
                                title = rs.getString("title"),
                                notes = rs.getString("notes"),
                                weekdays = rs.getInt("weekdays"),
                                completion = ChoreCompletion.valueOf(rs.getString("completion")),
                                assigneeIds = assignees[id].orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

        val completions = prepareStatement(
            """
            SELECT cc.chore_id, cc.member_id, cc.done_on FROM chore_completion cc
            JOIN chore c ON c.id = cc.chore_id
            WHERE c.household_id = ? AND NOT c.deleted AND cc.done_on = ?
            ORDER BY cc.created_at
            """.trimIndent(),
        ).run {
            setObject(1, householdId); setObject(2, today)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(ChoreCompletionDto(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate::class.java).toString()))
                }
            }
        }
        return ChoresResponse(today.toString(), chores, completions)
    }
}
