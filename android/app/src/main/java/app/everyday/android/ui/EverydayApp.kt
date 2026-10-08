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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import app.everyday.android.ui.chores.ChoreEditorScreen
import app.everyday.android.ui.chores.ChoresScreen
import app.everyday.android.ui.editor.EditorScreen
import app.everyday.android.ui.household.HouseholdViewModel
import app.everyday.android.ui.household.MembersScreen
import app.everyday.android.ui.household.ProfilePicker
import app.everyday.android.ui.household.SetupFlow
import app.everyday.android.ui.settings.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Reminders("Reminders", Icons.Default.DateRange),
    Chores("Chores", Icons.Default.Home),
}

private object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val MEMBERS = "members"
    const val EDIT = "edit?id={id}&choreId={choreId}"
    fun edit(id: String?) = if (id == null) "edit" else "edit?id=$id"
    fun newChoreReminder(choreId: String) = "edit?choreId=$choreId"
    const val CHORE = "chore?id={id}"
    fun chore(id: String?) = if (id == null) "chore" else "chore?id=$id"
}

@Composable
fun EverydayApp() {
    val household: HouseholdViewModel = viewModel(factory = HouseholdViewModel.Factory)
    val session by household.session.collectAsStateWithLifecycle()
    when {
        !session.joined && !session.skippedSetup -> SetupFlow(household)
        session.joined && session.memberId == null -> ProfilePicker(household)
        else -> MainNav(household)
    }
}

@Composable
private fun MainNav(household: HouseholdViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onEditReminder = { id -> nav.navigate(Routes.edit(id)) },
                onEditChore = { id -> nav.navigate(Routes.chore(id)) },
                onAddChoreReminder = { choreId -> nav.navigate(Routes.newChoreReminder(choreId)) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true },
                navArgument("choreId") { type = NavType.StringType; nullable = true },
            ),
        ) { entry ->
            EditorScreen(
                reminderId = entry.arguments?.getString("id"),
                choreId = entry.arguments?.getString("choreId"),
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.CHORE,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
        ) { entry ->
            ChoreEditorScreen(choreId = entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onManageMembers = { nav.navigate(Routes.MEMBERS) })
        }
        composable(Routes.MEMBERS) {
            MembersScreen(household, onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onOpenSettings: () -> Unit,
    onEditReminder: (String?) -> Unit,
    onEditChore: (String?) -> Unit,
    onAddChoreReminder: (String) -> Unit,
) {
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
                Tab.Chores -> ChoresScreen(onEditChore = onEditChore, onAddReminder = onAddChoreReminder)
            }
        }
    }
}
