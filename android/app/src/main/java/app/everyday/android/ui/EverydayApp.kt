package app.everyday.android.ui

import app.everyday.android.app
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.LaunchedEffect
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
import app.everyday.android.ui.household.HouseholdPicker
import app.everyday.android.ui.household.HouseholdViewModel
import app.everyday.android.ui.household.JoinCodeDialog
import app.everyday.android.ui.household.MembersScreen
import app.everyday.android.ui.household.WaitingScreen
import app.everyday.android.ui.household.SetupFlow
import app.everyday.android.ui.settings.SettingsScreen
import app.everyday.android.ui.event.EventEditorScreen
import app.everyday.android.ui.reminders.RemindersScreen
import app.everyday.android.ui.whatsnew.WhatsNewDialog
import app.everyday.android.ui.whatsnew.WhatsNewScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Events("Events", EventIcon),
    Chores("Chores", ChoreIcon),
    Reminders("Reminders", ReminderIcon),
}

private object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val MEMBERS = "members"
    const val WHATS_NEW = "whats-new"
    const val EDIT = "edit?id={id}&choreId={choreId}"
    fun edit(id: String?) = if (id == null) "edit" else "edit?id=$id"
    fun newChoreReminder(choreId: String) = "edit?choreId=$choreId"
    const val EVENT = "event?id={id}"
    fun event(id: String?) = if (id == null) "event" else "event?id=$id"
    const val CHORE = "chore?id={id}"
    fun chore(id: String?) = if (id == null) "chore" else "chore?id=$id"
}

@Composable
fun EverydayApp() {
    val household: HouseholdViewModel = viewModel(factory = HouseholdViewModel.Factory)
    val session by household.session.collectAsStateWithLifecycle()
    val app = LocalContext.current.app
    val joinLink by app.joinLink.collectAsStateWithLifecycle()
    LaunchedEffect(joinLink) {
        joinLink?.let { household.openJoinLink(it); app.joinLink.value = null }
    }
    when {
        household.addingHousehold -> SetupFlow(household, onCancel = { household.addingHousehold = false })
        !session.joined && !session.skippedSetup -> SetupFlow(household)
        // Phase 9: a phone in several households asks which one to open each time the app starts.
        session.memberships.size > 1 && !household.pickerDone -> HouseholdPicker(household)
        session.joined && session.memberId == null -> WaitingScreen(household)
        else -> MainNav(household)
    }
    household.shownJoinCode?.let { code ->
        JoinCodeDialog(code, session.householdName.orEmpty(), session.serverUrl, onDismiss = { household.shownJoinCode = null })
    }
}

@Composable
private fun MainNav(household: HouseholdViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onEditEvent = { id -> nav.navigate(Routes.event(id)) },
                onEditChoreReminder = { id -> nav.navigate(Routes.edit(id)) },
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
            Routes.EVENT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
        ) { entry ->
            EventEditorScreen(eventId = entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(
            Routes.CHORE,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
        ) { entry ->
            ChoreEditorScreen(choreId = entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                household = household,
                onBack = { nav.popBackStack() },
                onManageMembers = { nav.navigate(Routes.MEMBERS) },
                onWhatsNew = { nav.navigate(Routes.WHATS_NEW) },
            )
        }
        composable(Routes.WHATS_NEW) {
            WhatsNewScreen(onBack = { nav.popBackStack() })
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
    onEditEvent: (String?) -> Unit,
    onEditChoreReminder: (String) -> Unit,
    onEditChore: (String?) -> Unit,
    onAddChoreReminder: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Events) }
    val context = LocalContext.current
    val update by context.app.updates.update.collectAsStateWithLifecycle()
    val whatsNew by context.app.prefs.whatsNew.collectAsStateWithLifecycle()
    if (whatsNew.isNotEmpty()) WhatsNewDialog(whatsNew, onDismiss = context.app.prefs::dismissWhatsNew)
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            update?.let { u ->
                Text(
                    "Version ${u.versionName} is available. Tap to download and install it.",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.downloadUrl))) }
                        .padding(12.dp),
                )
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.Events -> CalendarScreen(
                        onEditEvent = onEditEvent,
                        onEditChoreReminder = onEditChoreReminder,
                        onEditChore = onEditChore,
                        onAddChoreReminder = onAddChoreReminder,
                    )
                    Tab.Chores -> ChoresScreen(onEditChore = onEditChore, onAddReminder = onAddChoreReminder)
                    Tab.Reminders -> RemindersScreen(onEditEvent = onEditEvent, onEditChoreReminder = onEditChoreReminder)
                }
            }
        }
    }
}
