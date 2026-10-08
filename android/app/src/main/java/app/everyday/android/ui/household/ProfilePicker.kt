package app.everyday.android.ui.household

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** "Who is using this phone?" Shown after joining and when switching profile. */
@Composable
fun ProfilePicker(vm: HouseholdViewModel) {
    val members by vm.members.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(session.householdName ?: "Household", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Who's using this phone?", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Reminders on this phone belong to the person you pick. You can switch later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(members, key = { it.id }) { member ->
                    Card(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.chooseProfile(member.id) }) {
                        Text(
                            member.displayName + if (member.id == session.adminMemberId) "  (admin)" else "",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
            if (members.size <= 1) {
                Text(
                    "Not in the list? Ask the household admin to add you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { vm.leave {} }) { Text("Leave household") }
        }
    }
}
