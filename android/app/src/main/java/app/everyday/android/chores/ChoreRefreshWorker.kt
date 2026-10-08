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
 * Refreshes the chore cache in the background, so a chore done on another phone silences this
 * phone's chore reminders even while the app is closed.
 */
class ChoreRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.app.chores.refresh()
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
