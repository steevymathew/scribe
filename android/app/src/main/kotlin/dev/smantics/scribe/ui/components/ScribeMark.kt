package dev.smantics.scribe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.smantics.scribe.R

/**
 * The Scribe mark: the teal S with the pen and the microphone, on a dark ground with the
 * circle pressed into it.
 *
 * The artwork is transparent where there is no ink, and on this product that transparency
 * resolves to darkness — the mark belongs to the same near-black surface as everything
 * else, not to a light tile borrowed from somewhere brighter. The moulded circle is baked
 * into the asset so the app and the launcher icon are the same image rather than two
 * attempts at the same idea.
 */
@Composable
fun ScribeMark(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.scribe_mark),
            contentDescription = null,   // decorative; the app name is always beside it
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
    }
}
