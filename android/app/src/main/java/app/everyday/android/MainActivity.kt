package app.everyday.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.everyday.android.ui.EverydayApp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.everyday.android.ui.EverydayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { app.engine.recompute() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) {
            openJoinLink(intent)
            openHousehold(intent)
        }
        setContent {
            val followPhone by app.prefs.followPhoneTheme.collectAsStateWithLifecycle()
            EverydayTheme(followPhone) {
                EverydayApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openJoinLink(intent)
        openHousehold(intent)
    }

    /** A notification about one household (e.g. a join request) opens that household. */
    private fun openHousehold(intent: Intent?) {
        intent?.getStringExtra(EXTRA_HOUSEHOLD)?.let { app.openHousehold.value = it }
    }

    /** A join link (Phase 9) opens the join screen with its code filled in. */
    private fun openJoinLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != BuildConfig.JOIN_SCHEME || uri.host != "join") return
        val code = uri.getQueryParameter("code") ?: uri.fragment
        if (!code.isNullOrBlank()) app.joinLink.value = code.trim().uppercase().take(24)
    }

    override fun onResume() {
        super.onResume()
        // Catch up on anything missed (e.g. alarms dropped by the system) whenever the app is opened.
        lifecycleScope.launch {
            app.engine.recompute()
            // Picks up member changes, and notices if this phone was removed from the household.
            app.household.refresh()
            app.chores.refresh()
            app.sync.requestSync()
            app.updates.check()
        }
    }

    companion object {
        const val EXTRA_HOUSEHOLD = "household_id"
    }
}
