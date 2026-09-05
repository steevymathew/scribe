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

---

# v0.2.0 — the three routes

The first build shipped only Scribe's own keyboard, and on a real Fold 7 it was listed,
switched on, and never drew. That is fixed below, but the more useful change is that the
keyboard is no longer the only way in.

**Uninstall 0.1.0 first** if it is still there — same key, but a clean state is worth more
than the two seconds saved.

## 1 · Voice input — try this one first

The route that needs no keyboard switching at all.

- [ ] Settings → General management → Keyboard list and default → **Voice input**.
      Is **"Scribe (on-device)"** in the list? Pick it.
      If Samsung's picker only offers its own and Google's, say so — the service is
      registered correctly (`adb shell dumpsys package dev.smantics.scribe | grep -A3
      RecognitionService`), but Samsung Keyboard may only route to a fixed pair. In that
      case try Gboard's microphone, or use the bubble.
- [ ] Open any app, tap a text field, press the microphone on your usual keyboard.
- [ ] Speak, then **stop speaking**. Scribe ends the utterance on about 1.5 s of silence —
      there is no button to release on this path. Does it end when you expect, or does it
      cut you off mid-thought? That timing is the one number I could not tune without a
      device.

## 2 · The bubble

- [ ] Scribe → *Ways to use Scribe* → "A button that appears on text fields" → Turn on.
      It opens Accessibility settings; enable Scribe there.
- [ ] Tap any text box in any app. A small Scribe button should appear near the bottom
      right, above the keyboard.
- [ ] Tap it. It should expand into a strip with the waveform, the RAW/CLEAN switch, and a
      hamburger that opens the model name.
- [ ] Speak. The text should land at the cursor **without destroying anything already in
      the field** — try it with a half-written message and the cursor in the middle.
- [ ] Does the bubble disappear when no text field has focus? Does it stay put while you
      are actually dictating?

## 3 · The keyboard, which should now work

The failure was Compose resolving its recomposer from the window's root view, which in an
input method is a framework-created decor with no lifecycle owner attached — so composition
never started and nothing drew.

- [ ] Enable and select Scribe's keyboard. The panel should appear.
- [ ] **If it still does not**, you should now see a plain dark panel naming the exception
      instead of nothing at all. Send me that text — it is the whole diagnosis.
- [ ] `adb logcat -s ScribeIME` if you want the same thing with a stack trace.

## 4 · The logo

- [ ] The launcher icon should be the Scribe mark — the teal S with the pen and microphone —
      on a light tile, and the same mark should appear in the app header and on the
      collapsed bubble. Tell me if the light tile looks wrong against your wallpaper; a
      dark ground is a one-line change.

---

# v0.7.0 — the bubble types, and the keyboard settles

Two things were reported from the phone and both are fixed below. Neither fix could be
tested here: one needs another app's text field, the other needs eyes on a real animation.

**Uninstall 0.6.0 first.** Same key, but the accessibility service's state is worth
starting clean.

## 1 · The bubble actually putting words in the field

This is the one that matters. The report was: *"it writes it in the bubble but never puts
the text into the text field, and the insert button just shrinks the bubble back down."*
Both halves were the same fault — insertion failed, the exception was swallowed, and the
panel collapsed exactly as it does on success.

- [ ] Open a chat or notes app, tap a text field, and dictate through the bubble.
      **The words should land at the cursor.**
- [ ] With a half-written message and the cursor in the middle of it, dictate again.
      Nothing already in the field should be lost, and the new words should not be welded
      onto the ones on either side of the cursor.
- [ ] Dictate twice in a row without moving the cursor. Two sentences, one space between
      them — not `one.Two.`
- [ ] Try it in something that is not a plain `EditText`: a browser's address bar, a
      WebView-based app, Samsung's own Messages. These are the cases where finding the
      field takes more than one attempt.

**If it still does not go in**, the panel now says so rather than closing. Send back:

- what the red line in the panel says — it names the actual reason
- `adb logcat -s ScribeA11y -s ScribeEngine -d > scribe-insert.txt`

