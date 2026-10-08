package app.everyday.android.data

import app.everyday.android.chores.ChoreRules
import app.everyday.android.chores.Completion
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** A server snapshot of the household's chores, ready to replace the cache. */
data class ChoreSnapshot(
    val today: LocalDate,
    val chores: List<ChoreEntity>,
    /** Assignee ids per chore id, in the server's order. */
    val assignees: Map<String, List<String>>,
    val completions: List<ChoreCompletionEntity>,
    val slots: List<ChoreSlotEntity> = emptyList(),
)

/** The cached chores. Only [replace] writes them; changes go through the server (see ChoreManager). */
class ChoreRepository(private val db: AppDatabase) {
    private val chores = db.choreDao()

    fun observeChores(): Flow<List<ChoreWithDetails>> = chores.observeChores()

    suspend fun replace(snapshot: ChoreSnapshot) = chores.replaceAll(
        snapshot.chores,
        snapshot.chores.flatMap { c ->
            snapshot.assignees[c.id].orEmpty().mapIndexed { i, member -> ChoreAssignmentEntity(c.id, member, i) }
        },
        snapshot.completions,
        snapshot.slots,
    )

    /** For each cached chore, the days it counts as done for [memberId] (see [ChoreRules.doneDatesFor]). */
    suspend fun doneDatesFor(memberId: String): Map<String, Set<LocalDate>> =
        chores.getChores().associate { c ->
            c.chore.id to ChoreRules.doneDatesFor(c.chore.completion, c.completions.map { it.toCompletion() }, memberId)
        }
}

fun ChoreCompletionEntity.toCompletion() = Completion(memberId, LocalDate.parse(doneOn))
