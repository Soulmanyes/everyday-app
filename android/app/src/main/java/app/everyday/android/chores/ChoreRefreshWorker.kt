package app.everyday.android.chores

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
        // Kept in this worker (not a new one): installed phones have it enqueued by class name.
        applicationContext.app.refreshFromServer()
        return Result.success()
    }

    companion object {
        private const val NAME = "chore_refresh"
        private const val NOW_NAME = "sync_now"

        /** Once, as soon as there is a network: a "sync now" push that couldn't finish in time. */
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<ChoreRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Every 15 minutes (WorkManager's minimum) while there is a network. Safe to call repeatedly. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ChoreRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
