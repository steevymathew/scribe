package dev.smantics.scribe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.ui.components.ScribeCard
import dev.smantics.scribe.ui.components.SecondaryButton
import dev.smantics.scribe.ui.components.SectionLabel
import dev.smantics.scribe.ui.components.Tag
import dev.smantics.scribe.ui.components.ToggleRow
import dev.smantics.scribe.ui.theme.ScribeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Transcript history, and the network ledger.
 *
 * Both are here for the same reason: they are the two places where "everything stays on
 * this device" stops being a claim and becomes something the user can check. History is
 * off by default and writes nothing until it is switched on; the ledger lists every
 * network request the app has ever made, which in a correct build is only model downloads
 * the user asked for.
 */
@Composable
fun HistoryScreen(engine: ScribeEngine, config: ScribeConfig, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(config.historyEnabled, refresh) {
        if (config.historyEnabled) engine.history.load() else emptyList()
    }
    val requests = remember(refresh) { engine.models.ledger.entries() }
    val stamp = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    ScreenScaffold(title = "History and network", onBack = onBack) {
        ScribeCard("history-card") {
            SectionLabel("DICTATION HISTORY")
            ToggleRow(
                title = "Keep a history of what I dictate",
                body = "Saved on this phone only, capped at 500 entries. Off by default.",
                checked = config.historyEnabled,
            ) { on ->
                scope.launch { engine.settings.update { it.copy(historyEnabled = on) } }
            }

            when {
                !config.historyEnabled -> Text(
                    "History is off, so nothing is being written. Turning it off again " +
                        "later does not delete what was already saved — use Clear for that.",
                    color = ScribeTokens.muted,
                    fontSize = 13.sp,
                )
                entries.isEmpty() -> Text(
                    "Nothing dictated yet.",
                    color = ScribeTokens.faint,
                    fontSize = 13.sp,
                )
                else -> {
                    entries.take(MAX_SHOWN).forEach { entry ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(entry.text, color = ScribeTokens.text, fontSize = 14.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stamp.format(Date(entry.at)),
                                    color = ScribeTokens.faint,
                                    fontSize = 12.sp,
                                )
                                Tag(entry.backend.uppercase(), ScribeTokens.muted)
                                Text(
                                    "%.1fs".format(entry.seconds),
                                    color = ScribeTokens.faint,
                                    fontSize = 12.sp,
                                )
                                SecondaryButton("Copy", "copy-${entry.at}") {
                                    context.copy(entry.text)
                                }
                            }
                        }
                    }
                    if (entries.size > MAX_SHOWN) {
                        Text(
                            "${entries.size - MAX_SHOWN} older entries not shown.",
                            color = ScribeTokens.faint,
                            fontSize = 12.sp,
                        )
                    }
                    SecondaryButton("Clear history", "clear-history", tint = ScribeTokens.rec) {
                        engine.history.clear()
                        refresh++
                    }
                }
            }
        }

        ScribeCard("network-card") {
            SectionLabel("NETWORK ACTIVITY")
            Text(
                "Every request Scribe has ever made. Your audio is not on this list, and " +
                    "cannot be: it is never sent anywhere.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
            if (requests.isEmpty()) {
                Text(
                    "Scribe has not made a single network request on this phone.",
                    color = ScribeTokens.good,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                requests.take(MAX_SHOWN).forEach { entry ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(entry.reason, color = ScribeTokens.text, fontSize = 13.sp)
                        Text(
                            "${entry.whenText}  ${entry.url}",
                            color = ScribeTokens.faint,
                            fontSize = 11.sp,
                        )
                    }
                }
                SecondaryButton("Clear the log", "clear-ledger") {
                    engine.models.ledger.clear()
                    refresh++
                }
            }
        }
    }
}

/**
 * The list has a realistic maximum of 500 and a realistic growth of several a day, so it
 * gets a decision about truncation rather than rendering as a wall.
 */
private const val MAX_SHOWN = 40

private fun Context.copy(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Scribe", text))
}
