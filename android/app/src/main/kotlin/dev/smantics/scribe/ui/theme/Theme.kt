package dev.smantics.scribe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Scribe's design tokens, ported value-for-value from the desktop build's
 * `src/scribe/ui/qml/Theme.qml`.
 *
 * The point of porting the exact hex values rather than approximating them is that the two
 * products should read as one product. Someone who uses Scribe on a laptop and then
 * installs it on their phone should recognise it immediately.
 *
 * The palette's own description, from the QML: "Deep, near-black ground with one luminous
 * signal-teal accent; semantic colours kept separate from the accent." That separation is
 * the rule that makes the accent mean something — [rec], [warn] and [good] carry state,
 * [accent] does not.
 */
object ScribeTokens {
    // Ground and surfaces
    val bg = Color(0xFF05070A)
    val s0 = Color(0xFF0B0F14)   // window base / rail
    val s1 = Color(0xFF10151C)   // card
    val s1h = Color(0xFF141C25)  // card hover / pressed
    val s2 = Color(0xFF18212C)   // controls / elevated

    val stroke = Color.White.copy(alpha = 0.08f)
    val stroke2 = Color.White.copy(alpha = 0.14f)

    // Text
    val text = Color(0xFFE7EEF4)
    val muted = Color(0xFF93A1B0)
    val faint = Color(0xFF5C6875)

    // The one accent
    val accent = Color(0xFF34E4CE)
    val accent2 = Color(0xFF26B7C7)
    val accentGlow = Color(0xFF34E4CE).copy(alpha = 0.16f)

    // Semantic state, never decorative
    val rec = Color(0xFFFF6584)
    val warn = Color(0xFFFFC24D)
    val good = Color(0xFF48E39B)

    /** Ink used on top of the accent gradient — from Brand.qml. */
    val onAccent = Color(0xFF04201D)

    /**
     * The light tile the Scribe mark sits on, in the app and as the launcher icon ground.
     * The mark contains black; on this product's near-black surface it needs its own
     * ground or half the logo disappears.
     */
    val markGround = Color(0xFFF4F7F9)

    // Radii
    val radius = 16.dp
    val radiusSm = 11.dp
    val radiusChip = 9.dp
    val radiusPill = 999.dp

    // Spacing scale actually used by the desktop layouts
    val pageInset = 22.dp
    val gapLarge = 16.dp
    val gap = 12.dp
    val gapSmall = 8.dp
    val gapTiny = 4.dp

    /** Brand mark corner radius is a fixed proportion of its size. */
    const val BRAND_RADIUS_RATIO = 0.29f
}

/**
 * Animation constants, also ported so motion matches.
 *
 * Durations are milliseconds. Every one of these already earns its place on the desktop;
 * none were invented for the phone.
 */
object ScribeMotion {
    const val RAIL = 170          // nav rail collapse/expand, OutCubic
    const val CARD = 140          // card hover lift and tint
    const val PULSE_RING = 1400   // the recording orb's expanding ring, OutCubic
    const val LEVEL_BAR = 70      // waveform bar height, tracking real amplitude
    const val LEVEL_COLOR = 160   // waveform bar tint (teal -> amber on boost)
    const val DOT_PULSE = 550     // status dot breathing while recording/transcribing
    const val DONE_FLASH = 1600   // how long "Inserted" stays before the pill retires
    const val FADE = 160
}

/**
 * State-to-colour, ported from `Theme.statusColor(s)`.
 *
 * Loading and transcribing are amber rather than green because they are *not* ready — the
 * interface must not report a state it is not in. Same rule as the desktop.
 */
fun statusColor(status: DictationStatus): Color = when (status) {
    DictationStatus.RECORDING -> ScribeTokens.rec
    DictationStatus.TRANSCRIBING, DictationStatus.LOADING -> ScribeTokens.warn
    DictationStatus.ERROR -> ScribeTokens.rec
    DictationStatus.READY -> ScribeTokens.good
    DictationStatus.NEEDS_PERMISSION -> ScribeTokens.warn
}

/** The visible states, mirroring the desktop bridge's status string exactly. */
enum class DictationStatus { LOADING, READY, RECORDING, TRANSCRIBING, ERROR, NEEDS_PERMISSION }

private val ScribeColorScheme = darkColorScheme(
    primary = ScribeTokens.accent,
    onPrimary = ScribeTokens.onAccent,
    secondary = ScribeTokens.accent2,
    onSecondary = ScribeTokens.onAccent,
    background = ScribeTokens.bg,
    onBackground = ScribeTokens.text,
    surface = ScribeTokens.s1,
    onSurface = ScribeTokens.text,
    surfaceVariant = ScribeTokens.s2,
    onSurfaceVariant = ScribeTokens.muted,
    error = ScribeTokens.rec,
    onError = ScribeTokens.onAccent,
    outline = ScribeTokens.stroke2,
)

/**
 * Type scale, from the sizes the desktop QML actually uses. Sizes there are integer pixel
 * values, so they are integers here too rather than being nudged onto a Material scale
 * that would put a near-duplicate next to every one of them.
 */
private val ScribeTypography = Typography(
    headlineMedium = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    // Section headers: 11px with 1.4px tracking, as in SettingsPage.qml.
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp),
)

/** True when the panel should be laid out for one-handed reach rather than centred. */
val LocalHandedness = staticCompositionLocalOf { Handedness.RIGHT }

enum class Handedness { LEFT, RIGHT }

@Composable
fun ScribeTheme(
    handedness: Handedness = Handedness.RIGHT,
    content: @Composable () -> Unit,
) {
    // Scribe is a dark product. The desktop has no light palette and inventing one for the
    // phone would mean two designs to keep honest, not one.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    CompositionLocalProvider(LocalHandedness provides handedness) {
        MaterialTheme(
            colorScheme = ScribeColorScheme,
            typography = ScribeTypography,
            content = content,
        )
    }
}
