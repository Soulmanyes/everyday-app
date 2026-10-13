package app.everyday.android.calendarimport

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.data.EventDraft
import app.everyday.android.data.EventEntity
import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.ui.formatBefore
import app.everyday.android.ui.formatEventWhen
import app.everyday.android.ui.formatMinute

/** Import from the phone's calendar (Phase 10): allow access, pick calendars, tick events, then their reminders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val vm: ImportViewModel = viewModel(factory = ImportViewModel.Factory)
    val context = LocalContext.current
    fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    // Back from the app's system settings.
    LifecycleResumeEffect(Unit) {
        granted = allowed()
        onPauseOrDispose {}
    }
    LaunchedEffect(granted) { if (granted) vm.loadCalendars() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!granted) {
                // Android stops showing the request after two refusals; then only the app's settings can allow it.
                val blocked = denied && !ActivityCompat.shouldShowRequestPermissionRationale(context as Activity, Manifest.permission.READ_CALENDAR)
                Access(
                    blocked = blocked,
                    onAllow = { ask.launch(Manifest.permission.READ_CALENDAR) },
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    },
                )
                return@Box
            }
            when (val step = vm.step) {
                ImportStep.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ImportStep.Calendars -> CalendarsStep(vm, step)
                is ImportStep.Events -> EventsStep(vm, step)
                is ImportStep.Reminders -> RemindersStep(vm, step)
                is ImportStep.Done -> DoneStep(step, onBack)
            }
        }
    }
}

@Composable
private fun Access(blocked: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Everyday can copy events from your phone's calendar, such as Google Calendar, into Everyday. " +
                "It reads the calendar only while you import, and only the events you tick are saved.",
        )
        if (blocked) {
            Text("Calendar access is turned off for Everyday. Allow it in the app's settings, under Permissions.")
            Button(onClick = onOpenSettings) { Text("Open app settings") }
        } else {
            Button(onClick = onAllow) { Text("Allow calendar access") }
        }
    }
}

@Composable
private fun CalendarsStep(vm: ImportViewModel, step: ImportStep.Calendars) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("Which calendars?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Events in the next $IMPORT_MONTHS months are listed next, for you to pick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (step.calendars.isEmpty()) {
                item { Text("No calendars on this phone. Is Google Calendar set up and syncing?") }
            }
            items(step.calendars, key = { it.id }) { cal ->
                CheckRow(checked = cal.id in vm.pickedCalendars, onToggle = { vm.toggleCalendar(cal.id) }) {
                    Dot(cal.color)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(cal.name)
                        if (cal.account.isNotBlank() && cal.account != cal.name) {
                            Text(cal.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        BottomBar {
            Button(onClick = vm::loadEvents, enabled = vm.pickedCalendars.isNotEmpty()) { Text("Next") }
        }
    }
}

@Composable
private fun EventsStep(vm: ImportViewModel, step: ImportStep.Events) {
    val importable = step.choices.count { it.items.isNotEmpty() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("Tick the events to import", style = MaterialTheme.typography.titleMedium)
                Text(
                    (vm.householdName?.let { "They become your own events in $it. " } ?: "They become your own events. ") +
                        "Others see them as Busy, as with events you add. Later changes in the calendar don't follow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (importable > 0) {
                    Row {
                        TextButton(onClick = { vm.selectAll(true) }) { Text("Select all") }
                        TextButton(onClick = { vm.selectAll(false) }) { Text("Select none") }
                    }
                }
            }
            if (step.choices.isEmpty()) {
                item { Text("No events in the next $IMPORT_MONTHS months in those calendars.", Modifier.padding(top = 8.dp)) }
            }
            items(step.choices, key = { it.eventId }) { choice ->
                val enabled = choice.items.isNotEmpty()
                CheckRow(checked = choice.eventId in vm.pickedEvents, enabled = enabled, onToggle = { vm.toggleEvent(choice.eventId) }) {
                    Dot(step.calendars[choice.calendarId]?.color)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(choice.title, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(describe(choice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        BottomBar {
            OutlinedButton(onClick = vm::backToCalendars) { Text("Calendars") }
            Spacer(Modifier.width(8.dp))
            val count = step.choices.filter { it.eventId in vm.pickedEvents }.sumOf { it.items.size }
            Button(onClick = vm::import, enabled = count > 0) { Text(if (count == 1) "Import 1 event" else "Import $count events") }
        }
    }
}

/** "Every Mon · 18:00 – 19:00", "Tue 14 Oct 2026 · 09:00", or "13 dates from Tue 14 Oct 2026 · 09:00". */
private fun describe(choice: ImportChoice): String {
    if (choice.alreadyImported) return "Already imported"
    val first = choice.items.first().draft.toEvent()
    val text = when {
        choice.weekly -> formatEventWhen(first)
        choice.items.size == 1 -> formatEventWhen(first)
        else -> "${choice.items.size} dates from ${formatEventWhen(first)}"
    }
    return if (choice.importedBefore > 0) "$text (${choice.importedBefore} imported before)" else text
}

