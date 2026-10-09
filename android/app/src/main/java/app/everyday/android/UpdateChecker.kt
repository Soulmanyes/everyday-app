package app.everyday.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release on the download page. */
data class AppUpdate(val versionName: String, val downloadUrl: String)

/**
 * Release builds compare their versionCode with `download/version.json` on the server (published
 * with each release) and offer the newer APK. The dev app never checks.
 */
class UpdateChecker {
    private val _update = MutableStateFlow<AppUpdate?>(null)
    val update: StateFlow<AppUpdate?> = _update.asStateFlow()

    suspend fun check() {
        if (!BuildConfig.CHECK_FOR_UPDATES) return
        _update.value = runCatching { fetch() }.getOrNull()?.takeIf { it.first > BuildConfig.VERSION_CODE }?.second
    }

    private suspend fun fetch(): Pair<Int, AppUpdate> = withContext(Dispatchers.IO) {
        val connection = URL("${BuildConfig.DEFAULT_SERVER_URL}/download/version.json").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            json.getInt("versionCode") to AppUpdate(json.getString("versionName"), json.getString("url"))
        } finally {
            connection.disconnect()
        }
    }
}