Those two together say which step failed. The lines to look for:

| Line | What it means |
|---|---|
| `inserted N characters into <package>` | it worked |
| `could not insert: no text field has focus` | nothing on screen was reachable and editable — the field-finding cascade came back empty |
| `could not insert: this app would not accept typed text` | the field was found and the app refused `ACTION_SET_TEXT` |
| `could not insert: the app did not answer in time` | the app did not respond within 2.5 s |
| `the field still reads as it did before the write` | the app said yes and then kept its own text — the interesting one, and the only case with no fix in Scribe |
| `nothing to insert (empty=…, sink=…)` | the transcript never reached the insertion path at all |

- [ ] When it fails, the transcript is still on screen and **"Tap the field, then try
      again"** is offered. Tap into the field, press it — does the text go in on the second
      attempt? If it does, the cause is focus being lost during the reveal, and that is
      worth knowing.

## 2 · The keyboard coming up and going down

Four things were changed, all of which show up in the same place — the show and hide
animation — so this is one observation, not four:

- [ ] Open and close the keyboard a dozen times in a row. It should slide up and down
      cleanly, with no stutter and **no jump in height** part-way through.
- [ ] Do it with the letters showing, and again with them hidden. The letters case is the
      one that used to open short and then grow: the panel now knows its own height before
      it draws its first frame.
- [ ] Watch the panel while it slides *away*. It used to freeze part-way down.
- [ ] Leave the keyboard open and idle for a minute, then check the battery figure in
      Settings → Battery → Scribe. Nothing should accumulate: the panel now stops drawing
      frames when nothing is animating, where before the waveform kept it redrawing at
      display rate forever, open or not.
- [ ] Type a fast sentence on the letters. Any input lag?

## 3 · Nothing else should have moved

- [ ] Dictation through the keyboard still inserts at the cursor.
- [ ] The Raw/Clean toggle still re-renders text already inserted (keyboard only).
- [ ] The bubble still drags, still drops onto the target to dismiss, and the notification
      still brings it back.

---

# v0.8.0 — the bubble as one object, and a model setting that means something

From the first real device pass. Five of the six items below are things that were wrong
rather than missing.

## 1 · Turning the bubble on — the instructions were wrong

The app used to say "Turn on" and leave you at Accessibility settings, where a sideloaded
build cannot be enabled at all. The actual route, as found on the phone, is now in the app
behind a **Steps** button on the Home screen:

1. Tap Turn on. Find Scribe in the accessibility list and try to enable it.
2. Android says restricted settings are blocking it. Close that dialog.
3. Android Settings → Apps → Scribe.
4. Three dots, top right → **Allow restricted settings**.
5. Back to Accessibility → Scribe, turn it on.

- [ ] Follow the steps as written on a fresh install. Do they match what the phone does,
      exactly, including the wording of the dialog? If any step is off, that is worth
      correcting again — this is the first thing anyone does.

## 2 · The circle and the panel are one object now

- [ ] The circle is on screen whether the panel is open or shut. It never disappears.
- [ ] Tapping the circle opens the panel; tapping it again closes it. **The circle does not
      move** when the panel opens — the panel grows upward from it.
- [ ] The circle is lit teal while the panel is open, and red while recording.
- [ ] Dictation now starts from the **microphone in the panel**, not from the circle.
- [ ] Drag the circle: the whole thing moves, panel included.
- [ ] Drag the *panel* — its edges, the control row, the status line: it moves too. Over
      the transcript a vertical drag scrolls the text instead, which is deliberate; the
      circle is always a handle.
- [ ] Drag it to the top of the screen and to the far left. It should stop at the edges
      rather than disappearing off them.
- [ ] Drag it to the bottom and drop it on the target: still closes.

## 3 · Cancel

- [ ] While dictating there is a **red** button at the left of the row. It discards the
      utterance. It is the only red control there.
- [ ] It is not there when nothing is running.
- [ ] "Discard" is gone from the hamburger menu — it is this button now.

## 4 · The model setting actually changes the model

