package app.everyday.android.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.data.RecipientStatus
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderView
import app.everyday.android.reminders.Window
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.formatWeekdays
import app.everyday.android.ui.formatWindow

/** Reminders shared with the current profile: new invites first, then the ones already answered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedScreen(onBack: () -> Unit) {
    val vm: SharedViewModel = viewModel(factory = SharedViewModel.Factory)
    val shared by vm.shared.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val names = members.associate { it.id to it.displayName }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared with me") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shared.isEmpty()) {
                Text("Nobody has shared a reminder with you yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shared.sortedBy { if (it.myAnswer == RecipientStatus.PENDING) 0 else 1 }.forEach { item ->
                SharedCard(item, names) { status -> vm.answer(item.reminder.id, status) }
            }
        }
    }
}

@Composable
private fun SharedCard(item: ReminderView, names: Map<String, String>, onAnswer: (RecipientStatus) -> Unit) {
    val r = item.reminder
    val ownerName = names[r.ownerMemberId]
    val schedule = if (r.mode == ReminderMode.PERSISTENT) {
        item.windows.sortedBy { it.startMinute }.joinToString(", ") { formatWindow(Window(it.startMinute, it.endMinute)) }
    } else {
        item.times.map { it.minuteOfDay }.sorted().joinToString(", ") { formatMinute(it) }
    }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(r.message, style = MaterialTheme.typography.titleMedium)
        Text(
            "From ${ownerName ?: "a removed member"} · ${formatWeekdays(r.weekdays)} · $schedule",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!r.active) Text("Paused by ${ownerName ?: "its owner"}", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                // The owner was removed: nobody can edit it; each person removes it for themselves.
                ownerName == null -> if (item.myAnswer == RecipientStatus.ACCEPTED) {
                    OutlinedButton(onClick = { onAnswer(RecipientStatus.DECLINED) }) { Text("Remove for me") }
                }
                item.myAnswer == RecipientStatus.PENDING -> {
                    Button(onClick = { onAnswer(RecipientStatus.ACCEPTED) }) { Text("Opt in") }
                    OutlinedButton(onClick = { onAnswer(RecipientStatus.DECLINED) }) { Text("No thanks") }
                }
                item.myAnswer == RecipientStatus.ACCEPTED -> {
                    Text("You're in", Modifier.weight(1f).padding(top = 10.dp))
                    OutlinedButton(onClick = { onAnswer(RecipientStatus.DECLINED) }) { Text("Opt out") }
                }
                else -> {
                    Text("You opted out", Modifier.weight(1f).padding(top = 10.dp))
                    OutlinedButton(onClick = { onAnswer(RecipientStatus.ACCEPTED) }) { Text("Opt in") }
                }
            }
        }
    }
}
