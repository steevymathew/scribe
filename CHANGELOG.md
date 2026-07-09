# Changelog

A dated record of what changed and when. Newest first. Times are local
(America/Chicago). This log is maintained by hand alongside the git history.

## Unreleased — `v1-polish` branch

### 2026-07-08

- **23:40 — Long-audio fix: transcribe clips longer than 30 seconds.**
  Whisper encodes a fixed 30 s window and the feature extractor silently
  truncates anything longer, so the tail of a long dictation was dropped
  (both models — it's the ONNX pipeline, not the model). The ONNX backend now
  splits long audio into ≤30 s windows, cutting each at the quietest point in
  its final ~2 s so word boundaries survive, and joins the results. Verified a
  36.7 s clip now captures content past 0:30 that was previously lost.
- **23:10 — Removed VAD from the ONNX hot path.** Whisper already pads clips to
  30 s internally, so voice-activity trimming saved almost nothing while
  risking clipped quiet words and, worse, dropping real speech. The path now
  transcribes the full clip; genuine silence returns `[BLANK_AUDIO]`, which the
  post-processor strips. `vad.py` stays for possible future opt-in use.
- **15:50 — Fixed "nothing typed" on quieter mics.** Two independent silence
  gates (an RMS energy check and a second `is_speech()` gate inside the ONNX
  backend) were discarding real dictation on mics quieter than the test clip.
- **14:17 — Fixed dictation getting stuck on "Transcribing".** When the VAD
  judged a clip silent it returned without emitting the terminal UI event, so
  the tray/overlay hung. Every path now emits a terminal event; console status
  is stdout-safe for the windowed build; the full record→transcribe→type
  pipeline is now logged for diagnosis.

### 2026-07-07 — v1 polish (Wispr-Flow-grade UX pass, per `ROADMAP.md`)

- **18:00 — Packaging & distribution (Phase 5b/5c) + NPU tooling (Phase 6
  prep).** PyInstaller spec building `scribe.exe` (console) and
  `scribe-tray.exe` (windowed); per-user Inno Setup installer (Start menu,
  optional sign-in autostart, clean uninstall); `BUILDING.md`;
  `tools/build_npu_encoder.py` for the future Qualcomm AI Hub encoder artifact
  (offline validate path verified).
- **17:52 — First-run onboarding wizard (Phase 5a).** Welcome/privacy → mic
  check with live level meter → press-a-key hotkey capture → one-time model
  download → done. Shown when no config exists.
- **17:06–17:10 — Desktop UI (Phase 3) and quality (Phase 4), built in
  parallel and merged.** System-tray app with recording overlay and settings
  window (`--ui`); Silero VAD, post-processing (filler removal, custom
  dictionary), language setting.
- **11:49 — Package refactor + config (Phase 2).** Split the single file into
  `src/scribe/` with a compatibility shim so all launchers keep working; TOML
  config with `defaults < file < CLI` precedence; unit tests.
- **11:41 — Quiet console + logging (Phase 1).** Third-party noise suppressed;
  everything routed to a rotating log file; `--advanced` streams the full log;
  no more raw tracebacks — friendly one-liners, details in the log.
- **11:35 — Baseline tag.** Snapshot of the working NPU/ONNX backend before the
  polish work began.

## Earlier — original project and Snapdragon backend

- **2026-06-25 — Snapdragon (Windows-on-ARM) support added.** Native-ARM64
  ONNX Runtime backend (`--device npu`) because `faster-whisper`/CTranslate2
  has no ARM64 build and would otherwise run under slow x64 emulation.
  KV-cache decoding; int8 `large-v3-turbo` for high-accuracy (boost) mode;
  Right-Alt/AltGr hotkey handling. Encoder is NPU-ready via `--npu-encoder`;
  until a QNN-prepared model is supplied it runs on the native-ARM64 CPU
  (still several times faster than emulation).
- **Original — Scribe.** Fully-offline push-to-talk dictation for Linux
  (X11/Wayland) and Windows: hold a key, speak, release, text appears at the
  cursor. faster-whisper (CPU) and openai-whisper (CUDA) backends.
