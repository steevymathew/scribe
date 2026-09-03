package dev.smantics.scribe.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.core.clean.TextDiff
import dev.smantics.scribe.dictation.DictationStage
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * The transcript, and the cleanup happening to it.
 *
 * While you speak this shows what has been heard so far. When you stop, it replays the
 * edit: first the raw text with everything Clean is about to remove struck through, then
 * the tidied text with what was added marked, then the finished line — and only then is
 * anything inserted.
 *
 * The reason for the animation is not decoration. Clean mode deletes words on purpose, and
 * a tool that deletes your words without showing you is one you have to proof-read every
 * time, which costs more than the cleanup saves. Three seconds of showing the working buys
 * the right to be trusted with the edit.
 */
@Composable
fun TranscriptReveal(
    stage: DictationStage,
    partialText: String,
    diff: List<TextDiff.Segment>,
    finalText: String,
    modifier: Modifier = Modifier,
) {
    if (stage == DictationStage.IDLE) return

    Column(
        modifier
            .testTag("transcript-reveal")
            .fillMaxWidth()
            .neuInset(RoundedCornerShape(ScribeTokens.radiusSm), depth = 0.8f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp, max = 108.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = textFor(stage, partialText, diff, finalText),
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = ScribeTokens.text,
                modifier = Modifier.testTag("transcript-text"),
            )
        }
        StageLabel(stage)
    }
}

private fun textFor(
    stage: DictationStage,
    partialText: String,
    diff: List<TextDiff.Segment>,
    finalText: String,
): AnnotatedString = when (stage) {
    DictationStage.IDLE -> AnnotatedString("")

    // Live: what has been heard, verbatim. Nothing is styled, because nothing has been
    // decided yet — showing a cleanup mid-sentence would keep changing under the reader.
    DictationStage.LISTENING -> AnnotatedString(
        partialText.ifEmpty { "Listening…" },
    )

    // The raw text with the removals struck through, so the deletions are visible before
    // they happen rather than discovered afterwards.
    DictationStage.CLEANING -> buildAnnotatedString {
        diff.forEach { segment ->
            when (segment.kind) {
                TextDiff.Kind.ADDED -> Unit   // not added yet at this stage
                TextDiff.Kind.REMOVED -> withStyle(
                    SpanStyle(
                        color = ScribeTokens.rec,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                ) { append(segment.text) }
                TextDiff.Kind.UNCHANGED -> append(segment.text)
            }
        }
    }

    // The tidied text, with what the cleaner put in picked out so it is obvious which
    // characters are Scribe's rather than the speaker's.
    DictationStage.PUNCTUATING -> buildAnnotatedString {
        diff.forEach { segment ->
            when (segment.kind) {
                TextDiff.Kind.REMOVED -> Unit
                TextDiff.Kind.ADDED -> withStyle(
                    SpanStyle(color = ScribeTokens.warn, fontWeight = FontWeight.Medium),
                ) { append(segment.text) }
                TextDiff.Kind.UNCHANGED -> append(segment.text)
            }
        }
    }

    DictationStage.FINAL -> AnnotatedString(finalText)
}

/** The small caption in the corner, naming what is happening right now. */
@Composable
private fun StageLabel(stage: DictationStage) {
    val (label, colour) = when (stage) {
        DictationStage.LISTENING -> "LISTENING" to ScribeTokens.rec
        DictationStage.CLEANING -> "CLEANING IT UP" to ScribeTokens.muted
        DictationStage.PUNCTUATING -> "ADDING PUNCTUATION" to ScribeTokens.warn
        DictationStage.FINAL -> "READY TO INSERT" to ScribeTokens.good
        DictationStage.IDLE -> return
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stage == DictationStage.LISTENING) {
            val transition = rememberInfiniteTransition(label = "listening")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "listening-alpha",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .background(colour.copy(alpha = alpha), RoundedCornerShape(3.dp))
                    .padding(end = 4.dp),
            )
        }
        Text(
            text = label,
            color = colour,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier
                .testTag("stage-label")
                .padding(start = 6.dp),
        )
    }
}
