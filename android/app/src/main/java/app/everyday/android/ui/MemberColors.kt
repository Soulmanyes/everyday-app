package app.everyday.android.ui

import androidx.compose.ui.graphics.Color

/**
 * Member dot colours, keyed like the server's MEMBER_COLORS. The server hands out a free key to each
 * member and keeps them unique per household, so every phone shows the same colour for a person.
 * No greens: chore cards are green. Each colour is chosen to show on light and dark backgrounds.
 * The 12 after "sky" came in 2.7.0. Older apps don't know them, so the server hands them out only once
 * the first 12 are taken; keep new keys at the end for the same reason.
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
    "violet" to Color(0xFF7C4DFF),
    "gold" to Color(0xFFC9A227),
    "slate" to Color(0xFF546E7A),
    "salmon" to Color(0xFFFF8A80),
    "lavender" to Color(0xFFB39DDB),
    "chocolate" to Color(0xFF5D4037),
    "magenta" to Color(0xFFD500F9),
    "tan" to Color(0xFFD7B98E),
    "mauve" to Color(0xFFAD7A99),
    "coral" to Color(0xFFFF7043),
    "periwinkle" to Color(0xFF8C9EFF),
    "blush" to Color(0xFFF8BBD0),
)

private val BY_KEY = MEMBER_PALETTE.toMap()

/** The colour for a palette key, or a stable fallback for members without one (e.g. a full palette). */
fun memberColorOf(key: String?, memberId: String): Color =
    key?.let { BY_KEY[it] } ?: MEMBER_PALETTE[Math.floorMod(memberId.hashCode(), MEMBER_PALETTE.size)].second
