package app.everyday.android.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.everyday.android.app
import app.everyday.android.household.CallAnswer
import app.everyday.android.household.CallInfo
import app.everyday.android.ui.EverydayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The ringing screen (Phase 12), opened by the call notification's full-screen intent, also over the lock
 * screen. The ringing itself is the notification's; answering here stops it, and so does the time running out.
 */
class CallActivity : ComponentActivity() {
    private val callId get() = intent.getStringExtra(EXTRA_CALL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        showing = WeakReference(this)
        val householdId = intent.getStringExtra(EXTRA_HOUSEHOLD).orEmpty()
        val callerName = intent.getStringExtra(EXTRA_CALLER).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        val endsAt = intent.getLongExtra(EXTRA_UNTIL, 0)
        lifecycleScope.launch {
            delay((endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            finish()
        }
        setContent {
            val followPhone by app.prefs.followPhoneTheme.collectAsStateWithLifecycle()
            EverydayTheme(followPhone) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$callerName is calling", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                            Text(
                                message,
                                Modifier.padding(top = 24.dp),
                                style = MaterialTheme.typography.displaySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FilledTonalButton(onClick = { answer(householdId, CallAnswer.CANT) }, Modifier.weight(1f).height(64.dp)) {
                                Text("Can't now")
                            }
                            Button(onClick = { answer(householdId, CallAnswer.COMING) }, Modifier.weight(1f).height(64.dp)) {
                                Text("On my way")
                            }
                        }
                    }
                }
            }
        }
    }

    /** Another call while this screen shows: show that one instead (the first keeps ringing in its notification). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun answer(householdId: String, answer: CallAnswer) {
        val id = callId ?: return finish()
        // The app's scope, so the answer still goes out after this screen closes.
        app.mainScope.launch { app.calls.answer(householdId, id, answer) }
        finish()
    }

    override fun onDestroy() {
        if (showing?.get() === this) showing = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CALL = "call_id"
        private const val EXTRA_HOUSEHOLD = "household_id"
        private const val EXTRA_CALLER = "caller_name"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_UNTIL = "ring_until"

        private var showing: WeakReference<CallActivity>? = null

        /** [ringUntil]: this phone's clock time when the ringing stops and the screen closes. */
        fun intent(context: Context, call: CallInfo, householdId: String, ringUntil: Long): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_CALL, call.id).putExtra(EXTRA_HOUSEHOLD, householdId)
                .putExtra(EXTRA_CALLER, call.callerName).putExtra(EXTRA_MESSAGE, call.message)
                .putExtra(EXTRA_UNTIL, ringUntil)

        /** Closes the ringing screen for [callId] if it's showing (answered here or on another phone). */
        fun finishShowing(callId: String) {
            showing?.get()?.takeIf { it.callId == callId }?.let { it.runOnUiThread { it.finish() } }
        }
    }
}
