package app.everyday.android.ui.household

import app.everyday.android.ui.memberColorOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import app.everyday.android.household.JoinRequestInfo
import androidx.compose.material3.OutlinedButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
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
    var coloring by remember { mutableStateOf<MemberEntity?>(null) }
    var deletingHousehold by remember { mutableStateOf(false) }

    FormScaffold("Household members", onBack) {
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (vm.requests.isNotEmpty()) {
            Text("Waiting to join", style = MaterialTheme.typography.titleMedium)
            vm.requests.forEach { request -> RequestCard(request, members, vm) }
            HorizontalDivider()
        }
        Column {
            members.forEach { member ->
                val isAdmin = member.id == session.adminMemberId
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // A 48 dp touch target around the 24 dp dot.
                    Box(
                        Modifier.size(48.dp).clickable(enabled = vm.adminPin != null, onClickLabel = "Change colour") { coloring = member },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(24.dp).background(memberColorOf(member.color, member.id), CircleShape))
                    }
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
        if (vm.adminPin != null) AdminSections(vm, session.hasJoinCode, onDeleteHousehold = { deletingHousehold = true })
    }

    vm.shownInvite?.let { code -> InviteDialog(code, session.serverUrl, onDismiss = { vm.shownInvite = null }) }
    if (deletingHousehold) {
        DeleteHouseholdDialog(session.householdName.orEmpty(), onDismiss = { deletingHousehold = false }) {
            deletingHousehold = false
            vm.deleteHousehold(onDone = onBack)
        }
    }

    if (adding) {
        NameDialog("Add member", "", onDismiss = { adding = false }) { vm.addMember(it); adding = false }
    }
    renaming?.let { member ->
        NameDialog("Rename", member.displayName, onDismiss = { renaming = null }) { vm.renameMember(member.id, it); renaming = null }
    }
    coloring?.let { member ->
        AlertDialog(
            onDismissRequest = { coloring = null },
            title = { Text("Colour for ${member.displayName}") },
            text = {
                ColorPicker(
                    selected = member.color,
                    takenBy = members.filter { it.id != member.id && it.color != null }.associate { it.color!! to it.displayName },
                    enabled = !vm.busy,
                ) { color -> vm.setMemberColor(member.id, member.displayName, color); coloring = null }
            },
            confirmButton = { TextButton(onClick = { coloring = null }) { Text("Close") } },
        )
    }
    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${member.displayName}?") },
            text = { Text("Their phone is signed out and goes back to the join screen.") },
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

/** A phone asking to join: approve as a new member, link it to an existing member, or reject it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestCard(request: JoinRequestInfo, members: List<MemberEntity>, vm: HouseholdViewModel) {
    var linking by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(request.name, style = MaterialTheme.typography.titleMedium)
        request.deviceLabel?.let {
            Text("Phone: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.approve(request.id, null) }, enabled = !vm.busy) { Text("Add as new") }
            Box {
                OutlinedButton(onClick = { linking = true }, enabled = !vm.busy && members.isNotEmpty()) { Text("This is…") }
                DropdownMenu(expanded = linking, onDismissRequest = { linking = false }) {
                    members.forEach { m ->
                        DropdownMenuItem(text = { Text(m.displayName) }, onClick = { linking = false; vm.approve(request.id, m.id) })
                    }
                }
            }
            TextButton(onClick = { vm.reject(request.id) }, enabled = !vm.busy) { Text("Reject") }
        }
        Text(
            "\"This is…\" links the phone to someone already in the list, e.g. a new phone or a person you added by hand.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Admin only (Phase 9): the join code, invites for other households, and deleting the household. */
@Composable
private fun AdminSections(vm: HouseholdViewModel, hasJoinCode: Boolean, onDeleteHousehold: () -> Unit) {
    var confirmNewCode by remember { mutableStateOf(false) }
    HorizontalDivider()
    Text("Join code", style = MaterialTheme.typography.titleMedium)
    Text(
        if (hasJoinCode) {
            "People join with the household's join code. It's shown only when it's made: if you've lost it, make a new one."
        } else {
            "Nobody new can join until you make a join code (codes chosen before version 2.0 stopped working)."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (hasJoinCode) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
    OutlinedButton(onClick = { if (hasJoinCode) confirmNewCode = true else vm.newJoinCode() }, enabled = !vm.busy) {
        Text(if (hasJoinCode) "New join code" else "Make a join code")
    }

    HorizontalDivider()
    Text("Invite another household", style = MaterialTheme.typography.titleMedium)
    Text(
        "An invite lets a friend set up their own, separate household on this server. It works once, within 7 days. " +
            "You can make ${vm.invitesLeft} more.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val dates = remember { DateTimeFormatter.ofPattern("d MMM") }
    fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dates)
    vm.invites.forEach { invite ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val now = System.currentTimeMillis()
            Text(
                when {
                    invite.usedAt != null -> "Used ${day(invite.usedAt)}"
                    invite.expiresAt < now -> "Expired ${day(invite.expiresAt)}"
                    else -> "Made ${day(invite.createdAt)}, unused until ${day(invite.expiresAt)}"
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (invite.usedAt == null && invite.expiresAt >= System.currentTimeMillis()) {
                TextButton(onClick = { vm.cancelInvite(invite.id) }, enabled = !vm.busy) { Text("Cancel") }
            }
        }
    }
    OutlinedButton(onClick = vm::createInvite, enabled = !vm.busy && vm.invitesLeft > 0) { Text("New invite") }

    HorizontalDivider()
    Text("Delete household", style = MaterialTheme.typography.titleMedium)
    Text(
        "Deletes the household and everything in it from the server, for everyone: members, chores, events and reminders.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onDeleteHousehold, enabled = !vm.busy) { Text("Delete household", color = MaterialTheme.colorScheme.error) }

    if (confirmNewCode) {
        AlertDialog(
            onDismissRequest = { confirmNewCode = false },
            title = { Text("Make a new join code?") },
            text = { Text("The current code stops working. People already in the household aren't affected.") },
            confirmButton = { TextButton(onClick = { vm.newJoinCode(); confirmNewCode = false }) { Text("New code") } },
            dismissButton = { TextButton(onClick = { confirmNewCode = false }) { Text("Cancel") } },
        )
    }
}

/** Deleting can't be undone, so the admin types the household's name to confirm. */
@Composable
private fun DeleteHouseholdDialog(householdName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $householdName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Everything in it is deleted for everyone, and every phone leaves it. This can't be undone. Type the household's name to confirm.")
                OutlinedTextField(typed, { typed = it }, label = { Text(householdName) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim().equals(householdName.trim(), ignoreCase = true)) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
