package app.everyday.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.everyday.android.ui.EverydayApp
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
        setContent {
            EverydayTheme {
                EverydayApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch up on anything missed (e.g. alarms dropped by the system) whenever the app is opened.
        lifecycleScope.launch {
            app.engine.recompute()
            // Picks up member changes, and notices if this phone was removed from the household.
            app.household.refresh()
            app.chores.refresh()
        }
    }
}
