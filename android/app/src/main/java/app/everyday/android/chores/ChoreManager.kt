package app.everyday.android.chores

import app.everyday.android.data.ChoreCompletion
import app.everyday.android.data.ChoreRepository
import app.everyday.android.data.ChoreSnapshot
import app.everyday.android.data.ReminderRepository
import app.everyday.android.household.ApiClient
import app.everyday.android.household.ApiError
import app.everyday.android.household.Session
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chores live on the server; this phone keeps a read-only cache. Every call here talks to the server
 * and replaces the cache with the snapshot it returns, then tidies chore reminders and recomputes
 * alarms. Writes throw [ApiError] (status 0 when offline).
 */
class ChoreManager(
    private val session: Session,
    private val chores: ChoreRepository,
    private val reminders: ReminderRepository,
    private val engine: ReminderEngine,
) {
    private val mutex = Mutex()

    private fun client() = ApiClient(session.current.serverUrl, session.current.token)

    /** Fetches the chores. Network errors are ignored: the cache stays as it was. */
    suspend fun refresh() {
        if (!session.current.joined) return
        runCatching { write { chores() } }
    }

    suspend fun save(id: String, title: String, notes: String?, weekdays: Int, completion: ChoreCompletion, assigneeIds: List<String>) =
        write { saveChore(id, title, notes, weekdays, completion, assigneeIds) }

    suspend fun delete(id: String) = write { deleteChore(id) }

    /** Marks the chore done today for the picked profile (for ANY it's done for everyone). */
    suspend fun markDone(id: String) = write { markChoreDone(id, requireMember()) }

    /** ANY: clears today's completion. EACH: clears only the picked profile's part. */
    suspend fun undoDone(id: String) = write { undoChoreDone(id, requireMember()) }

    private fun requireMember() = session.current.memberId ?: throw ApiError(0, "Pick your profile first.")

    private suspend fun write(call: suspend ApiClient.() -> ChoreSnapshot) = mutex.withLock {
        val snapshot = try {
            client().call()
        } catch (e: ApiError) {
            if (e.isUnauthorized) {
                session.clear()
                engine.recompute()
            }
            throw e
        }
        chores.replace(snapshot)
        reminders.deleteUnassignedChoreReminders()
        engine.recompute()
    }
}
