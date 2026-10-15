package app.everyday.android.ui.licenses

/** A license as the APK lists it; [text] is null when only a link is known (Google's SDK terms). */
data class License(val name: String, val url: String?, val text: String?)

/** A library in the APK (from AboutLibraries' `aboutlibraries.json`). */
data class Library(val name: String, val version: String?, val licenses: List<License>)

/** Notices Google compiled into Play services / Firebase, one text with every project it covers. */
data class EmbeddedNotice(val names: List<String>, val text: String)

/**
 * The licenses to show for a library. A config override (protobuf's real notice) is added next to
 * the detected one, which is an SPDX template with `<<var;...>>` placeholders; drop the template
 * when there is a filled-in license too.
 */
fun licensesToShow(licenses: List<License>): List<License> {
    val filled = licenses.filterNot { it.text?.contains("<<var;") == true }
    return filled.ifEmpty { licenses }
}
