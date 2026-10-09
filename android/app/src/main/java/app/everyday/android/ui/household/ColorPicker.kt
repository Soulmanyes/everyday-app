package app.everyday.android.ui.household

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.everyday.android.ui.MEMBER_PALETTE

/**
 * The member colour palette. [takenBy] maps colours already used to the member's name: those are
 * dimmed, show that member's initial, and can't be picked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: String?, takenBy: Map<String, String>, enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MEMBER_PALETTE.forEach { (key, color) ->
            val owner = takenBy[key]
            val isSelected = key == selected
            Box(
                Modifier.size(40.dp)
                    .alpha(if (owner != null) 0.35f else 1f)
                    .background(color, CircleShape)
                    .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable(enabled = enabled && owner == null && !isSelected) { onPick(key) }
                    .semantics { contentDescription = key + (owner?.let { ", taken by $it" } ?: "") + if (isSelected) ", selected" else "" },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isSelected -> Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                    owner != null -> Text(owner.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
