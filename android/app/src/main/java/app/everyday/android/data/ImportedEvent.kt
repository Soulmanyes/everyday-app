package app.everyday.android.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * An event imported from the phone's calendar (Phase 10), so importing it again is skipped. Kept on this phone
 * only. [sourceKey] names the calendar event: its iCal UID (or the phone's row id), plus the start time for one
 * occurrence of a repeat that was imported as a one-off event. Per member, so another household can import it too.
 */
@Entity(tableName = "imported_event", primaryKeys = ["memberId", "sourceKey"])
data class ImportedEventEntity(
    val memberId: String,
    val sourceKey: String,
    val eventId: String,
    val importedAt: Long,
)

@Dao
interface ImportDao {
    /** Source keys [memberId] imported whose event still exists; a deleted event can be imported again. */
    @Query(
        "SELECT i.sourceKey FROM imported_event i JOIN event e ON e.id = i.eventId " +
            "WHERE i.memberId = :memberId AND NOT e.deleted",
    )
    suspend fun importedKeys(memberId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<ImportedEventEntity>)
}
