package app.everyday.android.push

import app.everyday.android.app
import app.everyday.android.chores.ChoreRefreshWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the server's data-only "sync now" pushes. The push carries no household data: the phone
 * fetches the change itself, as its 15-minute refresh would.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        app.push.forget()
        runBlocking { app.push.ensureRegistered() }
    }

    /**
     * Runs on a background thread, and a high-priority push gives about 20 seconds. The refresh is a few
     * small requests; if it doesn't finish in time it's left to WorkManager.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "sync") return
        val done = runBlocking { withTimeoutOrNull(15_000) { app.refreshFromServer() } }
        if (done == null) ChoreRefreshWorker.runSoon(this)
    }
}
