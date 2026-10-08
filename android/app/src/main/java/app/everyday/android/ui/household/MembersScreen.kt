package app.everyday.android.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.everyday.android.data.MemberEntity

/** Admin-only member management. Asks for the admin PIN first; the PIN is forgotten when leaving. */
@Composable
fun MembersScreen(vm: HouseholdViewModel, onBack: () -> Unit) {
    DisposableEffect(Unit) { onDispose { vm.lockAdmin() } }
    if (vm.adminPin == null) {
        PinDialog(vm, onCancel = onBack)
    }
    val members by vm.members.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<MemberEntity?>(null) }
    var removing by remember { mutableStateOf<MemberEntity?>(null) }

    FormScaffold("Household members", onBack) {
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Column {
            members.forEach { member ->
                val isAdmin = member.id == session.adminMemberId
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(member.displayName + if (isAdmin) "  (admin)" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { renaming = member }, enabled = vm.adminPin != null) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename ${member.displayName}")
                    }
                    if (!isAdmin) {
                        IconButton(onClick = { removing = member }, enabled = vm.adminPin != null) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${member.displayName}")
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add member") },
        )
    }

    if (adding) {
        NameDialog("Add member", "", onDismiss = { adding = false }) { vm.addMember(it); adding = false }
    }
    renaming?.let { member ->
        NameDialog("Rename", member.displayName, onDismiss = { renaming = null }) { vm.renameMember(member.id, it); renaming = null }
    }
    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${member.displayName}?") },
            text = { Text("Their phone will go back to the profile picker.") },
            confirmButton = { TextButton(onClick = { vm.removeMember(member.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PinDialog(vm: HouseholdViewModel, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    // Start over after a wrong PIN instead of appending to it.
    LaunchedEffect(vm.error) { if (vm.error != null) pin = "" }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Admin PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Only the household admin can manage members.")
                SecretField(pin, { pin = it }, "PIN", numeric = true)
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.unlockAdmin(pin) {} }, enabled = pin.isNotEmpty() && !vm.busy) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
