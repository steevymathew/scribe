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
UninstallDisplayIcon={app}\{#AppExe}

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
