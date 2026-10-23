package app.everyday.android.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.everyday.android.app
import app.everyday.android.household.CallAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** "On my way" / "Can't now" on a ringing call's notification, and the caller's check after the ringing (Phase 12). */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL) ?: return
        val householdId = intent.getStringExtra(EXTRA_HOUSEHOLD) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The time limit keeps the receiver within the system's limit for goAsync().
                withTimeoutOrNull(8_000) {
                    when (intent.action) {
                        ACTION_ANSWER -> intent.getStringExtra(EXTRA_ANSWER)?.let { a ->
                            context.app.calls.answer(householdId, callId, CallAnswer.valueOf(a))
                        }
                        ACTION_CHECK -> context.app.calls.checkAnswers(householdId, callId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "app.everyday.android.action.CALL_ANSWER"
        const val ACTION_CHECK = "app.everyday.android.action.CALL_CHECK"
        const val EXTRA_CALL = "call_id"
        const val EXTRA_HOUSEHOLD = "household_id"
        const val EXTRA_ANSWER = "answer"
    }
}
