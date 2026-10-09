package app.everyday.android.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderView
import app.everyday.android.reminders.Window
import app.everyday.android.ui.calendar.CalendarViewModel
import app.everyday.android.ui.calendar.EventDetailsSheet
import app.everyday.android.ui.choreColors
import app.everyday.android.ui.formatBefore
import app.everyday.android.ui.formatEventWhen
import app.everyday.android.ui.formatLead
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.formatWeekdays
import app.everyday.android.ui.formatWindow
import app.everyday.android.ui.kindIcon
import java.time.LocalDate

/**
 * The Reminders tab: every reminder of the picked profile, on events and on chores, as a plain list.
 * Tapping an event reminder opens its event (where its reminders are edited), a chore reminder its editor.
 */
@Composable
fun RemindersScreen(onEditEvent: (String) -> Unit, onEditChoreReminder: (String) -> Unit) {
    val vm: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory)
    val events by vm.events.collectAsStateWithLifecycle()
    val choreReminders by vm.choreReminders.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now() }

    val eventRows = remember(events) { events.flatMap { it.reminders }.sortedWith(byTitle) }
    val choreRows = remember(choreReminders) { choreReminders.sortedWith(byTitle) }

    if (eventRows.isEmpty() && choreRows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                "No reminders yet.\nAdd them from an event on the Events tab, or a chore on the Chores tab.",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (eventRows.isNotEmpty()) {
                item(key = "events") { SectionHeader("Events") }
                items(eventRows, key = { it.reminder.id }) { r ->
                    ReminderRow(r, onClick = { selectedEventId = r.reminder.eventId }, onSetActive = { vm.setActive(r.reminder.id, it) })
                }
            }
            if (choreRows.isNotEmpty()) {
                item(key = "chores") { SectionHeader("Chores") }
                items(choreRows, key = { it.reminder.id }) { r ->
                    ReminderRow(r, onClick = { onEditChoreReminder(r.reminder.id) }, onSetActive = { vm.setActive(r.reminder.id, it) })
                }
            }
        }
    }

    events.firstOrNull { it.event.id == selectedEventId }?.let { view ->
        EventDetailsSheet(vm, view, today, members, onDismiss = { selectedEventId = null }, onEditEvent = onEditEvent)
    }
}

/** A to Z by title; on the same event or chore, timed before persistent. */
private val byTitle = compareBy<ReminderView>({ it.title.lowercase() }, { it.reminder.mode }, { it.reminder.createdAt })

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** What it's on, when that happens, when it reminds, and a switch to pause or resume it. Paused rows fade. */
@Composable
private fun ReminderRow(item: ReminderView, onClick: () -> Unit, onSetActive: (Boolean) -> Unit) {
    val r = item.reminder
    val colors = MaterialTheme.colorScheme
    // Same colours as on the timetable: chore green for chore reminders.
    val (bg, fg) = if (r.choreId != null) colors.choreColors() else colors.tertiaryContainer to colors.onTertiaryContainer
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.weight(1f).alpha(if (r.active) 1f else 0.5f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(24.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(kindIcon(r.choreId), contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                }
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                item.event?.let(::formatEventWhen) ?: formatWeekdays(r.weekdays),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Text(scheduleOf(item), style = MaterialTheme.typography.bodyMedium)
            if (!r.active) Text("Paused", style = MaterialTheme.typography.labelMedium, color = colors.error)
        }
        Switch(checked = r.active, onCheckedChange = onSetActive)
    }
}

/** "Timed · 08:00, 30 min before", "Persistent · All day", "Persistent · 08:00 – 09:00". */
private fun scheduleOf(item: ReminderView): String {
    val r = item.reminder
    val event = item.event
    return when (r.mode) {
        ReminderMode.TIMED -> {
            val fixed = item.times.filter { !it.relative }.map { it.minuteOfDay }.sorted().map(::formatMinute)
            val before = item.times.filter { it.relative }.map { it.minuteOfDay }.sorted().map(::formatBefore)
            "Timed · " + (fixed + before).joinToString(", ")
        }
        ReminderMode.PERSISTENT -> "Persistent · " + when {
            event != null -> r.leadMinutes?.let { formatLead(it, event.endMinute != null) } ?: "All day"
            else -> item.windows.sortedBy { it.startMinute }.joinToString(", ") { formatWindow(Window(it.startMinute, it.endMinute)) }
        }
    }
}
