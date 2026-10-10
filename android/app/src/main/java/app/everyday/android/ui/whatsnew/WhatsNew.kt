package app.everyday.android.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown once after an update, listing the versions this phone hasn't seen yet. */
@Composable
fun WhatsNewDialog(notes: List<ReleaseNote>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(notes.singleOrNull()?.let { "What's new in ${it.versionName}" } ?: "What's new") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NotesList(notes, showVersion = notes.size > 1)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Settings → What's new: every release, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What's new") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            NotesList(RELEASE_NOTES, showVersion = true)
        }
    }
}

@Composable
private fun NotesList(notes: List<ReleaseNote>, showVersion: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        notes.forEach { note ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showVersion) Text("Version ${note.versionName}", style = MaterialTheme.typography.titleSmall)
                note.changes.forEach { change ->
                    Row {
                        Text("•", Modifier.padding(end = 8.dp))
                        Text(change)
                    }
                }
            }
        }
    }
}
