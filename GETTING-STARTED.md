# Getting Started with Scribe

Scribe lets you **talk instead of type**. Hold a key, speak, let go — your
words appear wherever your cursor is. Everything happens on your own computer;
nothing you say is ever sent over the internet.

This guide is for people who just want to *use* Scribe. You don't need to know
anything about programming, terminals, or Python. Pick your computer type
below and follow the steps.

---

## 1. Which version do I need?

Scribe runs on Windows and Linux. The processor inside your computer decides
which download to grab. If you're not sure which you have, use the table — most
people are on the first row.

| Your computer | What to download | Notes |
|---|---|---|
| **Windows laptop/desktop (Intel or AMD)** | `Scribe-Setup-x64.exe` | The common case. Almost all Windows PCs. |
| **Windows on Snapdragon / ARM** (e.g. Surface Pro (2024+), many new thin-and-light laptops) | `Scribe-Setup-arm64.exe` | Made for the Snapdragon X chip. |
| **Linux (Intel or AMD)** | `Scribe-x86_64.AppImage` | One file, no install. See the Linux section. |

**How to check on Windows if you're unsure:** press the Windows key, type
"About your PC", open it, and look at **System type / Processor**. If it mentions
"ARM" or "Snapdragon", use the `arm64` download. Otherwise use `x64`.

> You only ever run **one** file — the installer for your system. You do **not**
> need Python, a terminal, or any of the developer files in this project folder.

### No admin rights? Use the portable version

If you can't install software on your PC (a work laptop, a locked-down machine),
grab the **portable** build instead: `Scribe-Portable-x64.zip` (or `-arm64`).
Unzip it anywhere — your Desktop, a folder, even a USB stick — open the `Scribe`
folder, and double-click **`scribe-tray.exe`**. Nothing is installed, no admin
password is asked, and everything Scribe creates (your settings, its logs, and
the downloaded speech model) stays inside a `ScribeData` folder right next to
the program. To remove it, just delete the folder.

---

## 2. Install on Windows (the easy path)

1. **Download** the correct installer for your PC from the Releases page
   (`Scribe-Setup-x64.exe` or `Scribe-Setup-arm64.exe`).
2. **Double-click** it. It installs just for you — no admin password needed.
3. Windows may show a blue **"Windows protected your PC"** box. This is normal
   for new apps that aren't yet code-signed. Click **More info**, then
   **Run anyway**. (Scribe is open-source and runs entirely offline; the warning
   is about the missing paid signing certificate, not about safety.)
4. Click through: **Next → Install → Finish.** Scribe starts automatically.

### First time you run it

A short **setup wizard** walks you through three things:

1. **Microphone** — pick which mic to use and watch the level bar move when you
   talk, so you know it's hearing you.
2. **Push-to-talk key** — the key you hold to dictate. The default is the
   **Right Alt** key (to the right of the spacebar). You can pick a different one.
3. **Download the voice model** — a one-time download (~500 MB) of the speech
   recognition model. **This is the only time Scribe uses the internet.** After
   this, it works completely offline, even in airplane mode.

That's it. Scribe now lives quietly in your **system tray** (the little icons
near the clock, bottom-right). It uses almost no resources until you speak.

---

## 3. How to use Scribe

- **Normal dictation:** Hold **Right Alt**, speak, then let go. Your words type
  themselves wherever your cursor is — a Word document, an email, a chat box,
  anything.
- **High-accuracy mode:** Hold **Right Shift** as well (Right Shift + Right Alt)
  while you speak. This uses a bigger, more precise model — good for names,
  technical terms, or anything that has to be exactly right. The first use loads
  the bigger model (a few seconds); after that it's instant.

A small floating pill shows you when Scribe is **Listening**, **Transcribing**,
and when it has **Inserted** your text, so you always know what it's doing.

To change your microphone, key, or model later, click the Scribe icon in the
tray and open **Settings**.

---

## 4. Linux

Linux packaging is still being finalized. For now:

- **AppImage (coming):** download `Scribe-x86_64.AppImage`, right-click →
  Properties → allow "Execute", then double-click to run. You'll also need one
  small system helper for typing at the cursor — `xdotool` (on X11) or `wtype`
  (on Wayland) — installable from your distro's software manager.
- **From source (available today):** if you're comfortable in a terminal, see
  the developer instructions in [README.md](README.md) (`./setup.sh` then
  `./scribe`).

If you only need Linux and the AppImage isn't published yet, follow the README's
source instructions or ask the maintainer for a build.

---

## 5. Uninstalling (Windows)

Open **Settings → Apps → Installed apps**, find **Scribe**, and click
**Uninstall**. It removes cleanly. Your downloaded voice models and preferences
are left in place in case you reinstall; you can delete those folders manually if
you want them gone (`%LOCALAPPDATA%\Scribe` and `%APPDATA%\Scribe`).

---

## 6. Common questions

**Does Scribe send my voice anywhere?** No. After the one-time model download
during setup, nothing leaves your computer — no audio, no text, no analytics.

**Do I need internet to use it?** Only once, for the initial model download.
After that it works fully offline.

**It typed nothing / it types silence.** Open the tray → Settings and check the
microphone level bar moves when you talk. If not, pick a different microphone or
set the right one as your system default input.

**The key doesn't do anything.** Some apps grab certain keys first. Try choosing
a different push-to-talk key in Settings (for example Right Ctrl or Pause).

**Which file do I run day-to-day?** None manually — Scribe starts with Windows
(if you left that option on) and waits in the tray. Just hold your key and talk.

---

### A note on the many files in this folder

If you're looking at the project source folder, you'll see a lot of scripts
(`scribe.bat`, `scribe-npu.bat`, `scribe-ui.bat`, `setup.bat`, and so on).
**Those are for developers.** As a regular user you should ignore all of them
and only use the single **installer** for your system (Section 2). The installer
bundles everything Scribe needs — you don't have to choose a script or install
Python. The goal is one download, one install, done.
