# UAT brief

The brief given to reviewers who assess Scribe for Android as a user would, rather than as
its author does.

## Who you are

You own a Galaxy Z Fold 7. You have been paying for Wispr Flow and you are tired of it: the
floating bubble stops responding every few minutes, setup involved developer options, it
does not work on a plane, and you have never been comfortable that your voice goes to
someone else's server. You are trying Scribe because it claims to do the same job on the
phone itself.

You are not a tester looking for crashes. You are someone deciding, within about ten
minutes, whether this replaces what you were paying for. Note anything that would make you
put it down — friction, confusion, a moment where you cannot tell what the app is doing, a
place where it does something you did not ask for.

## What to hold it to

- `Cyber/ux/UX — First Principles.md` and `Cyber/ux/UX — Review Checklist.md` in the vault.
  Findings should name the principle they violate.
- The desktop Scribe's feature set: it is the same product, and a person moving from laptop
  to phone should not find things missing or renamed for no reason.
- Wispr Flow's feature set: auto punctuation, filler removal, self-corrections, numbered
  lists, custom dictionary, snippets, per-app tone, whisper-quiet speech, 100+ languages.
  Where Scribe deliberately does not match, the reasoning should be defensible — not absent.

## The flows

Walk each one end to end and say where it breaks down.

**First run.** Install → welcome → grant the microphone → enable the keyboard → learn what
Raw and Clean mean → finish. Can it be completed by someone who does not know what an IME
is? Is any step a dead end?

**The first dictation.** Open a chat app → switch to Scribe → hold the button → speak → let
go → text appears. What does the user see at each moment? Is it obvious that it is
listening, that it is working, that it finished?

**The mode switch.** Dictate in Clean. Tap the toggle. The inserted text should be replaced
with the Raw rendering of the same utterance. Is that discoverable? Is it obvious that it
changed, and what changed?

**Correcting a word.** Scribe hears a name wrong. What does the user do? How many taps and
screens to add it to the dictionary and make it stick?

**Getting a better model.** Notice mistakes on names → find the models screen → understand
what "Small, 181 MB, 7.08 % WER" means → download it → use it. Is the trade-off legible
without knowing what a word error rate is?

**Losing the microphone.** The keyboard is up and RECORD_AUDIO has not been granted (an
input method cannot ask for it). What happens? Can the user get out of it?

**Folding mid-sentence.** Start dictating on the cover screen, open the phone. What should
happen, and what does the code say happens?

**Offline.** Aeroplane mode, first launch, no model downloaded beyond the bundled one.
Does everything that should work still work? Is anything shown that is only true online?

**Going back to Gboard.** Is leaving Scribe as easy as arriving?

## What you have to work with

- `app/build/screenshots/` — real Compose renders of the keyboard in every state, at the
  Fold 7's cover and inner display metrics. These are honest pixels but not a photograph of
  a phone; see the note at the top of `ScribeScreenshotTest`.
- The source, which is commented with the reasoning behind each decision.
- `docs/accuracy.md` — measured word error rates.
- The test suites, which encode the intended behaviour.

## What is out of scope, and why

There is no device. Nothing about real microphone behaviour, on-device speed, battery,
heat, or how One UI treats the keyboard can be assessed here, and **guessing at them is
worse than leaving them blank**. Mark anything in that category `OWNER-VERIFY` and move on.

## How to report

One finding per issue:

```
SEVERITY  blocker | major | minor | polish
WHERE     file:line, or the screenshot name
WHAT      what a user would experience
WHY       the principle or expectation it breaks
FIX       the smallest change that resolves it
```

Rank by whether it would make the person in the first paragraph stop using the app. Do not
pad the list: a finding that is really a preference should say so. If a flow is genuinely
fine, say that too — a review that finds problems everywhere is as useless as one that
finds none.
