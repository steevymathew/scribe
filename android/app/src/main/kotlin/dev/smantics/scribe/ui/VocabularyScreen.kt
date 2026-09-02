package dev.smantics.scribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smantics.scribe.core.clean.CleanContext
import android.content.Context
import dev.smantics.scribe.core.clean.CleanPipeline
import dev.smantics.scribe.core.clean.ToneProfile
import dev.smantics.scribe.dictation.ScribeEngine
import dev.smantics.scribe.settings.ScribeConfig
import dev.smantics.scribe.ui.components.PrimaryButton
import dev.smantics.scribe.ui.components.ScribeCard
import dev.smantics.scribe.ui.components.SecondaryButton
import dev.smantics.scribe.ui.components.SectionLabel
import dev.smantics.scribe.ui.components.ToggleRow
import dev.smantics.scribe.ui.theme.ScribeTokens
import kotlinx.coroutines.launch

/**
 * The custom dictionary and voice shortcuts.
 *
 * Both are Wispr Flow features — its personal dictionary and its Snippets — done without an
 * account to sync them to. The dictionary earns its place twice: it corrects the spelling
 * afterwards, *and* it is handed to Whisper as an initial prompt so the name is more likely
 * to be heard right in the first place. Measured errors on this project's own test corpus
 * are almost entirely proper nouns, so this is the highest-leverage screen in the app.
 */
@Composable
fun VocabularyScreen(engine: ScribeEngine, config: ScribeConfig, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    ScreenScaffold(title = "Words and shortcuts", onBack = onBack) {
        ScribeCard("dictionary-card") {
            SectionLabel("DICTIONARY")
            Text(
                "Teach Scribe the names and terms you use. It applies the spelling you " +
                    "want, and nudges recognition towards hearing the word correctly.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
            PairEditor(
                entries = config.dictionary,
                leftLabel = "You say",
                rightLabel = "Scribe types",
                leftHint = "jira",
                rightHint = "Jira",
                testPrefix = "dictionary",
                onAdd = { spoken, written ->
                    scope.launch {
                        engine.settings.update { it.copy(dictionary = it.dictionary + (spoken to written)) }
                    }
                },
                onRemove = { key ->
                    scope.launch {
                        engine.settings.update { it.copy(dictionary = it.dictionary - key) }
                    }
                },
            )
        }

        ScribeCard("snippets-card") {
            SectionLabel("VOICE SHORTCUTS")
            Text(
                "Say the phrase, get the text. Useful for anything you type often and " +
                    "would rather not spell out loud.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
            PairEditor(
                entries = config.snippets,
                leftLabel = "You say",
                rightLabel = "Scribe types",
                leftHint = "my address",
                rightHint = "12 Rue Example, Paris",
                testPrefix = "snippets",
                onAdd = { trigger, expansion ->
                    scope.launch {
                        engine.settings.update { it.copy(snippets = it.snippets + (trigger to expansion)) }
                    }
                },
                onRemove = { key ->
                    scope.launch {
                        engine.settings.update { it.copy(snippets = it.snippets - key) }
                    }
                },
            )
        }

        ToneCard(engine, config)

        /**
         * A live preview, because these two features are otherwise invisible until the
         * next time you happen to dictate the right word — and if a rule is wrong, that is
         * a bad moment to find out.
         */
        ScribeCard("preview-card") {
            SectionLabel("TRY IT")
            var sample by remember {
                mutableStateOf("um so i think we should file the jira ticket comma actually two tickets")
            }
            Field(
                value = sample,
                hint = "Type something as if you had said it",
                testTag = "preview-input",
                onChange = { sample = it },
            )
            Text("Clean mode would type:", color = ScribeTokens.faint, fontSize = 12.sp)
            Text(
                CleanPipeline.run(
                    sample,
                    CleanContext(
                        dictionary = config.dictionary,
                        snippets = config.snippets,
                        options = config.cleanContext(null).options,
                    ),
                ).ifEmpty { "(nothing)" },
                color = ScribeTokens.accent,
                fontSize = 14.sp,
                modifier = Modifier.testTag("preview-output"),
            )
        }
    }
}

@Composable
private fun PairEditor(
    entries: Map<String, String>,
    leftLabel: String,
    rightLabel: String,
    leftHint: String,
    rightHint: String,
    testPrefix: String,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var left by remember { mutableStateOf("") }
    var right by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall)) {
        if (entries.isEmpty()) {
            Text("Nothing added yet.", color = ScribeTokens.faint, fontSize = 13.sp)
        } else {
            entries.entries.sortedBy { it.key }.forEach { (key, value) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("“$key”", color = ScribeTokens.muted, fontSize = 13.sp)
                        Text(value, color = ScribeTokens.text, fontSize = 14.sp)
                    }
                    SecondaryButton(
                        "Remove",
                        "$testPrefix-remove-$key",
                        tint = ScribeTokens.rec,
                    ) { onRemove(key) }
                }
            }
        }

        Text(leftLabel, color = ScribeTokens.faint, fontSize = 12.sp)
        Field(left, leftHint, "$testPrefix-left") { left = it }
        Text(rightLabel, color = ScribeTokens.faint, fontSize = 12.sp)
        Field(right, rightHint, "$testPrefix-right") { right = it }
        PrimaryButton(
            label = "Add",
            enabled = left.isNotBlank() && right.isNotBlank(),
            testTag = "$testPrefix-add",
        ) {
            onAdd(left.trim(), right.trim())
            left = ""
            right = ""
        }
    }
}

