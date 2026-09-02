package dev.smantics.scribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.ui.components.Glyph
import dev.smantics.scribe.ui.components.GlyphName
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * The frame every settings screen sits in: a back affordance, a title, and a scrolling
 * column of cards.
 *
 * The content column is width-capped. On the Fold's 8-inch inner display an uncapped
 * settings list produces lines of text far too long to read comfortably, and a screen that
 * is worse when unfolded is a strange thing to ship on a folding phone.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 640

    Column(
        Modifier
            .fillMaxSize()
            .background(ScribeTokens.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = if (wide) MAX_READABLE_WIDTH_DP.dp else Int.MAX_VALUE.dp)
                    .padding(ScribeTokens.pageInset),
                verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapLarge),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .testTag("back")
                            .size(40.dp)
                            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
                            .background(ScribeTokens.s1)
                            .semantics { contentDescription = "Back" }
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(GlyphName.ARROW_LEFT, ScribeTokens.text, size = 20.dp, thickness = 1.8.dp)
                    }
                    Text(
                        title,
                        color = ScribeTokens.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                content()
            }
        }
    }
}

/**
 * Roughly 75 characters at this type size — the point past which line length starts
 * costing comprehension rather than adding it.
 */
private const val MAX_READABLE_WIDTH_DP = 620
