package app.everyday.android.ui.household

import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.everyday.android.household.DEFAULT_SERVER_URL

private enum class SetupStep { Welcome, Create, Join }

/** The keyboard's action key moves to the next field, which scrolls it into view above the keyboard. */
private val NEXT = KeyboardOptions(imeAction = ImeAction.Next)

/**
 * First-run flow: create a household, join one, or skip for now. With [onCancel] it's adding another
 * household to a phone that's already in one (Phase 9), and cancelling goes back.
 */
@Composable
fun SetupFlow(vm: HouseholdViewModel, onCancel: (() -> Unit)? = null) {
    var step by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    if (onCancel != null && step == SetupStep.Welcome) BackHandler(onBack = onCancel)
    // A join link goes straight to the join screen.
    LaunchedEffect(vm.linkedJoinCode) { if (vm.linkedJoinCode != null) step = SetupStep.Join }
    when (step) {
        SetupStep.Welcome -> WelcomeScreen(
            onCreate = { vm.error = null; step = SetupStep.Create },
            onJoin = { vm.error = null; step = SetupStep.Join },
            onSkip = onCancel ?: vm::skipSetup,
            skipLabel = if (onCancel != null) "Cancel" else "Not now",
        )
        SetupStep.Create -> CreateScreen(vm, onBack = { step = SetupStep.Welcome })
        SetupStep.Join -> JoinScreen(vm, onBack = { step = SetupStep.Welcome })
    }
}

@Composable
private fun WelcomeScreen(onCreate: () -> Unit, onJoin: () -> Unit, onSkip: () -> Unit, skipLabel: String) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Everyday", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Reminders and chores for your household.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
            Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Join a household") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Set up a new household") }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSkip) { Text(skipLabel) }
        }
    }
}

@Composable
private fun JoinScreen(vm: HouseholdViewModel, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(vm.linkedJoinCode) {
        vm.linkedJoinCode?.let { code = it; vm.linkedJoinCode = null }
    }
    var name by rememberSaveable { mutableStateOf("") }
    var isAdmin by rememberSaveable { mutableStateOf(false) }
    var pin by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(vm.session.value.serverUrl) }
    FormScaffold("Join household", onBack) {
        Text(
            "Ask the household's admin for its join code. They'll approve you before you're in.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!isAdmin) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        CodeField(code, { code = it }, "Join code or invite", "e.g. HZ7K-2P9X-QM4T")
        // The admin's own new phone: nobody else could approve it.
        Row(
            Modifier.fillMaxWidth().toggleable(value = isAdmin, role = Role.Checkbox, onValueChange = { isAdmin = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isAdmin, onCheckedChange = null)
            Text("I'm the household admin (new phone)", Modifier.padding(start = 8.dp))
        }
        if (isAdmin) SecretField(pin, { pin = it }, "Admin PIN", numeric = true)
        ServerField(server) { server = it }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { if (isAdmin) vm.join(server, code, "", pin) else vm.join(server, code, name) },
            enabled = !vm.busy && code.isNotBlank() && if (isAdmin) pin.isNotBlank() else name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.busy) "Joining…" else "Join") }
    }
}

@Composable
private fun CreateScreen(vm: HouseholdViewModel, onBack: () -> Unit) {
    var invite by rememberSaveable { mutableStateOf("") }
    var householdName by rememberSaveable { mutableStateOf("") }
    var adminName by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var pinAgain by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(vm.session.value.serverUrl) }
    val problem = when {
        pin.isNotEmpty() && !pin.matches(Regex("\\d{6,8}")) -> "The admin PIN must be 6 to 8 digits."
        pinAgain.isNotEmpty() && pin != pinAgain -> "The PINs don't match."
        else -> null
    }
    val ready = invite.isNotBlank() && householdName.isNotBlank() &&
        adminName.isNotBlank() && pin.matches(Regex("\\d{6,8}")) && pin == pinAgain
    FormScaffold("Set up household", onBack) {
        Text(
            "You need an invite from the admin of another household. You'll be your household's admin: " +
                "only you can add or remove members, using your admin PIN.",
            style = MaterialTheme.typography.bodyMedium,
        )
        CodeField(invite, { invite = it }, "Invite code", "e.g. NEW-7K3P-QX9M-2B4D")
        OutlinedTextField(householdName, { householdName = it }, label = { Text("Household name") }, singleLine = true, keyboardOptions = NEXT, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(adminName, { adminName = it }, label = { Text("Your name") }, singleLine = true, keyboardOptions = NEXT, modifier = Modifier.fillMaxWidth())
        SecretField(pin, { pin = it }, "Admin PIN (6–8 digits)", numeric = true)
        SecretField(pinAgain, { pinAgain = it }, "Admin PIN again", numeric = true)
        ServerField(server) { server = it }
        var privacy by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { privacy = true }) { Text("What's stored, and who can see it") }
        if (privacy) PrivacyDialog(onDismiss = { privacy = false })
        (problem ?: vm.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.create(server, invite, householdName, adminName, pin) },
            enabled = !vm.busy && ready,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.busy) "Setting up…" else "Create household") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
fun SecretField(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A join code or invite: shown as typed (they're shared as text anyway), in capitals. */
@Composable
fun CodeField(value: String, onChange: (String) -> Unit, label: String, example: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.uppercase().take(24)) },
        label = { Text(label) },
        placeholder = { Text(example) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The server address, collapsed behind a link since almost nobody needs to change it. */
@Composable
private fun ServerField(value: String, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(value != DEFAULT_SERVER_URL) }
    if (open) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("Server address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(onClick = { open = true }) { Text("Change server address") }
    }
}

/** Shown after asking to join, until the admin approves (or rejects) this phone. */
@Composable
fun WaitingScreen(vm: HouseholdViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    // Checks every 10 seconds while the screen is open; the background job also checks.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(10_000)
        }
    }
    // A Surface gives the screen the theme's background and text colours (dark mode too).
    Surface(Modifier.fillMaxSize()) { Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Waiting for approval", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ask the admin of ${session.householdName ?: "the household"} to approve \"${session.requestedName.orEmpty()}\" " +
                "in Settings → Manage members. This screen moves on by itself once they do.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = { vm.leave {} }, enabled = !vm.busy) { Text("Cancel request") }
        if (session.memberships.size > 1) TextButton(onClick = vm::showPicker) { Text("Switch household") }
    } }
}
