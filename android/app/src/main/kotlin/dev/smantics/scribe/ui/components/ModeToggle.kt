package dev.smantics.scribe.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.core.clean.Mode
import dev.smantics.scribe.ui.theme.ScribeMotion
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * The Raw/Clean switch that sits beside the waveform.
 *
 * Two segments rather than a checkbox or a menu, because both states are first-class and
 * the user needs to see which one is armed without opening anything. RAW is what you said;
 * CLEAN is what you meant.
 *
 * Tapping it after text has already been inserted re-renders that same utterance in the
 * other mode and replaces what was typed — so the switch is also an undo for the cleanup,
 * which is the thing people ask for after watching a cloud dictation tool "helpfully"
 * rewrite a sentence they wanted verbatim.
 *
 * Both segments carry the accent, distinguished by fill rather than by hue, so the control
 * does not spend a second colour on a state that is not semantic.
 */
@Composable
fun ModeToggle(
    mode: Mode,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .testTag("mode-toggle")
            .clip(RoundedCornerShape(ScribeTokens.radiusPill))
            .background(ScribeTokens.s2)
            .border(1.dp, ScribeTokens.stroke2, RoundedCornerShape(ScribeTokens.radiusPill))
            .clickable(enabled = enabled, onClick = onToggle)
            .semantics {
                contentDescription = "Dictation mode"
                stateDescription = if (mode == Mode.RAW) {
                    "Raw — text appears exactly as spoken"
                } else {
                    "Clean — text is punctuated and tidied"
                }
            }
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment("RAW", selected = mode == Mode.RAW, enabled = enabled)
        Segment("CLEAN", selected = mode == Mode.CLEAN, enabled = enabled)
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, enabled: Boolean) {
    val background by animateColorAsState(
        targetValue = if (selected) ScribeTokens.accent else ScribeTokens.s2,
        animationSpec = tween(ScribeMotion.FADE),
        label = "segment-bg",
    )
    val content by animateColorAsState(
        targetValue = when {
            selected -> ScribeTokens.onAccent
            enabled -> ScribeTokens.muted
            else -> ScribeTokens.faint
        },
        animationSpec = tween(ScribeMotion.FADE),
        label = "segment-fg",
    )

    Box(
        Modifier
            .clip(RoundedCornerShape(ScribeTokens.radiusPill))
            .background(background)
            // 44dp is the smallest target that stays reliably hittable one-handed on the
            // Fold's outer screen; the label alone would be smaller than that.
            .defaultMinSize(minWidth = 58.dp, minHeight = 30.dp)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.1.sp,
        )
    }
}
