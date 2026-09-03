; Inno Setup script for Scribe (Windows installer).
; Compile:  iscc packaging\installer.iss                     (x64 build)
;           iscc /DTargetArch=arm64 packaging\installer.iss  (Snapdragon build)
; Expects the PyInstaller bundle at dist\scribe\ (see packaging\scribe.spec).
; Per-user install: no admin prompt, no UAC, clean uninstall.

#ifndef TargetArch
  #define TargetArch "x64"
#endif
#define AppName "Scribe"
#define AppVersion "0.9.0"
#define AppExe "scribe-tray.exe"

[Setup]
AppId={{7A1B7C63-5B7E-4C6E-9A34-scribe-dictation}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Scribe (open source, MIT)
DefaultDirName={localappdata}\Programs\{#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=Scribe-Setup-{#TargetArch}
Compression=lzma2
SolidCompression=yes
#if TargetArch == "arm64"
ArchitecturesAllowed=arm64
#else
ArchitecturesAllowed=x64compatible
#endif
; Branded icon for the installer/uninstaller UI (the exes carry it embedded too).
SetupIconFile=..\src\scribe\ui\assets\scribe.ico
UninstallDisplayIcon={app}\{#AppExe}

; --- Optional code signing (off by default; unsigned build still compiles) ---
; Two ways to sign, pick one (see BUILDING.md > Code signing):
;
;   A) RECOMMENDED / lower risk: leave this block OFF and sign the produced
;      installer AFTER compiling, with the same pipeline used for the exes:
;          tools\sign.ps1 dist\Scribe-Setup-x64.exe
;      No Inno config, cannot break the build. (Trade-off: the embedded
;      uninstaller is not separately signed - acceptable for most releases.)
;
;   B) Sign installer AND uninstaller at compile time via Inno's SignTool.
;      Enable by passing /DSignScribe and defining the "scribe" signer that
;      the block below references, e.g. (one line):
;          iscc /DSignScribe ^
;               "/Sscribe=powershell -NoProfile -ExecutionPolicy Bypass -File \"%CD%\tools\sign.ps1\" $f" ^
;               packaging\installer.iss
;      ($f is Inno's placeholder for the file being signed; sign.ps1 reads the
;      cert from SCRIBE_CERT_PFX / SCRIBE_CERT_PASS. Without /DSignScribe the
;      directives are skipped so no signer definition is required to compile.)
#ifdef SignScribe
SignTool=scribe
SignedUninstaller=yes
#endif

[Tasks]
Name: "autostart"; Description: "Start {#AppName} automatically when I sign in"; \
  GroupDescription: "Startup:"
Name: "desktopicon"; Description: "Create a &desktop shortcut"; \
  GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "..\dist\scribe\*"; DestDir: "{app}"; \
  Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{userprograms}\{#AppName} (console)"; Filename: "{app}\scribe.exe"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
  ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\{#AppExe}"""; \
  Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName} now"; \
  Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Config/logs are the user's data — leave them. Only the app dir goes.
Type: filesandordirs; Name: "{app}"
