package app.everyday.android.ui.household

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.everyday.android.household.DEFAULT_SERVER_URL

private enum class SetupStep { Welcome, Create, Join }

/** The keyboard's action key moves to the next field, which scrolls it into view above the keyboard. */
private val NEXT = KeyboardOptions(imeAction = ImeAction.Next)

/** First-run flow: create the household, join it, or skip for now. */
@Composable
fun SetupFlow(vm: HouseholdViewModel) {
    var step by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    when (step) {
        SetupStep.Welcome -> WelcomeScreen(
            onCreate = { vm.error = null; step = SetupStep.Create },
            onJoin = { vm.error = null; step = SetupStep.Join },
            onSkip = vm::skipSetup,
        )
        SetupStep.Create -> CreateScreen(vm, onBack = { step = SetupStep.Welcome })
        SetupStep.Join -> JoinScreen(vm, onBack = { step = SetupStep.Welcome })
    }
}

@Composable
private fun WelcomeScreen(onCreate: () -> Unit, onJoin: () -> Unit, onSkip: () -> Unit) {
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
            Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Join my household") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Set up a new household") }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSkip) { Text("Not now") }
        }
    }
}

@Composable
private fun JoinScreen(vm: HouseholdViewModel, onBack: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(vm.session.value.serverUrl) }
    FormScaffold("Join household", onBack) {
        Text("Ask whoever set up the household for the household code.", style = MaterialTheme.typography.bodyMedium)
        SecretField(code, { code = it }, "Household code")
        ServerField(server) { server = it }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.join(server, code) },
            enabled = !vm.busy && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.busy) "Joining…" else "Join") }
    }
}

@Composable
private fun CreateScreen(vm: HouseholdViewModel, onBack: () -> Unit) {
    var setupCode by rememberSaveable { mutableStateOf("") }
    var householdName by rememberSaveable { mutableStateOf("") }
    var householdCode by rememberSaveable { mutableStateOf("") }
    var adminName by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var pinAgain by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(vm.session.value.serverUrl) }
    val problem = when {
        householdCode.isNotEmpty() && householdCode.length < 6 -> "The household code needs at least 6 characters."
        pin.isNotEmpty() && !pin.matches(Regex("\\d{4,8}")) -> "The admin PIN must be 4 to 8 digits."
        pinAgain.isNotEmpty() && pin != pinAgain -> "The PINs don't match."
        else -> null
    }
    val ready = setupCode.isNotBlank() && householdName.isNotBlank() && householdCode.length >= 6 &&
        adminName.isNotBlank() && pin.matches(Regex("\\d{4,8}")) && pin == pinAgain
    FormScaffold("Set up household", onBack) {
        Text(
            "You'll be the household's admin: only you can add or remove members, using your admin PIN.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SecretField(setupCode, { setupCode = it }, "Setup code (from the server)")
        OutlinedTextField(householdName, { householdName = it }, label = { Text("Household name") }, singleLine = true, keyboardOptions = NEXT, modifier = Modifier.fillMaxWidth())
        SecretField(householdCode, { householdCode = it }, "Household code (others use it to join)")
        OutlinedTextField(adminName, { adminName = it }, label = { Text("Your name") }, singleLine = true, keyboardOptions = NEXT, modifier = Modifier.fillMaxWidth())
        SecretField(pin, { pin = it }, "Admin PIN (4–8 digits)", numeric = true)
        SecretField(pinAgain, { pinAgain = it }, "Admin PIN again", numeric = true)
        ServerField(server) { server = it }
        (problem ?: vm.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.create(server, setupCode, householdName, householdCode, adminName, pin) },
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
