package app.everyday.android.ui

import androidx.compose.ui.graphics.Color

/**
 * Member dot colours, keyed like the server's MEMBER_COLORS. The server hands out a free key to each
 * member and keeps them unique per household, so every phone shows the same colour for a person.
 * No greens: chore cards are green. Each colour is chosen to show on light and dark backgrounds.
 */
val MEMBER_PALETTE: List<Pair<String, Color>> = listOf(
    "blue" to Color(0xFF1E88E5),
    "red" to Color(0xFFE53935),
    "purple" to Color(0xFF8E24AA),
    "orange" to Color(0xFFFB8C00),
    "yellow" to Color(0xFFFDD835),
    "pink" to Color(0xFFEC407A),
    "cyan" to Color(0xFF00ACC1),
    "brown" to Color(0xFF8D6E63),
    "indigo" to Color(0xFF3F51B5),
    "grey" to Color(0xFF90A4AE),
    "maroon" to Color(0xFF880E4F),
    "sky" to Color(0xFF81D4FA),
)

private val BY_KEY = MEMBER_PALETTE.toMap()

/** The colour for a palette key, or a stable fallback for members without one (e.g. a full palette). */
fun memberColorOf(key: String?, memberId: String): Color =
    key?.let { BY_KEY[it] } ?: MEMBER_PALETTE[Math.floorMod(memberId.hashCode(), MEMBER_PALETTE.size)].second
