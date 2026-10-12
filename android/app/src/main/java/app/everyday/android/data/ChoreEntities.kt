package app.everyday.android.data

import androidx.room.ColumnInfo
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
    /** The household the chore is in (Phase 9). Null before the first refresh after updating to 2.0. */
    val householdId: String? = null,
    /** The member who created it (Phase 9.9); null for chores from before. */
    val createdBy: String? = null,
    /** Only its creator sees it (Phase 9.9). Other members' personal chores never reach this phone. */
    @ColumnInfo(defaultValue = "0") val personal: Boolean = false,
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

/** When an assignee is reminded about a chore: a time ([endMinute] null) or a window. Shown on the timetable. */
@Entity(tableName = "chore_slot", primaryKeys = ["choreId", "memberId", "startMinute"])
data class ChoreSlotEntity(
    val choreId: String,
    val memberId: String,
    val startMinute: Int,
    val endMinute: Int? = null,
)

data class ChoreWithDetails(
    @Embedded val chore: ChoreEntity,
    @Relation(parentColumn = "id", entityColumn = "choreId")
    val assignments: List<ChoreAssignmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "choreId")
    val completions: List<ChoreCompletionEntity>,
    @Relation(parentColumn = "id", entityColumn = "choreId")
    val slots: List<ChoreSlotEntity> = emptyList(),
) {
    val assigneeIds get() = assignments.sortedBy { it.position }.map { it.memberId }

    /** The Chores tab's Mine (Phase 9.9): chores [memberId] is assigned to or created. */
    fun isMine(memberId: String?) = memberId != null && (memberId == chore.createdBy || assignments.any { it.memberId == memberId })
}
