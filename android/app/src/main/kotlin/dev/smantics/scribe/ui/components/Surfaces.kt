package dev.smantics.scribe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * The shared surfaces, so every screen reuses one card, one section header and one button
 * rather than each growing its own near-duplicate.
 *
 * All of them come from the desktop build's QML components (`Card.qml`, `Chip.qml`,
 * `SettingRow.qml`, `SettingsGroup.qml`) at the same values.
 */

/** `Card.qml`: s1 fill, hairline stroke, 16 dp radius. */
@Composable
fun ScribeCard(
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .testTag(testTag)
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScribeTokens.radius))
            .background(ScribeTokens.s1)
            .border(1.dp, ScribeTokens.stroke, RoundedCornerShape(ScribeTokens.radius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
        content = content,
    )
}

/** The all-caps 11 px section header with 1.4 px tracking, from `SettingsPage.qml`. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = ScribeTokens.faint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
    )
}

@Composable
fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = if (enabled) ScribeTokens.text else ScribeTokens.faint,
                fontSize = 14.sp,
            )
            Text(body, color = ScribeTokens.faint, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(
                checkedThumbColor = ScribeTokens.onAccent,
                checkedTrackColor = ScribeTokens.accent,
                uncheckedThumbColor = ScribeTokens.muted,
                uncheckedTrackColor = ScribeTokens.s2,
                uncheckedBorderColor = ScribeTokens.stroke2,
            ),
        )
    }
}

@Composable
fun PrimaryButton(
    label: String,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(if (enabled) ScribeTokens.accent else ScribeTokens.s2)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = if (enabled) ScribeTokens.onAccent else ScribeTokens.faint,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun SecondaryButton(
    label: String,
    testTag: String,
    modifier: Modifier = Modifier,
    tint: Color = ScribeTokens.text,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(ScribeTokens.s2)
            .border(1.dp, ScribeTokens.stroke2, RoundedCornerShape(ScribeTokens.radiusSm))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (enabled) tint else ScribeTokens.faint, fontSize = 13.sp)
    }
}

@Composable
fun GhostButton(label: String, testTag: String = "ghost-$label", onClick: () -> Unit) {
    Box(
        Modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = ScribeTokens.muted, fontSize = 14.sp)
    }
}

/** `Chip.qml`: a pill badge carrying one word of state. */
@Composable
fun Tag(label: String, colour: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(ScribeTokens.radiusPill))
            .background(colour.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = colour,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
    }
}

/** A row that opens another screen. */
@Composable
fun NavRow(title: String, detail: String, testTag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .testTag(testTag)
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = ScribeTokens.text, fontSize = 14.sp)
            Text(detail, color = ScribeTokens.faint, fontSize = 12.sp)
        }
        Glyph(GlyphName.ARROW_RIGHT, ScribeTokens.faint, size = 18.dp, thickness = 1.6.dp)
    }
}

/**
 * The panel's silhouette: a rectangle whose top corners curve **the other way**.
 *
 * An ordinary rounded corner rounds the black *off*, which makes the keyboard a card lying
 * on the app. Inverting it does the opposite — the ground stays full height at the far left
 * and right and sweeps down into the flat top edge, so what you see is the screen's own
 * edge coming inward and the keys sitting in the cove it makes. The keyboard stops being
 * something on top of the display and becomes part of it.
 *
 * Drawn rather than borrowed because no standard shape does this: `RoundedCornerShape`
 * removes area at the corners, and this adds it.
 */
class CoveTopShape(private val radius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }.coerceAtMost(size.width / 2f)
        val path = Path().apply {
            // Full height at the very left, sweeping down to the flat top edge.
            moveTo(0f, 0f)
            arcTo(Rect(0f, -r, 2 * r, r), 180f, -90f, false)
            lineTo(size.width - r, r)
            // And back up at the right.
            arcTo(Rect(size.width - 2 * r, -r, size.width, r), 90f, -90f, false)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}