This was a real bug: the transcriber was loaded once and cached for the life of the
process, so choosing a different model changed the setting, the IN USE tag, and nothing
else. The panel then went on naming the old one, which is what you saw.

- [ ] Settings → Models → download Small (English) → **Use**.
- [ ] Open the bubble's hamburger menu. It should say **Small (English)** — the same name
      the settings screen uses — within a second or two, without reopening the app.
- [ ] Dictate. Is it noticeably slower and more accurate on names than Base was? That is
      the confirmation the model itself changed rather than only the label.
- [ ] Switch back to Base (English). The label should follow again.
- [ ] `adb logcat -s ScribeEngine -s WhisperProvider` shows `speech model changed:
      base.en -> small.en` and `everyday model changed to Small (English); reloading`.

## 5 · "High accuracy" is gone

It set a second model for a boost mode that no control could turn on — the keyboard's
boost button had been removed on purpose, and this was left behind pointing at it. So it
set a value, showed a tag, and changed nothing observable.

- [ ] Settings → Models shows one button per installed model: **Use**. No "High accuracy",
      no HIGH ACCURACY tag.
- [ ] The card says one model is in use at a time and that Use takes effect straight away.

## 6 · Nothing else moved

- [ ] Insertion still works (v0.7.0 §1), and still says so when it does not.
- [ ] The keyboard still opens and closes smoothly (v0.7.0 §2).
- [ ] The bubble's close badge still hides it, and the notification still brings it back.

---

# v0.9.0 — the audio that went missing, and the wait that was never explained

## 1 · Long dictations no longer lose the end

There **was** a hard limit and it was invisible. Three separate faults:

- the clip was cut to 120 s with `copyOf` *after* you finished speaking, so the panel
  showed a live transcript of everything and typed in the first two minutes;
- past 130 s the capture thread wrote off the end of its buffer, died inside a
  `catch (Throwable)` that only logged, and every sample after that was lost;
- the buffer doubled by copying itself — several megabytes — **on the capture thread**,
  which has 20 ms to get back to `AudioRecord.read` before the driver drops audio. Past
  about a minute that copy is long enough to lose words in the *middle* of an utterance,
  silently.

All three are gone: audio is held as one-second blocks, nothing is ever copied or
reallocated on the capture thread, there is no ceiling, and nothing captured is discarded.

- [ ] Dictate for **three minutes** without stopping. Does every part of it arrive? The
      end especially.
- [ ] Dictate for about **90 seconds** and read the result carefully for gaps in the
      middle — that was the silent-dropout window.
- [ ] Keep going past three minutes. At 3:00 it should **stop by itself** and say
      "That's the 180 second limit — transcribing what you said", then transcribe
      everything up to that point. It must not simply stop responding.

## 2 · You can now see how long you have been talking

- [ ] While dictating the panel says `LISTENING · 1:37`, counting up.
- [ ] Inside the last 20 seconds it turns amber.
- [ ] This works on the keyboard and the bubble alike.

## 3 · The wait after pressing insert is explained, and shorter when it was long

What it is doing: **Whisper is not a streaming recogniser.** The live transcript you see
while talking is a separate decode of the audio so far, run every 1.8 s and then thrown
away. Pressing insert starts a *fresh* decode of the whole clip from the beginning, and
that decode costs a fraction of the length of what you said. A two-minute dictation is a
real wait, and none of it was previously visible.

- [ ] While it decodes, the panel says `TRANSCRIBING 1:02` — the length of the audio it is
      working through.
- [ ] **Time it.** From pressing insert to the text landing, for a 10-second dictation and
      again for a 60-second one. Those two numbers are the most useful thing you can send
      back: they give the phone's real-time factor, which nothing on the build machine can
      establish and which decides whether segmented decode is worth building next.
- [ ] After a *slow* decode the cleanup animation is skipped and the text goes straight in
      — no "cleaning it up" theatre on top of a wait you already sat through. After a fast
      one the reveal still plays.
- [ ] Anything above about 2.5 s of decoding should skip it. Does that feel like the right
      threshold, or should it be lower?

## 4 · Nothing else moved

