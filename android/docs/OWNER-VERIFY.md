# OWNER-VERIFY

Everything below needs a real phone and could not be checked on the build machine. None of
it has been tested; none of it is claimed to work. It is written so that finding out takes
about twenty minutes rather than an afternoon.

The build host is aarch64 Linux, which has no Android emulator, and the pixel-rendering
runtimes are x86-64 only (the attempt and its failure are documented at the top of the
`screenshotTest` task). So: the engine, the pipeline and the flows are tested; the
**hardware, the timings and the appearance are not**.

## Install

```bash
adb install -r app-standard-release.apk       # or app-airgap-release.apk
```

Both are signed with the key at `~/.keys/scribe-release.jks`. Sideloading will warn about
an unknown developer — that is expected for a self-signed build.

If a previous Scribe is installed with a different key, uninstall it first; Android refuses
a signature change.

## 1 · It starts and finishes setup

- [ ] The app opens without crashing on first launch.
- [ ] Onboarding runs: welcome → microphone → keyboard → Raw/Clean → done.
- [ ] The microphone step's level bars move when you speak. **If they do not, stop here** —
      `MicRecorder` uses `VOICE_RECOGNITION` at 16 kHz mono, and if Samsung's audio HAL
      refuses that combination nothing downstream can work. Report what
      `adb logcat -s MicRecorder` says.
- [ ] "Open keyboard settings" lands on the right system screen, and after enabling Scribe
      and returning, the checklist shows it as done **without needing a restart**.

## 2 · The first dictation — the one that matters

In any chat app or notes app:

- [ ] Scribe appears in the keyboard switcher and can be selected.
- [ ] The panel is a sensible height — not a sliver, not half the screen.
- [ ] Holding the microphone button starts recording; the waveform tracks your voice.
- [ ] Releasing transcribes and the text appears at the cursor.
- [ ] **Time it.** How long between releasing and the text appearing, for a ten-second
      sentence? This is the single number nothing on the build machine could establish.
      Measured on a workstation CPU, `base.en` decodes at 0.05× real time; a phone will be
      several times slower and the honest answer is unknown until you do this.
- [ ] The "Scribe is listening" notification appears while recording and goes away after.

## 3 · Raw and Clean

- [ ] Dictate `um so I think we should ship it on Friday comma actually Thursday` in Clean.
      Expect roughly: `So I think we should ship it on Thursday.`
- [ ] Tap the toggle to RAW. The inserted text should be replaced by the verbatim version.
- [ ] Tap back to CLEAN. It should return.
- [ ] Type something yourself, then tap the toggle. **Nothing should change** — Scribe
      refuses to edit text it is no longer sure it wrote.

## 4 · The fold — the case this port was designed around

- [ ] Start dictating on the cover screen, then open the phone mid-sentence. The recording
      should continue and the transcript should still arrive. State lives in
      `DictationService`/`ScribeEngine` precisely so this survives; whether it does in
      practice is untested.
- [ ] Fold and unfold with the keyboard open but idle — the panel should re-lay out, not
      disappear.
- [ ] Check the panel on the inner display. It is laid out for a much wider, shorter area
      than a normal phone; this is the layout most likely to be wrong.
- [ ] Flex mode (half-folded) — is the panel usable, or does the hinge cut it?
- [ ] Left-handed setting: does the mode toggle move to the reachable side?

## 5 · Offline

- [ ] Aeroplane mode, then dictate. Everything should work: the model is inside the APK.
- [ ] For the `airgap` build, confirm the app has no internet permission at all in
      Settings → Apps → Scribe.

## 6 · Failure states

- [ ] Deny the microphone, then open the keyboard. It should explain and offer "Open
      Scribe", and that button should actually get you to the permission dialog.
- [ ] Start a model download and turn off Wi-Fi mid-way. It should say it was interrupted
      and resume rather than restart when you tap again.
- [ ] Start a call (or anything that grabs the microphone), then try to dictate. The error
      should name the problem, not just fail.

## 7 · Living with it

- [ ] Dictate for a few minutes continuously. Does the phone get hot? Does the battery
      figure look reasonable in Settings?
- [ ] Leave the keyboard enabled for a day of normal use. Does it stay responsive — no
      "keyboard has stopped" dialogs, no lag when opening a text field?
- [ ] Does anything about the panel look wrong: contrast, cramped touch targets,
      truncation, the waveform stuttering?

## 8 · Only if you want the polish model

- [ ] Settings → Models → download Gemma 3 270M (292 MB), enable polish.
- [ ] Dictate something clumsy. Does the extra wait feel acceptable, or does it break the
      rhythm? If it breaks the rhythm, turn it off — Clean mode is complete without it.
- [ ] Watch for anything the model added that you did not say. It should be impossible —
      `PolishGuard` rejects invented numbers and content words — but this is the guard's
      first contact with a real model, and if it slips through it is the most serious
      defect the app could have.

## What to send back

`adb logcat -d > scribe-log.txt` after a session, plus the answer to §2's timing question
and a screenshot of the panel on both displays. Those three things resolve most of what is
currently unknown.