private fun EventDraft.toEvent() = EventEntity(
    id = "",
    ownerMemberId = "",
    title = title,
    weekdays = if (weekly) weekdays else 0,
    onDate = if (weekly) null else date?.toString(),
    startMinute = startMinute,
    endMinute = endMinute,
    endDays = endDays,
    createdAt = 0,
    updatedAt = 0,
)

@Composable
private fun RemindersStep(vm: ImportViewModel, step: ImportStep.Reminders) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(if (step.imported == 1) "Imported 1 event" else "Imported ${step.imported} events", style = MaterialTheme.typography.titleMedium)
                Text(
                    "These events have reminders in your calendar. Tick the ones Everyday should remind you of too. " +
                        "If the calendar app still reminds you, you'd get both.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            step.offers.groupBy { it.eventId }.forEach { (eventId, offers) ->
                item(key = "t$eventId") {
                    Text(step.titles[eventId].orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(offers, key = { "o$eventId-${it.minute}-${it.relative}" }) { offer ->
                    CheckRow(checked = offer in vm.pickedOffers, enabled = !offer.tooEarly, onToggle = { vm.toggleOffer(offer) }) {
                        Column {
                            Text(offerLabel(offer))
                            offerNote(offer)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        BottomBar {
            OutlinedButton(onClick = { vm.finish(skip = true) }) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.finish(skip = false) }, enabled = vm.pickedOffers.isNotEmpty()) { Text("Add reminders") }
        }
    }
}

private fun offerLabel(offer: ReminderOffer) =
    if (offer.relative) formatBefore(offer.minute) else "At ${formatMinute(offer.minute)} on the day"

private fun offerNote(offer: ReminderOffer): String? = when {
    offer.tooEarly -> "Too early: Everyday reminds up to 7 days before"
    !offer.relative && offer.calendarMinutes > 0 -> {
        val days = (offer.calendarMinutes + MINUTES_PER_DAY - 1) / MINUTES_PER_DAY
        "The calendar reminds you ${if (days == 1) "the day" else "$days days"} before"
    }
    else -> null
}

@Composable
private fun DoneStep(step: ImportStep.Done, onBack: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (step.imported == 1) "Imported 1 event." else "Imported ${step.imported} events.", style = MaterialTheme.typography.titleMedium)
        if (step.reminders > 0) Text(if (step.reminders == 1) "Added 1 reminder." else "Added ${step.reminders} reminders.")
        Text("They're on the Events tab. To change one, tap it there.")
        Button(onClick = onBack) { Text("Done") }
    }
}

@Composable
private fun CheckRow(checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        content()
    }
}

@Composable
private fun Dot(color: Int?) {
    Box(Modifier.size(12.dp).background(color?.let { Color(it or 0xFF000000.toInt()) } ?: MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun BottomBar(content: @Composable () -> Unit) {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
