package app.everyday.android.ui.household

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.everyday.android.household.DEFAULT_SERVER_URL
import app.everyday.android.household.Membership

/** Where people download the app: the server this phone uses. */
private fun downloadLink(serverUrl: String) = serverUrl.trimEnd('/') + "/download/"

/** A page that opens the app with the code; the code is after the #, which browsers never send to the server. */
private fun joinLink(serverUrl: String, code: String) = serverUrl.trimEnd('/') + "/download/join.html#" + code

/**
 * Shown when the app starts on a phone in several households (Phase 9): pick which one to open, and switch
 * each household's reminders and notifications on or off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdPicker(vm: HouseholdViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Your households") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Tap a household to open it. The switch turns its reminders and notifications on or off on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            session.memberships.sortedBy { it.householdName.orEmpty().lowercase() }.forEach { m ->
                HouseholdRow(m, onOpen = { vm.pick(m.token) }, onNotifications = { vm.setNotifications(m.token, it) })
            }
            OutlinedButton(onClick = vm::startAddingHousehold, modifier = Modifier.fillMaxWidth()) {
                Text("Join or set up another household")
            }
        }
    }
}

@Composable
private fun HouseholdRow(m: Membership, onOpen: () -> Unit, onNotifications: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.householdName ?: "Household", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        m.memberId == null -> "Waiting for the admin to approve you"
                        m.isAdmin -> "You're the admin"
                        else -> "Member"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = m.notificationsOn, onCheckedChange = onNotifications)
        }
    }
}

/** A join code, shown once (the server keeps only a hash), with a share button. */
@Composable
fun JoinCodeDialog(code: String, householdName: String, serverUrl: String, onDismiss: () -> Unit) {
    val message = "Join $householdName in Everyday: ${joinLink(serverUrl, code)}\n" +
        "New to the app? Install it from ${downloadLink(serverUrl)}, then tap \"Join a household\" and enter the join code: $code"
    CodeDialog(
        title = "Join code",
        code = code,
        explanation = "Share this with people you want in $householdName. You'll still approve each of them. " +
            "This is the only time the code is shown: if you lose it, make a new one in Manage members.",
        shareText = message,
        onDismiss = onDismiss,
    )
}

/** Opens the share sheet with a one-time invite link into [householdName] (Phase 9.5). */
fun shareMemberInvite(context: Context, code: String, householdName: String, serverUrl: String) {
    val message = "Join $householdName in Everyday: ${joinLink(serverUrl, code)}\n" +
        "New to the app? Install it from ${downloadLink(serverUrl)}, then tap \"Join a household\" and enter the code: $code\n" +
        "It works once, within 7 days."
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
    context.startActivity(Intent.createChooser(send, "Invite to $householdName"))
}

/**
 * Settings → Invite someone (Phase 9.5, admin): asks for the admin PIN, makes a one-time invite link and goes
 * straight to the share sheet. The person still waits for the admin to approve them.
 */
@Composable
fun InviteSomeoneDialog(vm: HouseholdViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session by vm.session.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    // Start over after a wrong PIN instead of appending to it.
    LaunchedEffect(vm.error) { if (vm.error != null) pin = "" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite someone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Makes a link to join ${session.householdName ?: "the household"}. It works once, within 7 days, " +
                        "and you still approve whoever uses it. Open links are listed in Manage members.",
                )
                SecretField(pin, { pin = it }, "Admin PIN", numeric = true)
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.inviteSomeone(pin) { code ->
                        shareMemberInvite(context, code, session.householdName ?: "my household", session.serverUrl)
                        onDismiss()
                    }
                },
                enabled = pin.isNotEmpty() && !vm.busy,
            ) { Text("Share link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An invite to create another household, shown once, with a share button. */
@Composable
fun InviteDialog(code: String, serverUrl: String, onDismiss: () -> Unit) {
    val message = "You're invited to set up your own household in Everyday.\n" +
        "1. Install the app: ${downloadLink(serverUrl)}\n" +
        "2. Tap \"Set up a new household\" and enter the invite code: $code\n" +
        "It works once, within 7 days."
    CodeDialog(
        title = "Invite",
        code = code,
        explanation = "Whoever uses this sets up their own, separate household: they can't see yours. " +
            "It works once, within 7 days. This is the only time it's shown.",
        shareText = message,
        onDismiss = onDismiss,
    )
}

@Composable
private fun CodeDialog(title: String, code: String, explanation: String, shareText: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer {
                    Text(code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                }
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                context.startActivity(Intent.createChooser(send, title))
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** What the server keeps, and who can see it (Phase 9: other people's households live on this server too). */
@Composable
fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PRIVACY_NOTE.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

private val PRIVACY_NOTE = listOf(
    "Everyday keeps each household's data on one private server, run by the person who made the app " +
        "(${DEFAULT_SERVER_URL.removePrefix("https://").removePrefix("http://")}), so it can sync between your phones.",
    "Stored: household and member names, chores, events, reminders and their times, and which phones are in " +
        "the household. Join codes, invites, admin PINs and phone keys are stored only in scrambled (hashed) form.",
    "Other households on the server can't see anything of yours, and you can't see theirs.",
    "Instant updates go through Google's Firebase, but carry no household data: only a \"sync now\" signal.",
    "The server owner can see, without names, how many households and members there are and when they were last " +
        "used. Like anyone running a server, they could technically read the database.",
    "Your household's admin can delete it and everything in it, for everyone, from Settings → Manage members. " +
        "Households that no phone has used for a year are deleted automatically.",
)
