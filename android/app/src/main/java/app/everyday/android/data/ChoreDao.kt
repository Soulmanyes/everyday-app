package app.everyday.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChoreDao {
    @Transaction
    @Query("SELECT * FROM chore ORDER BY title COLLATE NOCASE, id")
    fun observeChores(): Flow<List<ChoreWithDetails>>

    @Transaction
    @Query("SELECT * FROM chore ORDER BY title COLLATE NOCASE, id")
    suspend fun getChores(): List<ChoreWithDetails>

    @Query("DELETE FROM chore")
    suspend fun deleteChores()

    @Query("DELETE FROM chore_assignment")
    suspend fun deleteAssignments()

    @Query("DELETE FROM chore_completion")
    suspend fun deleteCompletions()

    @Insert
    suspend fun insertChores(chores: List<ChoreEntity>)

    @Insert
    suspend fun insertAssignments(assignments: List<ChoreAssignmentEntity>)

    @Insert
    suspend fun insertCompletions(completions: List<ChoreCompletionEntity>)

    /** Replaces the whole cache with a server snapshot. */
    @Transaction
    suspend fun replaceAll(
        chores: List<ChoreEntity>,
        assignments: List<ChoreAssignmentEntity>,
        completions: List<ChoreCompletionEntity>,
    ) {
        deleteChores()
        deleteAssignments()
        deleteCompletions()
        insertChores(chores)
        insertAssignments(assignments)
        insertCompletions(completions)
    }
}
