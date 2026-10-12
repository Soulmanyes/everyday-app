package app.everyday.android.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.everyday.android.data.BusyEventEntity
import app.everyday.android.data.EventEntity
import app.everyday.android.data.EventView
import app.everyday.android.data.MemberEntity
import app.everyday.android.data.Persistent
import app.everyday.android.data.Sharing
import app.everyday.android.ui.EventIcon
import app.everyday.android.ui.editor.TimeDialog
import app.everyday.android.ui.formatBefore
import app.everyday.android.ui.formatEventWhen
import app.everyday.android.ui.formatLead
import app.everyday.android.ui.formatMinute
import java.time.LocalDate

/** Choices for "X before the start", in minutes. */
private val BEFORE_CHOICES = listOf(0, 5, 10, 15, 30, 60, 120, 180)

/** Details of an event tapped on the timetable, with the picked profile's own reminders on it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventSheet(
    item: EventView,
    today: LocalDate,
    /** Display names by member id. */
    memberNames: Map<String, String>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLeave: () -> Unit,
    onAddTime: (minute: Int, relative: Boolean) -> Unit,
    onRemoveTime: (minute: Int, relative: Boolean) -> Unit,
    onSetPersistent: (Persistent) -> Unit,
    onSetActive: (Boolean) -> Unit,
    onToggleDone: (Boolean) -> Unit,
) {
    val e = item.event
    val hasStart = e.startMinute != null
    var addingTime by remember { mutableStateOf(false) }
    var addingBefore by remember { mutableStateOf(false) }
    var pickingLead by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    fun name(id: String) = memberNames[id] ?: "a removed member"

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(EventIcon, contentDescription = null)
                Text(e.title, style = MaterialTheme.typography.titleLarge)
            }
            Text(formatEventWhen(e), style = MaterialTheme.typography.bodyLarge)
            e.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                when {
                    !item.isOwn -> "Shared by ${name(e.ownerMemberId)}"
                    !item.isShared -> "Only you see this"
                    else -> sharedWith(item, ::name)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Your reminders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.fixedTimes.forEach { minute ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveTime(minute, false) },
                        label = { Text(formatMinute(minute)) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.size(16.dp)) },
                    )
                }
                item.minutesBefore.forEach { minutes ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveTime(minutes, true) },
                        label = { Text(formatBefore(minutes)) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.size(16.dp)) },
                    )
                }
                if (hasStart) {
                    AssistChip(
                        onClick = { addingBefore = true },
                        label = { Text("Before start") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
                AssistChip(
                    onClick = { addingTime = true },
                    label = { Text("At a time") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            Text(
                "A notification pops up at each of these. Only you see your reminders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Keep a notification showing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val persistent = item.persistent
            // Over several days (Phase 9.6): all day on each day, or once over the whole event.
            val multiDay = item.occurs.multiDay
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = persistent == Persistent.Off, onClick = { onSetPersistent(Persistent.Off) }, label = { Text("Off") })
                if (multiDay) {
                    FilterChip(selected = persistent == Persistent.EachDay, onClick = { onSetPersistent(Persistent.EachDay) }, label = { Text("Each day, all day") })
                }
                FilterChip(
                    selected = persistent == Persistent.AllDay,
                    onClick = { onSetPersistent(Persistent.AllDay) },
                    label = { Text(if (multiDay) "Whole event" else "All day") },
                )
                if (hasStart) {
                    FilterChip(
                        selected = persistent is Persistent.Before,
                        onClick = { pickingLead = true },
                        label = {
                            Text(
                                (persistent as? Persistent.Before)?.let { formatLead(it.minutes, e.endMinute != null) } ?: "Before it starts",
                            )
                        },
                    )
                }
            }
            Text(
                if (multiDay) {
                    "It stays in the notification bar until you tap Done, which hides it for the rest of that day. " +
                        "Whole event: from the first day until the event ends."
                } else {
                    "It stays in the notification bar until you tap Done."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.isOwn) {
                    Button(onClick = onEdit) { Text("Edit") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                } else {
                    OutlinedButton(onClick = { confirmLeave = true }) { Text("Leave event") }
                }
                if (item.reminders.isNotEmpty()) {
                    OutlinedButton(onClick = { onSetActive(item.paused) }) { Text(if (item.paused) "Resume my reminders" else "Pause my reminders") }
                }
                val p = item.persistentReminder
                if (p != null && p.reminder.active && item.isOn(today)) {
                    val doneToday = p.state?.doneOn == today.toString()
                    OutlinedButton(onClick = { onToggleDone(!doneToday) }) { Text(if (doneToday) "Undo done" else "Done today") }
                }
            }
            if (item.paused) Text("Your reminders are paused", color = MaterialTheme.colorScheme.error)
        }
    }

    if (addingTime) {
        TimeDialog("Remind me at", initialMinute = e.startMinute ?: (9 * 60), onDismiss = { addingTime = false }) {
            onAddTime(it, false)
            addingTime = false
        }
    }
    if (addingBefore) {
        BeforeDialog("Remind me", { formatBefore(it) }, onDismiss = { addingBefore = false }) {
            onAddTime(it, true)
            addingBefore = false
        }
    }
    if (pickingLead) {
        BeforeDialog("Show the notification", { formatLead(it, e.endMinute != null) }, onDismiss = { pickingLead = false }) {
            onSetPersistent(Persistent.Before(it))
            pickingLead = false
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave event?") },
            text = { Text("It's removed from your timetable, with your reminders on it. ${name(e.ownerMemberId)} can add you again.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            text = { Text(if (!item.isShared) "This can't be undone." else "It's removed for everyone it's shared with. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Who the owner shared it with (Phase 9.8): "Shared with Alex · Kim sees it for scheduling", or "with everyone". */
private fun sharedWith(item: EventView, name: (String) -> String): String {
    val e = item.event
    val taking = item.takingPart.map { name(it.memberId) }
    val scheduling = item.forScheduling.map { name(it.memberId) }
    val parts = buildList {
        when {
            e.everyone == Sharing.MEMBER -> add("Shared with everyone")
            taking.isNotEmpty() -> add("Shared with " + taking.joinToString(", "))
        }
        when {
            e.everyone == Sharing.SCHEDULING -> add((if (taking.isEmpty()) "everyone" else "everyone else") + " sees it for scheduling")
            scheduling.isNotEmpty() -> add(scheduling.joinToString(", ") + (if (scheduling.size == 1) " sees" else " see") + " it for scheduling")
        }
    }
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/**
 * Another member's event shared with me for scheduling (Phase 9.8): its title and times, who shared it, and
 * Hide. There are no notes, reminders or members to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledSheet(busy: BusyEventEntity, members: List<MemberEntity>, onDismiss: () -> Unit, onHide: () -> Unit) {
    var confirmHide by remember { mutableStateOf(false) }
    val owner = members.firstOrNull { it.id == busy.ownerMemberId }?.displayName ?: "a removed member"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(EventIcon, contentDescription = null)
                Text(busy.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
            }
            Text(formatEventWhen(busy.asEvent()), style = MaterialTheme.typography.bodyLarge)
            Text(
                "$owner shared this with you for scheduling. You see its title and times, but you don't take part, so it has no reminders.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmHide = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Hide") }
        }
    }
    if (confirmHide) {
        AlertDialog(
            onDismissRequest = { confirmHide = false },
            title = { Text("Hide event?") },
            text = { Text("It's removed from your calendar. $owner can share it with you again.") },
            confirmButton = { TextButton(onClick = { confirmHide = false; onHide() }) { Text("Hide") } },
            dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("Cancel") } },
        )
    }
}

/** For formatting its days and times like an event's. */
private fun BusyEventEntity.asEvent() = EventEntity(
    id = id, ownerMemberId = ownerMemberId, title = title.orEmpty(), weekdays = weekdays, onDate = onDate,
    startMinute = startMinute, endMinute = endMinute, createdAt = 0, updatedAt = 0, endDays = endDays,
)

@Composable
private fun BeforeDialog(title: String, label: (Int) -> String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var picked by remember { mutableStateOf(15) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                BEFORE_CHOICES.forEach { minutes ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = minutes }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == minutes, onClick = { picked = minutes })
                        Text(label(minutes))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
