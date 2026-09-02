package dev.smantics.scribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.BuildConfig
import dev.smantics.scribe.NativeLibs
import dev.smantics.scribe.core.model.ModelKind
import dev.smantics.scribe.core.model.ModelRegistry
import dev.smantics.scribe.core.model.ModelSpec
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.model.DownloadProgress
import dev.smantics.scribe.model.ModelState
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.ui.components.PrimaryButton
import dev.smantics.scribe.ui.components.ScribeCard
import dev.smantics.scribe.ui.components.SecondaryButton
import dev.smantics.scribe.ui.components.SectionLabel
import dev.smantics.scribe.ui.components.Tag
import dev.smantics.scribe.ui.components.ToggleRow
import dev.smantics.scribe.ui.theme.ScribeTokens
import kotlinx.coroutines.launch

/**
 * Models: what is installed, what it costs, and what it buys.
 *
 * The desktop build's own roadmap lists a models screen with "download/delete with
 * progress, disk usage" as unbuilt. This is that screen, with the parts that matter on a
 * phone: real byte progress rather than a spinner, resumable downloads, and an explicit
 * statement of what a bigger model is actually worth — because "181 MB" means nothing on
 * its own, and "26 % fewer errors" is the number someone can decide with.
 */
@Composable
fun ModelsScreen(engine: ScribeEngine, config: ScribeConfig, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress = remember { mutableStateMapOf<String, DownloadProgress>() }
    var refresh by remember { mutableStateOf(0) }
    val totalRamMb = remember { engine.models.totalRamMb() }

    ScreenScaffold(title = "Models", onBack = onBack) {
        ScribeCard("speech-models") {
            SectionLabel("SPEECH")
            Text(
                "Bigger models make fewer mistakes on names and technical words, and take " +
                    "longer to think. Everything runs on this phone either way.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
            ModelRegistry.speech.forEach { spec ->
                ModelRow(
                    spec = spec,
                    engine = engine,
                    inUse = spec.id == config.model,
                    asBoost = spec.id == config.heavyModel,
                    totalRamMb = totalRamMb,
                    progress = progress[spec.id],
                    refreshToken = refresh,
                    onDownload = {
                        scope.launch {
                            engine.models.download(spec).collect { progress[spec.id] = it }
                            refresh++
                        }
                    },
                    onDelete = {
                        engine.models.delete(spec)
                        progress.remove(spec.id)
                        refresh++
                    },
                    onUse = { scope.launch { engine.settings.update { it.copy(model = spec.id) } } },
                    onUseAsBoost = {
                        scope.launch { engine.settings.update { it.copy(heavyModel = spec.id) } }
                    },
                )
            }
        }

        ScribeCard("polish-models") {
            SectionLabel("CLEAN-UP POLISH")
            Text(
                "Clean mode works entirely on rules and needs nothing here. A small " +
                    "language model can additionally smooth awkward phrasing — it runs on " +
                    "this phone too, and Scribe throws away anything it produces that adds " +
                    "or changes what you said.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
            if (!NativeLibs.llmAvailable) {
                Text(
                    "The polish engine is not present in this build.",
                    color = ScribeTokens.warn,
                    fontSize = 13.sp,
                )
            } else {
                val installed = engine.models.polishModelInstalled()
                ToggleRow(
                    title = "Use polish in Clean mode",
                    body = if (installed == null) {
                        "Install a model below first."
                    } else {
                        "Adds a moment of thinking after each dictation."
                    },
                    checked = config.polishEnabled,
                    enabled = installed != null,
                ) { on ->
                    scope.launch {
                        engine.settings.update { it.copy(polishEnabled = on) }
                        engine.reloadPolisher()
                    }
                }
                ModelRegistry.polish.forEach { spec ->
                    ModelRow(
                        spec = spec,
                        engine = engine,
                        inUse = spec.id == config.polishModel && config.polishEnabled,
                        asBoost = false,
                        totalRamMb = totalRamMb,
                        progress = progress[spec.id],
                        refreshToken = refresh,
                        onDownload = {
                            scope.launch {
                                engine.models.download(spec).collect { progress[spec.id] = it }
                                refresh++
                            }
                        },
                        onDelete = {
                            engine.models.delete(spec)
                            progress.remove(spec.id)
                            refresh++
                            scope.launch { engine.reloadPolisher() }
                        },
                        onUse = {
                            scope.launch {
                                engine.settings.update { it.copy(polishModel = spec.id) }
                                engine.reloadPolisher()
                            }
                        },
                        onUseAsBoost = {},
                    )
                }
            }
        }

        ScribeCard("disk-usage") {
            SectionLabel("STORAGE")
            val bytes = engine.models.bytesOnDisk()
            refresh.let { }  // recompose when a download or delete finishes
            Text(
                "Models on this phone: ${bytes / 1_048_576} MB",
                color = ScribeTokens.text,
                fontSize = 14.sp,
            )
            Text(
                "Device memory: ${totalRamMb / 1024} GB",
                color = ScribeTokens.faint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    engine: ScribeEngine,
    inUse: Boolean,
    asBoost: Boolean,
    totalRamMb: Int,
    progress: DownloadProgress?,
    refreshToken: Int,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
    onUseAsBoost: () -> Unit,
) {
    // Re-read from disk whenever a download or delete has completed.
    val state = remember(spec.id, refreshToken, progress) { engine.models.state(spec) }
    val installed = state is ModelState.Installed
    val fits = ModelRegistry.fits(spec, totalRamMb)

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        spec.displayName,
                        color = ScribeTokens.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (inUse) Tag("IN USE", ScribeTokens.accent)
                    if (asBoost) Tag("HIGH ACCURACY", ScribeTokens.warn)
                    if (spec.bundled) Tag("INCLUDED", ScribeTokens.good)
                }
                Text(
                    "${spec.tier.label} · ${spec.sizeMb} MB · about ${spec.approxRamMb} MB in memory",
                    color = ScribeTokens.faint,
                    fontSize = 12.sp,
                )
                Text(spec.notes, color = ScribeTokens.muted, fontSize = 12.sp)
                if (!fits) {
                    // Never a silent failure later: say now that this phone cannot hold it.
                    Text(
                        "Too large for this phone's memory — it would be killed while loading.",
                        color = ScribeTokens.warn,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        when (val p = progress) {
            is DownloadProgress.Running -> DownloadBar(
                fraction = p.fraction,
                label = "${p.bytesDone / 1_048_576} of ${spec.sizeMb} MB",
            )
            is DownloadProgress.Verifying -> DownloadBar(
                fraction = 1f,
                label = "Checking the download is intact…",
            )
            is DownloadProgress.Failed -> Text(
                p.reason,
                color = ScribeTokens.rec,
                fontSize = 12.sp,
            )
            else -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall)) {
            when {
                installed && !inUse && spec.kind == ModelKind.SPEECH -> {
                    SecondaryButton("Use", "use-${spec.id}", onClick = onUse)
                    SecondaryButton("High accuracy", "boost-${spec.id}", onClick = onUseAsBoost)
                }
                installed && !inUse -> SecondaryButton("Use", "use-${spec.id}", onClick = onUse)
                !installed && progress !is DownloadProgress.Running -> PrimaryButton(
                    label = if (progress is DownloadProgress.Failed) "Resume" else "Download",
                    enabled = BuildConfig.NETWORK_ALLOWED && fits,
                    testTag = "download-${spec.id}",
                    onClick = onDownload,
                )
                else -> Unit
            }
            if (installed && !spec.bundled) {
                SecondaryButton(
                    "Delete",
                    "delete-${spec.id}",
                    tint = ScribeTokens.rec,
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * Real byte progress.
 *
 * The desktop wizard settled for an indeterminate spinner during its model download, and
 * its own roadmap records that as something it should have done properly. A 547 MB
 * download behind a spinner is indistinguishable from a hang.
 */
@Composable
private fun DownloadBar(fraction: Float, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(ScribeTokens.radiusPill))
                .background(ScribeTokens.s2),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(ScribeTokens.radiusPill))
                    .background(ScribeTokens.accent),
            )
        }
        Text(label, color = ScribeTokens.muted, fontSize = 12.sp)
    }
}
