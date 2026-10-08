package app.everyday.android.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

/*
 * Read-only cache of the server's chores (Phase 3). The whole cache is replaced from every server
 * response, so these tables have no sync fields and no foreign keys.
 */

/** ANY: done for the day once anyone marks it. EACH: every assignee does their own part. */
enum class ChoreCompletion { ANY, EACH }

@Entity(tableName = "chore")
data class ChoreEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val weekdays: Int,
    val completion: ChoreCompletion,
)

@Entity(tableName = "chore_assignment", primaryKeys = ["choreId", "memberId"])
data class ChoreAssignmentEntity(
    val choreId: String,
    val memberId: String,
    /** Keeps the server's assignee order. */
    val position: Int,
)

/** A member did the chore on [doneOn] (ISO date in the household timezone). */
@Entity(tableName = "chore_completion", primaryKeys = ["choreId", "memberId", "doneOn"])
data class ChoreCompletionEntity(
    val choreId: String,
    val memberId: String,
    val doneOn: String,
)

data class ChoreWithDetails(
    @Embedded val chore: ChoreEntity,
    @Relation(parentColumn = "id", entityColumn = "choreId")
    val assignments: List<ChoreAssignmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "choreId")
    val completions: List<ChoreCompletionEntity>,
) {
    val assigneeIds get() = assignments.sortedBy { it.position }.map { it.memberId }
}
