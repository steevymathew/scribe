package dev.smantics.scribe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.R
import dev.smantics.scribe.ui.theme.ScribeTokens

/**
 * The Scribe mark.
 *
 * It is drawn on a light tile rather than directly on the app's surface. The mark is teal,
 * white and black, and Scribe's ground is near-black (#05070A) — placed straight onto it
 * the black pen barrel would vanish and the logo would read as a broken outline. The tile
 * is the same light neutral the launcher icon uses, so the mark looks identical in the app
 * and on the home screen.
 *
 * The corner radius keeps the desktop build's `Brand.qml` proportion (0.29 × size), so the
 * silhouette matches the Qt app even though the artwork inside it has changed.
 */
@Composable
fun ScribeMark(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * ScribeTokens.BRAND_RADIUS_RATIO))
            .background(ScribeTokens.markGround),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.scribe_mark),
            contentDescription = null,   // decorative; the app name is always beside it
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .padding(size * 0.16f),
        )
    }
}
