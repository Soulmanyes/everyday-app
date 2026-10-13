package app.everyday.android.ui.settings

import app.everyday.android.ui.household.ColorPicker
import app.everyday.android.app
import app.everyday.android.BuildConfig
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.data.SoundMode
import app.everyday.android.ui.household.HouseholdViewModel
import app.everyday.android.ui.household.InviteSomeoneDialog
import app.everyday.android.ui.household.PrivacyDialog

private val SNOOZE_OPTIONS = listOf(5, 10, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    household: HouseholdViewModel,
    onBack: () -> Unit,
    onManageMembers: () -> Unit,
    onWhatsNew: () -> Unit,
    onImport: () -> Unit,
) {
    // [household] is the app's shared one: switching or adding a household here changes the app's top screen.
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-read system permission states when returning from the system settings screens.
    val refresh = remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh.intValue++
        onPauseOrDispose {}
    }
    val tick = refresh.intValue
    val notificationsOn = remember(tick) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactAlarmsOn = remember(tick) { vm.engine.canScheduleExactAlarms() }
    val batteryUnrestricted = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HouseholdSection(household, onManageMembers)
            HorizontalDivider()

            val followPhone by context.app.prefs.followPhoneTheme.collectAsStateWithLifecycle()
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = followPhone,
                    role = Role.Checkbox,
                    onValueChange = { context.app.prefs.setFollowPhoneTheme(it) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = followPhone, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Follow phone theme")
                    Text(
                        "Off: always dark. On: light or dark like the rest of your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Phase 9.7: per phone, on by default.
            val showChores by context.app.prefs.showChores.collectAsStateWithLifecycle()
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = showChores,
                    role = Role.Checkbox,
                    onValueChange = { context.app.prefs.setShowChores(it) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = showChores, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Show chores on the Events tab")
                    Text(
                        "Chores, and your chore reminders, in the week and month views. They always show on the Chores and Reminders tabs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Phase 10: a one-time copy, no ongoing sync.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Import from calendar")
                    Text(
                        "Copy events from your phone's calendar, such as Google Calendar, into Everyday as your own events.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onImport) { Text("Import") }
            }
            HorizontalDivider()

            Setting("Snooze length", "How long Snooze waits before reminding you again") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SNOOZE_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = s.snoozeMinutes == minutes,
                            onClick = { vm.update { it.copy(snoozeMinutes = minutes) } },
                            label = { Text("$minutes min") },
                        )
                    }
                }
            }
            Setting("Timed reminders", "How a timed reminder alerts you (Both = sound and vibrate)") {
                SoundPicker(s.timedSound) { mode -> vm.update { it.copy(timedSound = mode) } }
            }
            Setting("Persistent reminders", "How a persistent reminder alerts you when its window starts (Both = sound and vibrate)") {
                SoundPicker(s.persistentSound) { mode -> vm.update { it.copy(persistentSound = mode) } }
            }

            HorizontalDivider()
            Text("Make sure reminders arrive", style = MaterialTheme.typography.titleMedium)
            Status(
                title = "Notifications",
                ok = notificationsOn,
                okText = "Allowed",
                badText = "Blocked: reminders can't show",
                button = "Open settings",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
            Status(
                title = "Exact alarms",
                ok = exactAlarmsOn,
                okText = "Allowed",
                badText = "Not allowed: reminders may be a few minutes late",
                button = null,
            ) {}
            Status(
                title = "Battery optimization",
                ok = batteryUnrestricted,
                okText = "Unrestricted",
                badText = "Restricted: the phone may delay or block reminders",
                button = "Allow",
            ) {
                @SuppressLint("BatteryLife") // a reminder app is an accepted use case, and this app is sideloaded
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                context.startActivity(intent)
            }
            Text(
                "On Samsung phones, also add Everyday to Settings → Battery → Background usage limits → Never sleeping apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onWhatsNew) { Text("What's new") }
            }
        }
    }
}

@Composable
private fun Setting(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun SoundPicker(selected: SoundMode, onSelect: (SoundMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SoundMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, SoundMode.entries.size),
            ) {
                Text(
                    when (mode) {
                        SoundMode.SOUND -> "Sound"
                        SoundMode.VIBRATE -> "Vibrate"
                        SoundMode.SOUND_VIBRATE -> "Both"
                        SoundMode.SILENT -> "Silent"
                    },
                )
            }
        }
    }
}

@Composable
private fun Status(title: String, ok: Boolean, okText: String, badText: String, button: String?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                if (ok) "✓ $okText" else badText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!ok && button != null) OutlinedButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun HouseholdSection(vm: HouseholdViewModel, onManageMembers: () -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Household", style = MaterialTheme.typography.titleMedium)
        if (!session.joined) {
            Text(
                "This phone isn't part of a household yet. Reminders stay on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = vm::startSetup) { Text("Join or set up a household") }
            return@Column
        }
        val me = members.firstOrNull { it.id == session.memberId }
        Text(session.householdName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        Text("You: ${me?.displayName ?: "?"}")
        Text("Your colour", style = MaterialTheme.typography.titleSmall)
        Text(
            "Shown on chores for everyone in the household. One colour per person.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ColorPicker(
            selected = me?.color,
            takenBy = members.filter { it.id != me?.id && it.color != null }.associate { it.color!! to it.displayName },
            enabled = !vm.busy,
            onPick = vm::setMyColor,
        )
        // Phase 9.5: the admin shares a one-time invite link without going through Manage members.
        if (session.memberId != null && session.memberId == session.adminMemberId) {
            Button(onClick = { vm.error = null; inviting = true }) { Text("Invite someone") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onManageMembers) {
                Text(if (session.pendingRequests > 0) "Manage members (${session.pendingRequests} waiting)" else "Manage members")
            }
            OutlinedButton(onClick = { confirmLeave = true }) { Text("Leave") }
        }
        // Phase 9: a phone can be in several households.
        if (session.memberships.size > 1) {
            OutlinedButton(onClick = vm::showPicker) { Text("Switch household (${session.memberships.size})") }
        }
        OutlinedButton(onClick = vm::startAddingHousehold) { Text("Join or set up another household") }
        TextButton(onClick = { privacy = true }) { Text("Privacy: what's stored, and who can see it") }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (privacy) PrivacyDialog(onDismiss = { privacy = false })
    if (inviting) InviteSomeoneDialog(vm, onDismiss = { inviting = false; vm.error = null })
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${session.householdName ?: "household"}?") },
            text = { Text("This phone stops syncing with it. Your reminders there are hidden until you join again. Other households on this phone stay.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leave {} }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}
