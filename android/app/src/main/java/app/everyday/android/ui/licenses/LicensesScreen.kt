package app.everyday.android.ui.licenses

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.everyday.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private class Notices(val libraries: List<Library>, val embedded: List<EmbeddedNotice>)

/** What the detail page shows: a title and the license texts (or links) under it. */
private class Detail(val title: String, val licenses: List<License>)

/** Settings → Open source licenses: every library in the APK and the notices bundled inside them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notices by produceState<Notices?>(null) { value = withContext(Dispatchers.IO) { loadNotices(context) } }
    var detail by remember { mutableStateOf<Detail?>(null) }
    BackHandler(enabled = detail != null) { detail = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "Open source licenses") },
                navigationIcon = {
                    IconButton(onClick = { if (detail != null) detail = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val shown = detail
        if (shown != null) {
            DetailPage(shown, Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "Everyday's own code is released under CC0 1.0 (public domain). It is built with " +
                            "these open source libraries, used under their licenses.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val data = notices ?: return@LazyColumn
                items(data.libraries) { library ->
                    val licenses = licensesToShow(library.licenses)
                    LibraryRow(
                        title = library.name + (library.version?.let { " $it" } ?: ""),
                        subtitle = licenses.joinToString { it.name },
                        onClick = { detail = Detail(library.name, licenses) },
                    )
                }
                if (data.embedded.isNotEmpty()) {
                    item {
                        Text(
                            "Included inside Google Play services and Firebase",
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(data.embedded) { notice ->
                        val title = notice.names.joinToString()
                        LibraryRow(
                            title = title,
                            subtitle = null,
                            onClick = { detail = Detail(title, listOf(License(title, null, notice.text))) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

@Composable
private fun DetailPage(detail: Detail, modifier: Modifier) {
    SelectionContainer(modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            detail.licenses.forEach { license ->
                if (detail.licenses.size > 1 || license.name != detail.title) {
                    Text(license.name, style = MaterialTheme.typography.titleSmall)
                }
                // Google's SDK terms come as a link only.
                if (license.text == null && license.url != null) Text(license.url)
                license.text?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun loadNotices(context: Context): Notices {
    fun raw(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    val about = JSONObject(raw(R.raw.aboutlibraries))
    val licenseInfo = about.getJSONObject("licenses")
    val libraries = about.getJSONArray("libraries").objects().map { lib ->
        val licenses = lib.optJSONArray("licenses")?.strings().orEmpty().mapNotNull { key ->
            licenseInfo.optJSONObject(key)?.let { info ->
                License(
                    name = info.optString("name", key),
                    url = info.optString("url").ifEmpty { null },
                    text = info.optString("content").ifEmpty { null },
                )
            }
        }
        Library(lib.optString("name", lib.getString("uniqueId")), lib.optString("artifactVersion").ifEmpty { null }, licenses)
    }.sortedBy { it.name.lowercase() }

    val embedded = JSONArray(raw(R.raw.embedded_licenses)).objects().map {
        EmbeddedNotice(it.getJSONArray("names").strings(), it.getString("text"))
    }
    return Notices(libraries, embedded)
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun JSONArray.strings() = (0 until length()).map { getString(it) }
