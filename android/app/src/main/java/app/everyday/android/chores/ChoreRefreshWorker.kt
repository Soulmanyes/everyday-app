package app.everyday.android.chores

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.everyday.android.app
import java.util.concurrent.TimeUnit

/**
 * Refreshes the chore cache and syncs reminders in the background, so changes from other phones
 * (a chore done, an invite, an edited shared reminder) arrive even while the app is closed.
 */
class ChoreRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        app.chores.refresh()
        // Kept in this worker (not a new one): installed phones have it enqueued by class name.
        runCatching { app.sync.sync() }
        return Result.success()
    }

    companion object {
        private const val NAME = "chore_refresh"

        /** Every 15 minutes (WorkManager's minimum) while there is a network. Safe to call repeatedly. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ChoreRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
