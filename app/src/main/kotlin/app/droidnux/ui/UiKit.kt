package app.droidnux.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shapes and sizes of MoonVibe (docs/DESIGN.md there): rows 12 dp, cards 20 dp, dialogs 28 dp.
val RowShape = RoundedCornerShape(12.dp)
val CardShape = RoundedCornerShape(20.dp)

/** A phone held upright: tighter margins, one column, buttons under their text. */
@Composable
fun isCompact(): Boolean = LocalConfiguration.current.screenWidthDp < 600

/** Section title: upper case, accent color, 12 sp. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
    )
}

/**
 * A list row: icon, title and text, then whatever [trailing] adds (a value, a button).
 * [action] goes at the end too, or under the text on a phone, where it would squeeze it.
 */
@Composable
fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val compact = isCompact()
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "row",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RowShape)
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (text != null) Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (compact && action != null) Box(Modifier.padding(top = 8.dp)) { action() }
        }
        if (!compact && action != null) action()
        trailing()
    }
}

/** Page dots of the first-run steps: the current one is a longer bar. */
@Composable
fun StepDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 24.dp else 8.dp, label = "dot")
            val color by animateColorAsState(
                if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "dotColor",
            )
            Box(Modifier.size(width = width, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}
