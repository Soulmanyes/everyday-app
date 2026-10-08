package app.everyday.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.everyday.android.ui.calendar.CalendarScreen
import app.everyday.android.ui.editor.EditorScreen
import app.everyday.android.ui.settings.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Reminders("Reminders", Icons.Default.DateRange),
    Chores("Chores", Icons.Default.Home),
}

private object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val EDIT = "edit?id={id}"
    fun edit(id: String?) = if (id == null) "edit" else "edit?id=$id"
}

@Composable
fun EverydayApp() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onEditReminder = { id -> nav.navigate(Routes.edit(id)) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
        ) { entry ->
            EditorScreen(reminderId = entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onOpenSettings: () -> Unit, onEditReminder: (String?) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Reminders) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Reminders -> CalendarScreen(onEditReminder = onEditReminder)
                Tab.Chores -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chores coming soon", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