- [ ] The bubble still types into the field, the circle still opens and closes the panel,
      cancel is still red, and the model setting still takes effect (v0.8.0).

---

# v0.10.0 — the keyboard rebuilt, and a notification that stays

## 1 · The bubble's notification

It used to appear only *after* the bubble was closed — the moment you are least likely to
be reading notifications — and if it was ever swiped away there was no route back short of
the accessibility settings. It is now posted from the moment the service starts, and it
puts itself back if it is dismissed.

- [ ] Turn the bubble on. A silent, low-priority "Scribe" notification should appear
      immediately, saying the button is on screen.
- [ ] Close the bubble with its X. The notification should change to "Tap to bring the
      dictation button back" and gain a **Show button** action. Both the action and the
      body should bring it back.
- [ ] **Try to swipe the notification away.** It should come straight back.
- [ ] Turn the accessibility service off. The notification should go for good.
- [ ] Confirm it is only there for the bubble — using only the keyboard should leave you
      with no permanent Scribe notification (the "Scribe is listening" one appears while
      recording and goes when it stops; that is a different one and is required by Android).

## 2 · The keyboard layout

Built from your two screenshots.

- [ ] Number row across the top, `1`–`0`.
- [ ] Every letter shows a small symbol on its top-right shoulder — `q` has `-`, `a` has
      `@`, `z` has `*`, and so on. **Hold a key** and you get the shoulder symbol instead
      of the letter, with no stray letter typed alongside it.
- [ ] Bottom row is `?123 | , | ☺ | space | . | ⏎`.
- [ ] **Hold the comma** — it opens Scribe. (Android's own convention for a keyboard's
      settings key.)
- [ ] There is no key that switches to another keyboard, no width key, and no split key.
- [ ] On the unfolded Fold, the split shows `asdfg` / `ghjkl` and `zxcv` / `vbnm` — **`g`
      and `v` on both halves**, so neither thumb reaches across the hinge. Shift and
      backspace sit on the outside edges.

## 3 · The keys should feel pressed

- [ ] Every key is raised at rest and **visibly sinks** when your finger is on it — the
      highlight and shadow swap corners.
- [ ] The sink and the vibration happen together, on touch-down, not on release.
- [ ] Press a key and slide off it without lifting: it should un-press and type **nothing**.
- [ ] This applies to every key, including space, shift, backspace, enter and the emoji.

## 4 · The swipes

- [ ] **Swipe down** anywhere on the keys — they go away and leave a thin grabber bar.
- [ ] **Swipe up** on the grabber — they come back. Tapping it also works.
- [ ] **Swipe right** — the symbols page. **Swipe left twice** from there — emoji.
- [ ] Swiping past the ends does nothing (emoji ← letters → symbols is a run, not a loop).
- [ ] Faint chevrons on the left and right edges of the card hint at the pages either side.
- [ ] Is the swipe threshold right? It should be hard to trigger by accident while typing
      and easy on purpose. **This is the number most likely to need tuning** — tell me if
      it fires while you type or takes too much travel.
- [ ] On the emoji page a vertical drag scrolls the emoji rather than hiding the keyboard.
      Swiping right still leaves the page. Is that acceptable, or should the keyboard hide
      from there too?

## 5 · What is remembered

- [ ] Hide the keys, close the keyboard, open it again — the keys should still be hidden.
- [ ] Leave the keyboard on the **symbols or emoji** page, close it, open it again — it
      should come back on the **letters**. The up/down state is sticky; the page is not.

## 6 · Settings

- [ ] Scribe → the **KEYBOARD** card has *Split* (Automatic / Never / Always) and *Width*
      (Full / Slightly narrower / Compact).
- [ ] Changing either takes effect on the keyboard without restarting anything.
- [ ] Automatic should split on the unfolded Fold and not on the cover screen.

## 7 · Nothing else moved

- [ ] The bubble still types into fields, the mic still dictates from the strip, the
      transcript still shows `LISTENING · 0:12` while you talk.
- [ ] The Scribe mark at the left of the voice strip opens the app.
