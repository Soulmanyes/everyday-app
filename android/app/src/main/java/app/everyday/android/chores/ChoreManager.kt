package app.everyday.android.chores

import app.everyday.android.data.ChoreCompletion
import app.everyday.android.data.ChoreRepository
import app.everyday.android.data.ChoreSnapshot
import app.everyday.android.data.ReminderRepository
import app.everyday.android.household.ApiClient
import app.everyday.android.household.ApiError
import app.everyday.android.household.Membership
import app.everyday.android.household.Session
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chores live on the server; this phone keeps a read-only cache of every household it's in. Every call
 * here talks to the server and replaces that household's cache with the snapshot it returns, then tidies
 * chore reminders and recomputes alarms. Writes act on the picked household and throw [ApiError]
 * (status 0 when offline).
 */
class ChoreManager(
    private val session: Session,
    private val chores: ChoreRepository,
    private val reminders: ReminderRepository,
    private val engine: ReminderEngine,
) {
    private val mutex = Mutex()

    /** Fetches every household's chores. Network errors are ignored: the cache stays as it was. */
    suspend fun refresh() {
        for (m in session.current.memberships) runCatching { write(m) { chores() } }
    }

    suspend fun save(id: String, title: String, notes: String?, weekdays: Int, completion: ChoreCompletion, assigneeIds: List<String>) =
        write { saveChore(id, title, notes, weekdays, completion, assigneeIds) }

    suspend fun delete(id: String) = write { deleteChore(id) }

    /** Marks the chore done today for the picked profile (for ANY it's done for everyone). */
    suspend fun markDone(id: String) = write { markChoreDone(id, requireMember()) }

    /**
     * Marks the chore done as [memberId], in whichever of this phone's households that member is in: a
     * notification's Done can come from a household that isn't picked (Phase 9).
     */
    suspend fun markDoneAs(id: String, memberId: String) {
        val m = session.current.memberships.firstOrNull { it.memberId == memberId } ?: return
        write(m) { markChoreDone(id, memberId) }
    }

    /** ANY: clears today's completion. EACH: clears only the picked profile's part. */
    suspend fun undoDone(id: String) = write { undoChoreDone(id, requireMember()) }

    private fun requireMember() = session.current.memberId ?: throw ApiError(0, "Pick your profile first.")

    private suspend fun write(call: suspend ApiClient.() -> ChoreSnapshot) =
        write(session.current.picked ?: throw ApiError(0, "Join a household first."), call)

    private suspend fun write(m: Membership, call: suspend ApiClient.() -> ChoreSnapshot) = mutex.withLock {
        val snapshot = try {
            ApiClient(m.serverUrl, m.token).call()
        } catch (e: ApiError) {
            if (e.isUnauthorized) {
                session.remove(m.token)
                engine.recompute()
            }
            throw e
        }
        // A phone updated from 1.x learns its household id on the first refresh; until then the cache waits.
        val householdId = session.current.memberships.firstOrNull { it.token == m.token }?.householdId ?: return@withLock
        chores.replace(householdId, snapshot)
        reminders.deleteUnassignedChoreReminders()
        engine.recompute()
    }
}