@Composable
private fun Field(value: String, hint: String, testTag: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .testTag(testTag)
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScribeTokens.radiusSm))
            .background(ScribeTokens.s2)
            .semantics { contentDescription = hint }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // The hint sits behind the field rather than above it, so an empty input is one
        // row tall and the layout does not jump the moment someone starts typing.
        if (value.isEmpty()) {
            Text(hint, color = ScribeTokens.faint, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = ScribeTokens.text, fontSize = 14.sp),
            cursorBrush = SolidColor(ScribeTokens.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Per-app tone.
 *
 * Wispr Flow adapts its output to the app you are writing in, and does it by sending your
 * screen to its servers. Scribe uses the one piece of context Android hands an input
 * method for free — the package name of the field being typed into — and nothing else. No
 * screen contents, no window titles, nothing that leaves the phone.
 *
 * The list is only apps you have actually dictated into. Reading the installed-app list is
 * a restricted permission and Scribe does not ask for it, which also means this screen is
 * empty until Scribe has been used, and that is the correct trade.
 *
 * Tone changes typographic finish only — whether a fragment gets a full stop — never word
 * choice. A dictation tool that rewrites your register has stopped being a dictation tool.
 */
@Composable
private fun ToneCard(engine: ScribeEngine, config: ScribeConfig) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ScribeCard("tone-card") {
        SectionLabel("TONE PER APP")
        if (config.recentApps.isEmpty()) {
            Text(
                "Once you have dictated into a few apps they will appear here, and you can " +
                    "give each one its own finish — a full stop in email, none in chat.",
                color = ScribeTokens.muted,
                fontSize = 13.sp,
            )
            return@ScribeCard
        }
        Text(
            "Only apps you have dictated into. Scribe reads the app's name and nothing " +
                "else about what is on screen.",
            color = ScribeTokens.muted,
            fontSize = 13.sp,
        )
        config.recentApps.forEach { pkg ->
            val current = config.tonePerApp[pkg] ?: ToneProfile.NEUTRAL
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(context.appLabel(pkg), color = ScribeTokens.text, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(ScribeTokens.gapSmall)) {
                    ToneProfile.entries.forEach { profile ->
                        SecondaryButton(
                            label = profile.name.lowercase().replaceFirstChar { it.uppercase() },
                            testTag = "tone-$pkg-${profile.name}",
                            tint = if (profile == current) ScribeTokens.accent else ScribeTokens.muted,
                        ) {
                            scope.launch {
                                engine.settings.update {
                                    it.copy(tonePerApp = it.tonePerApp + (pkg to profile))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The app's display name if Android will tell us, and the package name if it will not.
 *
 * Package visibility filtering means an input method is not automatically allowed to look
 * up every app. Rather than declare a broad `<queries>` element to work around that, the
 * package name is shown — less pretty, and it asks for nothing.
 */
private fun Context.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
