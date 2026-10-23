package app.everyday.android.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.everyday.android.app
import app.everyday.android.calls.CallManager
import app.everyday.android.data.MemberEntity
import app.everyday.android.household.ApiError
import app.everyday.android.ui.memberColorOf
import kotlinx.coroutines.launch

/**
 * Rings people in the picked household (Phase 12): tick who (members with a phone; Everyone ticks them
 * all), pick a ready message or type one, then Ring.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CallSheet(members: List<MemberEntity>, myId: String?, onDismiss: () -> Unit, onRang: (String) -> Unit) {
    val calls = LocalContext.current.app.calls
    val scope = rememberCoroutineScope()
    var reachable by remember { mutableStateOf<Set<String>?>(null) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            reachable = calls.targets()
        } catch (e: ApiError) {
            error = e.message
        }
    }
    val others = members.filter { it.id != myId && reachable?.contains(it.id) == true }
    val quick = (listOfNotNull(calls.lastMessage) + CallManager.QUICK_MESSAGES).distinct()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Call", style = MaterialTheme.typography.titleLarge)
            Text("Their phones ring for 30 seconds.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                reachable == null && error == null -> Text("Loading…")
                reachable != null && others.isEmpty() -> Text("Nobody else in the household has a phone with the app yet.")
                others.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (others.size > 1) {
                        val all = picked.containsAll(others.map { it.id })
                        FilterChip(selected = all, onClick = { picked = if (all) emptySet() else others.map { it.id }.toSet() }, label = { Text("Everyone") })
                    }
                    others.forEach { m ->
                        FilterChip(
                            selected = m.id in picked,
                            onClick = { picked = if (m.id in picked) picked - m.id else picked + m.id },
                            label = { Text(m.displayName) },
                            leadingIcon = { Box(Modifier.size(12.dp).background(memberColorOf(m.color, m.id), CircleShape)) },
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { q -> SuggestionChip(onClick = { message = q }, label = { Text(q) }) }
            }
            OutlinedTextField(
                message,
                { message = it.take(MAX_MESSAGE) },
                Modifier.fillMaxWidth(),
                label = { Text("Message") },
                supportingText = { Text("${message.length} / $MAX_MESSAGE") },
                singleLine = true,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            calls.ring(picked.toList(), message.trim())
                            val names = others.filter { it.id in picked }.joinToString(", ") { it.displayName }
                            onRang("Ringing $names…")
                        } catch (e: ApiError) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && picked.isNotEmpty() && message.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ring") }
        }
    }
}

private const val MAX_MESSAGE = 100
