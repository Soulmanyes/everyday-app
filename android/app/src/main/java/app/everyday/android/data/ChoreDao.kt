package app.everyday.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChoreDao {
    /** The chores of [householdId]. Chores cached before 2.0 have no household and show until replaced. */
    @Transaction
    @Query("SELECT * FROM chore WHERE householdId = :householdId OR householdId IS NULL ORDER BY title COLLATE NOCASE, id")
    fun observeChores(householdId: String?): Flow<List<ChoreWithDetails>>

    @Transaction
    @Query("SELECT * FROM chore ORDER BY title COLLATE NOCASE, id")
    suspend fun getChores(): List<ChoreWithDetails>

    @Query("DELETE FROM chore WHERE householdId = :householdId OR householdId IS NULL")
    suspend fun deleteChores(householdId: String)

    @Query("DELETE FROM chore_assignment WHERE choreId IN (SELECT id FROM chore WHERE householdId = :householdId OR householdId IS NULL)")
    suspend fun deleteAssignments(householdId: String)

    @Query("DELETE FROM chore_completion WHERE choreId IN (SELECT id FROM chore WHERE householdId = :householdId OR householdId IS NULL)")
    suspend fun deleteCompletions(householdId: String)

    @Query("DELETE FROM chore_slot WHERE choreId IN (SELECT id FROM chore WHERE householdId = :householdId OR householdId IS NULL)")
    suspend fun deleteSlots(householdId: String)

    @Insert
    suspend fun insertSlots(slots: List<ChoreSlotEntity>)

    @Insert
    suspend fun insertChores(chores: List<ChoreEntity>)

    @Insert
    suspend fun insertAssignments(assignments: List<ChoreAssignmentEntity>)

    @Insert
    suspend fun insertCompletions(completions: List<ChoreCompletionEntity>)

    /** Replaces one household's cache with a server snapshot (and any chores cached before 2.0). */
    @Transaction
    suspend fun replaceHousehold(
        householdId: String,
        chores: List<ChoreEntity>,
        assignments: List<ChoreAssignmentEntity>,
        completions: List<ChoreCompletionEntity>,
        slots: List<ChoreSlotEntity>,
    ) {
        // Rows that point at the chores go first, while the chores still say which household they're in.
        deleteAssignments(householdId)
        deleteCompletions(householdId)
        deleteSlots(householdId)
        deleteChores(householdId)
        insertChores(chores)
        insertAssignments(assignments)
        insertCompletions(completions)
        insertSlots(slots)
    }
}
