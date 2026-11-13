package app.droidnux.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.painter.Painter
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
    image: Painter? = null,
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
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (text != null) Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (compact && action != null) Box(Modifier.padding(top = 8.dp)) { action() }
        }
        if (!compact && action != null) action()
        trailing()
    }
}

/**
 * Fades the content out at the edges it can still scroll past, instead of cutting it there: put it before the
 * scroll modifier (or on the lazy list) with the same [state]. [vertical] false for rows that scroll sideways.
 */
fun Modifier.fadingEdges(state: ScrollableState, vertical: Boolean = true, length: Dp = if (vertical) 56.dp else 32.dp): Modifier = composed {
    val start by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, tween(150), label = "fadeStart")
    val end by animateFloatAsState(if (state.canScrollForward) 1f else 0f, tween(150), label = "fadeEnd")
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        // The gradients are made again only when the size or an edge's fade changes, not at every scrolled frame.
        .drawWithCache {
            val edge = length.toPx()
            val full = if (vertical) size.height else size.width
            val band = if (vertical) Size(size.width, edge) else Size(edge, size.height)
            val far = if (vertical) Offset(0f, full - edge) else Offset(full - edge, 0f)
            val opaque = Color.Black
            fun gradient(from: Float, to: Float, startAlpha: Float, endAlpha: Float) =
                listOf(opaque.copy(alpha = startAlpha), opaque.copy(alpha = endAlpha)).let {
                    if (vertical) Brush.verticalGradient(it, from, to) else Brush.horizontalGradient(it, from, to)
                }
            val startFade = gradient(0f, edge, 1f - start, 1f)
            val endFade = gradient(full - edge, full, 1f, 1f - end)
            onDrawWithContent {
                drawContent()
                drawRect(startFade, size = band, blendMode = BlendMode.DstIn)
                drawRect(endFade, topLeft = far, size = band, blendMode = BlendMode.DstIn)
            }
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
