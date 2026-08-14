<#
.SYNOPSIS
  Work out which Scribe delivery path a locked-down Windows PC will actually allow.

.DESCRIPTION
  Scribe ships several install paths (per-user EXE installer, portable ZIP,
  portable-from-USB, Python-from-source, and an offline/pre-seeded variant).
  Different companies lock different things down, so the same download that
  works at one client is dead on arrival at the next.

  Run this script on the target PC. It probes what that machine's security
  configuration permits — not by guessing from policy alone, but by actually
  writing files, actually launching a test executable from each candidate
  folder, actually touching the registry keys the installer touches — and then
  prints a verdict per delivery path plus a concrete "build recipe" for that
  machine.

  It also checks the things that decide whether Scribe *works* after it is
  installed: microphone policy, the global keyboard hook it needs for
  push-to-talk, disk space for the speech models, and whether pypi.org /
  huggingface.co are reachable through the corporate proxy.

  SAFE BY DESIGN. The script needs no admin rights, makes no permanent change,
  and installs nothing. Every file, registry value, shortcut and scheduled task
  it creates is a uniquely-named probe that is deleted again before it exits.
  It never reads or transmits user data; the report it writes stays on disk for
  you to send back by hand.

.PARAMETER OutDir
  Where to write the .txt and .json report. Defaults to the current directory,
  falling back to %TEMP% if that is not writable.

.PARAMETER Quick
  Skip the slow probes: venv creation, `pip download`, and network reachability.
  Use for a fast application-control-only read.

.PARAMETER SkipNetwork
  Skip only the outbound-connectivity and TLS-interception probes.

.PARAMETER TestInputHooks
  Additionally test whether a low-level keyboard hook (SetWindowsHookEx with
  WH_KEYBOARD_LL) can be installed. This is exactly what Scribe's push-to-talk
  needs, so it is the single most decisive runtime check — but installing a
  global keyboard hook is also what a keylogger does, so some EDR products will
  raise an alert on it. It is therefore OFF by default: turn it on when you are
  cleared to, or accept an UNKNOWN verdict for push-to-talk. The probe installs
  a do-nothing pass-through hook, holds it for ~100 ms, and removes it; no
  keystroke is ever inspected, stored or logged.

.PARAMETER NoColor
  Plain output, for piping into a file or a ticket.

.EXAMPLE
  # Normal use, from a PowerShell window on the target PC:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scribe-envcheck.ps1

.EXAMPLE
  # Fullest picture (see -TestInputHooks caveat above):
  .\scribe-envcheck.ps1 -TestInputHooks -OutDir $env:USERPROFILE\Desktop

.NOTES
  If running .ps1 files is itself blocked on the target machine, that is a
  finding in its own right — see "If this script will not run" in BUILDING.md
  for the paste-into-console fallback.
#>
[CmdletBinding()]
param(
    [string] $OutDir,
    [switch] $Quick,
    [switch] $SkipNetwork,
    [switch] $TestInputHooks,
    [switch] $NoColor
)

# Deliberately NOT Set-StrictMode: this is a diagnostic that runs on hostile,
# half-broken configurations. A missing property must degrade to one UNKNOWN
# check, never abort the run. Every probe is wrapped in Invoke-Safe instead.
#
# SilentlyContinue (not Continue) so that non-terminating errors from probing a
# locked-down box do not spray red text through the report. Everything we
# actually need to catch is requested with an explicit -ErrorAction Stop, which
# overrides this preference.
$ErrorActionPreference = 'SilentlyContinue'
$ProgressPreference    = 'SilentlyContinue'

$SCRIPT_VERSION = '1.0.0'
$MODEL_BYTES_MIN  = 1.5GB   # small.en (~500 MB) + boost model (~1 GB)
$SOURCE_BYTES_MIN = 4GB     # venv with torch/PySide6 + both models

if ($Quick) { $SkipNetwork = $true }

# ==============================================================================
#  Result collection
# ==============================================================================

$script:Checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param(
        [string] $Id,
        [string] $Area,
        [string] $Title,
        [ValidateSet('Pass', 'Fail', 'Warn', 'Info', 'Skip', 'Unknown')]
        [string] $Status,
        [string] $Detail = '',
        [string] $Impact = '',
        $Data = $null
    )
    $script:Checks.Add([pscustomobject]@{
        Id     = $Id
        Area   = $Area
        Title  = $Title
        Status = $Status
        Detail = $Detail
        Impact = $Impact
        Data   = $Data
    }) | Out-Null
}

function Get-Check {
    param([string] $Id)
    foreach ($c in $script:Checks) { if ($c.Id -eq $Id) { return $c } }
    return $null
}

function Test-CheckStatus {
    <# True when the named check exists and has one of the given statuses. #>
    param([string] $Id, [string[]] $Status)
    $c = Get-Check $Id
    if ($null -eq $c) { return $false }
    return ($Status -contains $c.Status)
}

function Invoke-Safe {
    <# Run a probe; convert any explosion into an Unknown check instead of
       killing the whole run. The failure is printed as well as recorded - a
       silently missing section reads as "nothing to report" when it actually
       means "could not tell", and those are very different answers here. #>
    param([string] $Label, [scriptblock] $Body)
    try { & $Body }
    catch {
        Add-Check -Id ("error.{0}" -f $Label) -Area 'Diagnostics' `
            -Title "Probe '$Label' failed to run" -Status 'Unknown' `
            -Detail $_.Exception.Message `
            -Impact 'This area could not be assessed on this machine.'
        Out-Status 'Unknown' "Probe '$Label' could not run" $_.Exception.Message
    }
}

# ==============================================================================
#  Console output
# ==============================================================================

$script:Transcript = New-Object System.Text.StringBuilder

function Out-Line {
    param([string] $Text = '', [string] $Color = 'Gray')
    [void]$script:Transcript.AppendLine($Text)
    if ($NoColor) { Write-Host $Text } else { Write-Host $Text -ForegroundColor $Color }
}

function Out-Head {
    param([string] $Text)
    Out-Line ''
    Out-Line ('== ' + $Text + ' ' + ('=' * [Math]::Max(3, 74 - $Text.Length))) 'Cyan'
}

function Out-Status {
    param([string] $Status, [string] $Text, [string] $Detail = '')
    $map = @{
        Pass = @('  [ OK ] ', 'Green'); Fail = @('  [FAIL] ', 'Red')
        Warn = @('  [WARN] ', 'Yellow'); Info = @('  [ -- ] ', 'Gray')
        Skip = @('  [SKIP] ', 'DarkGray'); Unknown = @('  [ ?? ] ', 'Magenta')
    }
    $m = $map[$Status]
    if (-not $m) { $m = @('  [ -- ] ', 'Gray') }
    Out-Line ($m[0] + $Text) $m[1]
    if ($Detail) {
        foreach ($line in ($Detail -split "`r?`n")) {
            if ($line.Trim()) { Out-Line ('           ' + $line.Trim()) 'DarkGray' }
        }
    }
}

function Out-Check {
    <# Emit a check and print it in one go. #>
    param(
        [string] $Id, [string] $Area, [string] $Title, [string] $Status,
        [string] $Detail = '', [string] $Impact = '', $Data = $null
    )
    Add-Check -Id $Id -Area $Area -Title $Title -Status $Status `
              -Detail $Detail -Impact $Impact -Data $Data
    Out-Status $Status $Title $Detail
}

# ==============================================================================
#  Small helpers
# ==============================================================================

function Get-RegValue {
    param([string] $Path, [string] $Name)
    try {
        $item = Get-ItemProperty -LiteralPath $Path -Name $Name -ErrorAction Stop
        return $item.$Name
    } catch { return $null }
}

function Format-Bytes {
    param([double] $Bytes)
    if ($Bytes -ge 1TB) { return ('{0:N1} TB' -f ($Bytes / 1TB)) }
    if ($Bytes -ge 1GB) { return ('{0:N1} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N0} MB' -f ($Bytes / 1MB)) }
    return ('{0:N0} KB' -f ($Bytes / 1KB))
}

function Get-ExecBlockReason {
    <# Turn the wreckage of a failed launch into a named cause. The distinction
       matters: an AppLocker block is an IT allowlist request, a Defender block
       is a false-positive/exclusion request, and they are not the same ticket. #>
    param([string] $Text, [int] $NativeError = 0)
    $t = "$Text"
    if ($NativeError -eq 1260 -or $t -match 'blocked by group policy|Access Disabled by Policy') {
        return 'AppLocker / Software Restriction Policy'
    }
    if ($t -match 'contains a virus|potentially unwanted|0x800700E1|operation did not complete successfully because the file contains') {
        return 'Defender / antivirus (file quarantined or ASR rule)'
    }
    if ($t -match 'blocked by your system administrator|0x80070241|not permitted to run|Code Integrity') {
        return 'WDAC / Smart App Control (code integrity policy)'
    }
    if ($t -match 'Access is denied|UnauthorizedAccess|0x80070005') {
        return 'NTFS permissions or no-execute on that folder'
    }
    if ($t -match 'is not recognized|cannot find the (path|file)|0x80070002') {
        return 'File was not there (the copy step was blocked)'
    }
    if ($t) { return "Unclassified launch failure: $t" }
    return 'Unclassified launch failure'
}

# ==============================================================================
#  Probe scratch space
# ==============================================================================

$script:Scratch = Join-Path $env:TEMP ('scribe-envcheck-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$script:Cleanup = New-Object System.Collections.Generic.List[string]

function New-ScratchDir {
    try { New-Item -ItemType Directory -Path $script:Scratch -Force -ErrorAction Stop | Out-Null; return $true }
    catch { return $false }
}

# ------------------------------------------------------------------------------
#  A test executable. We want TWO of them, because they answer different
#  questions:
#
#    * "unsigned"   - compiled here and now, no Authenticode signature at all.
#                     This is what a Scribe build looks like to the OS unless we
#                     pay for an EV cert. It is the honest test.
#    * "ms-signed"  - a copy of a Microsoft-signed system binary.
#
#  If ms-signed runs from a folder but unsigned does not, the machine is on
#  publisher rules and the answer is "buy a code-signing certificate", not
#  "pick a different folder". That single distinction drives a lot of the
#  recommendations below, so it is worth compiling an exe to learn it.
# ------------------------------------------------------------------------------

function New-ProbeExe {
    param([string] $Dir)

    $result = [pscustomobject]@{
        UnsignedPath = $null; UnsignedNote = $null
        SignedPath   = $null; Token = 'SCRIBE_PROBE_OK'
    }

    # Microsoft-signed reference binary: hostname.exe prints the computer name
    # and exits, with no side effects.
    $sysHost = Join-Path $env:SystemRoot 'System32\hostname.exe'
    if (Test-Path -LiteralPath $sysHost) {
        $result.SignedPath = $sysHost
    }

    # Genuinely unsigned exe. Add-Type -OutputAssembly uses the .NET Framework
    # compiler and only exists on Windows PowerShell (Desktop edition); on
    # PowerShell 7 it throws, and we fall back to policy inspection only.
    if ($PSVersionTable.PSEdition -ne 'Core') {
        try {
            $cls = 'P' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
            $src = "public class $cls { public static void Main() { System.Console.WriteLine(`"$($result.Token)`"); } }"
            $out = Join-Path $Dir 'scribe-probe-unsigned.exe'
            Add-Type -TypeDefinition $src -OutputAssembly $out `
                     -OutputType ConsoleApplication -ErrorAction Stop
            if (Test-Path -LiteralPath $out) { $result.UnsignedPath = $out }
        } catch {
            $result.UnsignedNote = $_.Exception.Message
        }
    } else {
        $result.UnsignedNote = 'PowerShell 7 cannot compile a test exe (Add-Type -OutputAssembly is Desktop-only). Re-run under Windows PowerShell 5.1 for the unsigned-executable verdict.'
    }
    return $result
}

function Test-DirWrite {
    <# Can we create, write, read back and delete a file here? #>
    param([string] $Dir)
    $r = [pscustomobject]@{ Ok = $false; Error = $null; Path = $null }
    try {
        if (-not (Test-Path -LiteralPath $Dir)) {
            New-Item -ItemType Directory -Path $Dir -Force -ErrorAction Stop | Out-Null
        }
        $f = Join-Path $Dir ('scribe-write-probe-' + [Guid]::NewGuid().ToString('N').Substring(0, 6) + '.tmp')
        Set-Content -LiteralPath $f -Value 'scribe' -Encoding ASCII -ErrorAction Stop
        $back = Get-Content -LiteralPath $f -ErrorAction Stop
        Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue
        $r.Ok = ($back -eq 'scribe')
        $r.Path = $f
    } catch { $r.Error = $_.Exception.Message }
    return $r
}

function Test-DirExecute {
    <# Copy an exe into $Dir and run it. The only way to know for certain
       whether application control permits execution from that path. #>
    param(
        [string] $Dir,
        [string] $SourceExe,
        [string] $ExpectToken,
        [switch] $MarkOfTheWeb
    )
    $r = [pscustomobject]@{
        Ok = $false; Reason = $null; Output = $null; Copied = $false
    }
    if (-not $SourceExe -or -not (Test-Path -LiteralPath $SourceExe)) {
        $r.Reason = 'No test executable available'
        return $r
    }
    $dst = Join-Path $Dir ('scribe-exec-probe-' + [Guid]::NewGuid().ToString('N').Substring(0, 6) + '.exe')
    try {
        if (-not (Test-Path -LiteralPath $Dir)) {
            New-Item -ItemType Directory -Path $Dir -Force -ErrorAction Stop | Out-Null
        }
        Copy-Item -LiteralPath $SourceExe -Destination $dst -Force -ErrorAction Stop
        $r.Copied = $true
    } catch {
        $r.Reason = 'Could not place a file there: ' + $_.Exception.Message
        return $r
    }

    # Stamp the file as internet-downloaded, so SmartScreen / ASR "block
    # low-prevalence executables" / WDAC-on-MOTW rules see what they would see
    # for a real Scribe download from GitHub Releases.
    if ($MarkOfTheWeb) {
        try {
            $zone = "[ZoneTransfer]`r`nZoneId=3`r`nHostUrl=https://github.com/steevymathew/scribe/releases`r`n"
            Set-Content -LiteralPath $dst -Stream 'Zone.Identifier' -Value $zone -Encoding ASCII -ErrorAction Stop
        } catch {
            $r.Reason = 'Could not apply Mark-of-the-Web (alternate data streams unavailable): ' + $_.Exception.Message
        }
    }

    try {
        $out = & $dst 2>&1 | Out-String
        $r.Output = $out.Trim()
        if ($ExpectToken -and $out -match [regex]::Escape($ExpectToken)) {
            $r.Ok = $true
        } elseif (-not $ExpectToken -and $LASTEXITCODE -eq 0) {
            $r.Ok = $true
        } else {
            $r.Reason = Get-ExecBlockReason $out
        }
    } catch {
        $native = 0
        if ($_.Exception -is [System.ComponentModel.Win32Exception]) {
            $native = $_.Exception.NativeErrorCode
        }
        $r.Reason = Get-ExecBlockReason $_.Exception.Message $native
    } finally {
        Remove-Item -LiteralPath $dst -Force -ErrorAction SilentlyContinue
    }
    return $r
}

# ==============================================================================
#  Banner
# ==============================================================================

Out-Line ''
Out-Line '  ###########################################################################' 'Cyan'
Out-Line '  #                                                                         #' 'Cyan'
Out-Line '  #   Scribe - environment capability check                                 #' 'Cyan'
Out-Line '  #   Which install path will this PC actually allow?                       #' 'Cyan'
Out-Line '  #                                                                         #' 'Cyan'
Out-Line '  ###########################################################################' 'Cyan'
Out-Line ''
Out-Line "  script version $SCRIPT_VERSION   started $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" 'DarkGray'
Out-Line '  Read-only by design: nothing is installed, every probe file/key is removed.' 'DarkGray'
if (-not $TestInputHooks) {
    Out-Line '  Note: keyboard-hook probe is OFF (-TestInputHooks turns it on; see -?).' 'DarkGray'
}

# $IsWindows exists only on PowerShell 6+; on Windows PowerShell 5.1 it is
# undefined, and 5.1 only ever runs on Windows anyway.
if ($null -ne $PSVersionTable.Platform -and -not $IsWindows) {
    Out-Line ''
    Out-Line '  WARNING: this is not a Windows machine.' 'Yellow'
    Out-Line '  Every probe below targets Windows security controls, so the results are' 'Yellow'
    Out-Line '  meaningless here. Run this on the target PC instead.' 'Yellow'
}

if (-not (New-ScratchDir)) {
    Out-Line ''
    Out-Line "  FATAL: cannot create a working folder under %TEMP% ($env:TEMP)." 'Red'
    Out-Line '  That alone rules out the Python-from-source path and most installers.' 'Red'
    Out-Line '  Report this result as-is; it is a finding, not a script bug.' 'Red'
    exit 2
}

# ==============================================================================
#  1. Machine identity
# ==============================================================================

Out-Head 'Machine'

Invoke-Safe 'machine' {
    $os   = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
    $cs   = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
    $cpu  = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1

    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($env:PROCESSOR_ARCHITEW6432) { $arch = $env:PROCESSOR_ARCHITEW6432 }
    $isArm = ($arch -match 'ARM')

    $detail = @(
        "OS          : $($os.Caption) build $($os.BuildNumber)"
        "Architecture: $arch$(if ($isArm) { '  -> needs the arm64 build' } else { '  -> needs the x64 build' })"
        "CPU         : $($cpu.Name)"
        "Computer    : $env:COMPUTERNAME"
        "User        : $env:USERDOMAIN\$env:USERNAME"
    ) -join "`n"

    Out-Check -Id 'host.arch' -Area 'Machine' -Title "Target architecture: $arch" `
        -Status 'Info' -Detail $detail -Data @{ Arch = $arch; IsArm = $isArm; Build = "$($os.BuildNumber)" }

    # Management posture. A domain/MDM-managed machine means policy can change
    # under us, and it means there is an IT desk to raise an allowlist ticket with.
    $managed = @()
    if ($cs -and $cs.PartOfDomain) { $managed += "AD domain: $($cs.Domain)" }
    try {
        $ds = & dsregcmd.exe /status 2>$null | Out-String
        if ($ds -match 'AzureAdJoined\s*:\s*YES')   { $managed += 'Entra ID (Azure AD) joined' }
        if ($ds -match 'WorkplaceJoined\s*:\s*YES') { $managed += 'Workplace joined' }
        if ($ds -match 'MDMUrl\s*:\s*\S')           { $managed += 'MDM enrolled (Intune or similar)' }
    } catch { }
    if ($managed.Count -gt 0) {
        Out-Check -Id 'host.managed' -Area 'Machine' -Title 'Centrally managed device' `
            -Status 'Info' -Detail (($managed -join "`n") + "`nPolicy here is set by IT and can change without notice.") `
            -Impact 'Any allowlisting will have to go through the IT desk.' -Data $managed
    } else {
        Out-Check -Id 'host.managed' -Area 'Machine' -Title 'Standalone / not centrally managed' `
            -Status 'Info' -Detail 'No domain, Entra ID or MDM enrolment detected.' -Data @()
    }
}

Invoke-Safe 'elevation' {
    $id  = [Security.Principal.WindowsIdentity]::GetCurrent()
    $pr  = New-Object Security.Principal.WindowsPrincipal($id)
    $elevated = $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

    # Membership survives UAC filtering; IsInRole above only reports the
    # *current* token. Someone can be an admin without running elevated.
    $inAdmins = $false
    try {
        $groups = & whoami.exe /groups 2>$null | Out-String
        $inAdmins = ($groups -match 'S-1-5-32-544')
    } catch { }

    if ($elevated) {
        Out-Check -Id 'host.admin' -Area 'Machine' -Title 'Running elevated (administrator)' `
            -Status 'Info' -Detail 'IMPORTANT: probe results will be over-optimistic. Most application-control policies exempt administrators, so re-run this WITHOUT elevation to see what a normal user gets.' `
            -Impact 'Re-run unelevated for a trustworthy verdict.' -Data @{ Elevated = $true; InAdmins = $true }
    } elseif ($inAdmins) {
        Out-Check -Id 'host.admin' -Area 'Machine' -Title 'User is a local administrator (not currently elevated)' `
            -Status 'Info' -Detail 'Machine-wide install to Program Files is possible if the user accepts a UAC prompt.' `
            -Data @{ Elevated = $false; InAdmins = $true }
    } else {
        Out-Check -Id 'host.admin' -Area 'Machine' -Title 'Standard user - no administrator rights' `
            -Status 'Info' -Detail 'Everything must work per-user. Program Files, services and drivers are out.' `
            -Impact 'Rules out any machine-wide installer.' -Data @{ Elevated = $false; InAdmins = $false }
    }
}

Invoke-Safe 'diskspace' {
    $drive = (Split-Path -Qualifier $env:LOCALAPPDATA)
    $d = Get-PSDrive -Name $drive.TrimEnd(':') -ErrorAction SilentlyContinue
    if ($d) {
        $free = [double]$d.Free
        $status = 'Pass'
        $impact = ''
        if ($free -lt $MODEL_BYTES_MIN) {
            $status = 'Fail'
            $impact = 'Not enough room for the speech models - no delivery path can work until space is freed.'
        } elseif ($free -lt $SOURCE_BYTES_MIN) {
            $status = 'Warn'
            $impact = 'Enough for an installer/portable build, tight for a from-source venv.'
        }
        Out-Check -Id 'host.disk' -Area 'Machine' -Title "Free space on $drive $(Format-Bytes $free)" `
            -Status $status `
            -Detail ("Need ~{0} for the models (small.en + boost), ~{1} for a from-source install." -f (Format-Bytes $MODEL_BYTES_MIN), (Format-Bytes $SOURCE_BYTES_MIN)) `
            -Impact $impact -Data @{ FreeBytes = $free; Drive = $drive }
    }
}

# ==============================================================================
#  2. Can scripts run at all?
# ==============================================================================

Out-Head 'Script execution (does the setup.bat path even start?)'

Invoke-Safe 'langmode' {
    $lm = $ExecutionContext.SessionState.LanguageMode
    if ($lm -eq 'FullLanguage') {
        Out-Check -Id 'ps.langmode' -Area 'Scripts' -Title "PowerShell language mode: $lm" `
            -Status 'Pass' -Detail "PowerShell $($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))" -Data "$lm"
    } else {
        Out-Check -Id 'ps.langmode' -Area 'Scripts' -Title "PowerShell language mode: $lm" `
            -Status 'Warn' `
            -Detail "Constrained Language Mode is active - usually a side effect of AppLocker/WDAC being enforced. Several probes below will be skipped and reported as Unknown." `
            -Impact 'Strong hint that application control is enforced on this machine.' -Data "$lm"
    }
}

Invoke-Safe 'execpolicy' {
    $list = Get-ExecutionPolicy -List -ErrorAction SilentlyContinue
    $eff  = Get-ExecutionPolicy -ErrorAction SilentlyContinue
    $txt  = ($list | ForEach-Object { '{0,-16} {1}' -f $_.Scope, $_.ExecutionPolicy }) -join "`n"
    # MachinePolicy/UserPolicy are the Group-Policy-set scopes. They outrank
    # -ExecutionPolicy Bypass, which is why they get their own verdict.
    $gpo  = $list |
        Where-Object { $_.Scope -in @('MachinePolicy', 'UserPolicy') -and $_.ExecutionPolicy -ne 'Undefined' } |
        Select-Object -First 1
    $status = 'Pass'
    $impact = ''
    if ($gpo -and $gpo.ExecutionPolicy -in @('AllSigned', 'Restricted')) {
        $status = 'Fail'
        $impact = "Execution policy is locked by Group Policy to '$($gpo.ExecutionPolicy)' - -ExecutionPolicy Bypass will NOT override it. Any PowerShell step must be signed."
    } elseif ($eff -in @('Restricted', 'AllSigned')) {
        $status = 'Warn'
        $impact = 'Unsigned .ps1 files need an explicit -ExecutionPolicy Bypass (allowed here, since this script is running).'
    }
    Out-Check -Id 'ps.execpolicy' -Area 'Scripts' -Title "Effective execution policy: $eff" `
        -Status $status -Detail $txt -Impact $impact -Data ($list | ForEach-Object { @{ Scope = "$($_.Scope)"; Policy = "$($_.ExecutionPolicy)" } })
}

Invoke-Safe 'batchrun' {
    # setup.bat is the entry point of the Python-from-source path, so a blocked
    # .cmd kills that path even if Python itself is present.
    $bat = Join-Path $script:Scratch 'scribe-probe.cmd'
    Set-Content -LiteralPath $bat -Value "@echo off`r`necho SCRIBE_BAT_OK" -Encoding ASCII
    $out = ''
    try { $out = & cmd.exe /c $bat 2>&1 | Out-String } catch { $out = $_.Exception.Message }
    if ($out -match 'SCRIBE_BAT_OK') {
        Out-Check -Id 'script.cmd' -Area 'Scripts' -Title 'Batch (.cmd/.bat) scripts run from a temp folder' `
            -Status 'Pass' -Detail 'setup.bat can be launched.' -Data $true
    } else {
        Out-Check -Id 'script.cmd' -Area 'Scripts' -Title 'Batch (.cmd/.bat) scripts are BLOCKED' `
            -Status 'Fail' -Detail (Get-ExecBlockReason $out) `
            -Impact 'The Python-from-source path (setup.bat) cannot start.' -Data $false
    }

    $cmdDisabled = Get-RegValue 'HKCU:\Software\Policies\Microsoft\Windows\System' 'DisableCMD'
    if ($cmdDisabled -in @(1, 2)) {
        Out-Check -Id 'script.cmddisabled' -Area 'Scripts' -Title 'Command prompt disabled by policy (DisableCMD)' `
            -Status 'Fail' -Detail "DisableCMD = $cmdDisabled" `
            -Impact 'No batch-driven setup is possible.' -Data $cmdDisabled
    }
}

# ==============================================================================
#  3. Application control - the thing that usually decides everything
# ==============================================================================

Out-Head 'Application control policy'

Invoke-Safe 'applocker' {
    $svc = Get-Service -Name AppIDSvc -ErrorAction SilentlyContinue
    $policyXml = $null
    try { $policyXml = Get-AppLockerPolicy -Effective -Xml -ErrorAction Stop } catch { }

    if (-not $policyXml) {
        Out-Check -Id 'ac.applocker' -Area 'AppControl' -Title 'AppLocker: no effective policy readable' `
            -Status 'Pass' -Detail ("AppIDSvc status: {0}" -f $(if ($svc) { $svc.Status } else { 'not present' })) -Data $null
        return
    }

    $xml = [xml]$policyXml
    $rows = @()
    $enforced = @()
    foreach ($rc in $xml.AppLockerPolicy.RuleCollection) {
        $count = 0
        try { $count = @($rc.ChildNodes).Count } catch { }
        $rows += ('{0,-8} enforcement={1,-14} rules={2}' -f $rc.Type, $rc.EnforcementMode, $count)
        if ($rc.EnforcementMode -eq 'Enabled' -and $count -gt 0) { $enforced += "$($rc.Type)" }
    }
    $detail = ($rows -join "`n")
    if ($enforced.Count -gt 0) {
        $detail += "`nEnforced collections: " + ($enforced -join ', ')
        Out-Check -Id 'ac.applocker' -Area 'AppControl' -Title 'AppLocker is ENFORCED on this machine' `
            -Status 'Warn' -Detail $detail `
            -Impact 'Execution is allowlisted. The functional probes below show exactly what survives.' `
            -Data @{ Enforced = $enforced; Rows = $rows }
    } else {
        Out-Check -Id 'ac.applocker' -Area 'AppControl' -Title 'AppLocker present but not enforcing' `
            -Status 'Pass' -Detail $detail -Data @{ Enforced = @(); Rows = $rows }
    }
}

Invoke-Safe 'wdac' {
    $dg = Get-CimInstance -ClassName Win32_DeviceGuard `
        -Namespace 'root\Microsoft\Windows\DeviceGuard' -ErrorAction SilentlyContinue
    if (-not $dg) {
        Out-Check -Id 'ac.wdac' -Area 'AppControl' -Title 'WDAC / Device Guard: not reported' `
            -Status 'Info' -Detail 'Win32_DeviceGuard unavailable (normal on Home editions).' -Data $null
        return
    }
    $map = @{ 0 = 'Off'; 1 = 'Audit only'; 2 = 'Enforced' }
    $um  = $dg.UsermodeCodeIntegrityPolicyEnforcementStatus
    $ci  = $dg.CodeIntegrityPolicyEnforcementStatus
    $umTxt = $map[[int]$um]; if (-not $umTxt) { $umTxt = "$um" }
    $ciTxt = $map[[int]$ci]; if (-not $ciTxt) { $ciTxt = "$ci" }
    $detail = "Kernel code integrity : $ciTxt`nUser-mode code integrity (UMCI): $umTxt"
    if ([int]$um -eq 2) {
        Out-Check -Id 'ac.wdac' -Area 'AppControl' -Title 'WDAC user-mode code integrity is ENFORCED' `
            -Status 'Fail' -Detail $detail `
            -Impact 'Only binaries permitted by the WDAC policy will run. An unsigned PyInstaller build is very unlikely to pass. This normally requires IT to add a signer or hash rule.' `
            -Data @{ UMCI = [int]$um; CI = [int]$ci }
    } elseif ([int]$um -eq 1) {
        Out-Check -Id 'ac.wdac' -Area 'AppControl' -Title 'WDAC user-mode code integrity in AUDIT mode' `
            -Status 'Warn' -Detail $detail `
            -Impact 'Nothing is blocked yet, but this machine is being prepared for enforcement - plan for a signed build.' `
            -Data @{ UMCI = [int]$um; CI = [int]$ci }
    } else {
        Out-Check -Id 'ac.wdac' -Area 'AppControl' -Title 'WDAC user-mode code integrity not enforced' `
            -Status 'Pass' -Detail $detail -Data @{ UMCI = [int]$um; CI = [int]$ci }
    }
}

Invoke-Safe 'smartappcontrol' {
    $sac = Get-RegValue 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' 'VerifiedAndReputablePolicyState'
    if ($null -ne $sac) {
        $txt = @{ 0 = 'Off'; 1 = 'Enforced'; 2 = 'Evaluation' }[[int]$sac]
        if (-not $txt) { $txt = "$sac" }
        $status = 'Pass'; $impact = ''
        if ([int]$sac -eq 1) {
            $status = 'Fail'
            $impact = 'Smart App Control blocks unsigned and low-reputation executables outright, and it cannot be re-enabled once turned off. An EV-signed build is effectively mandatory here.'
        } elseif ([int]$sac -eq 2) {
            $status = 'Warn'
            $impact = 'Evaluation mode may switch itself to enforced.'
        }
        Out-Check -Id 'ac.sac' -Area 'AppControl' -Title "Smart App Control: $txt" `
            -Status $status -Detail '' -Impact $impact -Data ([int]$sac)
    }
}

Invoke-Safe 'srp' {
    $srp = Get-RegValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\Safer\CodeIdentifiers' 'DefaultLevel'
    if ($null -ne $srp -and [int]$srp -eq 0) {
        Out-Check -Id 'ac.srp' -Area 'AppControl' -Title 'Software Restriction Policy set to Disallowed by default' `
            -Status 'Fail' -Detail 'HKLM\...\Safer\CodeIdentifiers\DefaultLevel = 0 (Disallowed)' `
            -Impact 'Only explicitly allowed paths may execute - typically Program Files and Windows only.' -Data 0
    } elseif ($null -ne $srp) {
        Out-Check -Id 'ac.srp' -Area 'AppControl' -Title 'Software Restriction Policy present, default = Unrestricted' `
            -Status 'Pass' -Detail "DefaultLevel = $srp" -Data ([int]$srp)
    }
}

Invoke-Safe 'smartscreen' {
    $lvl  = Get-RegValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\System' 'ShellSmartScreenLevel'
    $en   = Get-RegValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\System' 'EnableSmartScreen'
    $user = Get-RegValue 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer' 'SmartScreenEnabled'
    if ([string]$lvl -eq 'Block' -or ([string]$user -eq 'Block')) {
        Out-Check -Id 'ac.smartscreen' -Area 'AppControl' -Title 'SmartScreen set to BLOCK (no "Run anyway" escape hatch)' `
            -Status 'Fail' -Detail "policy level=$lvl  enabled=$en  explorer=$user" `
            -Impact 'A downloaded, unsigned Scribe installer cannot be run at all - the override button is removed. Ship signed, or deliver by a route that avoids Mark-of-the-Web.' `
            -Data @{ Level = "$lvl"; Enabled = "$en"; Explorer = "$user" }
    } else {
        Out-Check -Id 'ac.smartscreen' -Area 'AppControl' -Title "SmartScreen: $(if ($user) { $user } else { 'default (Warn)' })" `
            -Status 'Info' -Detail "policy level=$lvl  enabled=$en  explorer=$user" `
            -Impact 'Unsigned downloads show "Windows protected your PC" with a More info -> Run anyway override.' `
            -Data @{ Level = "$lvl"; Enabled = "$en"; Explorer = "$user" }
    }
}

# --- Defender: real-time protection, Controlled Folder Access, ASR rules ------

$script:AsrRelevant = @{
    '01443614-cd74-433a-b99e-2ecdc07bfc25' = 'Block executables unless they meet a prevalence/age/trusted-list criterion  ->  KILLS a freshly built, low-prevalence Scribe installer'
    'b2b3f03d-6a65-4f7b-a9c7-1c7ef74a9ba4' = 'Block untrusted and unsigned processes that run from USB  ->  KILLS the portable-from-USB path'
    '5beb7efe-fd9a-4556-801d-275e5ffc04cc' = 'Block execution of potentially obfuscated scripts  ->  can trip setup.bat / .ps1 steps'
    'd3e037e1-3eb8-44c8-a917-57927947596d' = 'Block JS/VBS from launching downloaded executable content'
    'd1e49aac-8f56-4280-b9ba-993a6d77406c' = 'Block process creations from PSExec and WMI commands'
    'c1db55ab-c21a-4637-bb3f-a12568109d35' = 'Advanced ransomware protection'
    '56a863a9-875e-4185-98a7-b882c64b5ce5' = 'Block abuse of exploited vulnerable signed drivers'
}

Invoke-Safe 'defender' {
    $status = $null; $pref = $null
    try { $status = Get-MpComputerStatus -ErrorAction Stop } catch { }
    try { $pref   = Get-MpPreference   -ErrorAction Stop } catch { }

    if ($status) {
        $rtp = [bool]$status.RealTimeProtectionEnabled
        Out-Check -Id 'av.defender' -Area 'Defender' -Title "Microsoft Defender real-time protection: $(if ($rtp) { 'ON' } else { 'off' })" `
            -Status $(if ($rtp) { 'Warn' } else { 'Info' }) `
            -Detail ("Antivirus signature version {0}; tamper protection {1}" -f $status.AntivirusSignatureVersion, $status.IsTamperProtected) `
            -Impact $(if ($rtp) { 'PyInstaller one-folder bundles are a well-known false-positive source. Budget for a Defender exclusion or a code-signing certificate.' } else { '' }) `
            -Data @{ RTP = $rtp }
    }

    if ($pref) {
        $cfa = [int]$pref.EnableControlledFolderAccess
        if ($cfa -eq 1) {
            Out-Check -Id 'av.cfa' -Area 'Defender' -Title 'Controlled Folder Access is ENABLED' `
                -Status 'Fail' -Detail 'Desktop, Documents, Downloads and Pictures are write-protected against unapproved apps.' `
                -Impact 'Do not unzip the portable build to Desktop/Documents. Use %LOCALAPPDATA%, and expect Scribe to need an "allowed app" entry before it can write its own data folder there.' `
                -Data $cfa
        } elseif ($cfa -eq 2) {
            Out-Check -Id 'av.cfa' -Area 'Defender' -Title 'Controlled Folder Access in audit mode' -Status 'Warn' -Data $cfa
        } else {
            Out-Check -Id 'av.cfa' -Area 'Defender' -Title 'Controlled Folder Access off' -Status 'Pass' -Data $cfa
        }

        $ids  = @($pref.AttackSurfaceReductionRules_Ids)
        $acts = @($pref.AttackSurfaceReductionRules_Actions)
        $hits = @()
        for ($i = 0; $i -lt $ids.Count; $i++) {
            $gid = "$($ids[$i])".ToLower()
            $act = 0; if ($i -lt $acts.Count) { $act = [int]$acts[$i] }
            if ($act -ne 1) { continue }   # 1 = Block; 2 = Audit; 6 = Warn
            if ($script:AsrRelevant.ContainsKey($gid)) {
                $hits += ("{0}`n    {1}" -f $gid, $script:AsrRelevant[$gid])
            }
        }
        if ($hits.Count -gt 0) {
            Out-Check -Id 'av.asr' -Area 'Defender' -Title "$($hits.Count) Attack Surface Reduction rule(s) in BLOCK mode that affect Scribe" `
                -Status 'Fail' -Detail ($hits -join "`n") `
                -Impact 'See each rule above - these are the specific exclusions to request from IT.' -Data $hits
        } else {
            Out-Check -Id 'av.asr' -Area 'Defender' -Title 'No blocking ASR rules that affect Scribe' `
                -Status 'Pass' -Detail "$($ids.Count) ASR rule(s) configured in total." -Data @()
        }
    }
}

Invoke-Safe 'edr' {
    # Third-party EDR is the most common cause of "the installer just vanished"
    # and of a blocked keyboard hook. Knowing the vendor tells you whose console
    # the exclusion has to be added in.
    $known = @{
        'CSFalconService' = 'CrowdStrike Falcon'; 'SentinelAgent' = 'SentinelOne'
        'CbDefense' = 'VMware Carbon Black'; 'CylanceSvc' = 'BlackBerry Cylance'
        'cyserver' = 'Palo Alto Cortex XDR'; 'TaniumClient' = 'Tanium'
        'SophosED' = 'Sophos Intercept X'; 'SAVService' = 'Sophos AV'
        'masvc' = 'Trellix/McAfee ePO'; 'mfemms' = 'Trellix/McAfee'
        'SepMasterService' = 'Symantec Endpoint Protection'
        'ekrn' = 'ESET'; 'TmListen' = 'Trend Micro'; 'ds_agent' = 'Trend Micro Deep Security'
        'Sense' = 'Microsoft Defender for Endpoint (EDR)'
        'ElasticEndpoint' = 'Elastic Endpoint'; 'HealthService' = 'SCOM/MMA agent'
    }
    $found = @()
    foreach ($svc in (Get-Service -ErrorAction SilentlyContinue)) {
        if ($known.ContainsKey($svc.Name) -and $svc.Status -eq 'Running') {
            $found += ('{0}  ({1})' -f $known[$svc.Name], $svc.Name)
        }
    }
    try {
        $av = Get-CimInstance -Namespace 'root\SecurityCenter2' -ClassName AntiVirusProduct -ErrorAction SilentlyContinue
        foreach ($p in $av) { $found += ('registered AV: ' + $p.displayName) }
    } catch { }
    $found = @($found | Select-Object -Unique)
    if ($found.Count -gt 0) {
        Out-Check -Id 'av.edr' -Area 'Defender' -Title 'Endpoint security agents present' `
            -Status 'Warn' -Detail ($found -join "`n") `
            -Impact 'Behavioural blocking here is invisible to policy inspection. Any allowlisting must be done in this vendor console, not just in Windows.' `
            -Data $found
    } else {
        Out-Check -Id 'av.edr' -Area 'Defender' -Title 'No third-party EDR agent detected' -Status 'Pass' -Data @()
    }
}

Invoke-Safe 'applockerevents' {
    # If our probes get blocked, these events name the culprit rule.
    $logs = @('Microsoft-Windows-AppLocker/EXE and DLL', 'Microsoft-Windows-AppLocker/MSI and Script')
    $recent = @()
    foreach ($log in $logs) {
        try {
            $ev = Get-WinEvent -FilterHashtable @{ LogName = $log; Id = 8004, 8007 } -MaxEvents 5 -ErrorAction Stop
            foreach ($e in $ev) {
                $recent += ('{0:yyyy-MM-dd HH:mm}  {1}  {2}' -f $e.TimeCreated, $e.Id, ($e.Message -replace "`r?`n", ' ').Substring(0, [Math]::Min(140, $e.Message.Length)))
            }
        } catch { }
    }
    if ($recent.Count -gt 0) {
        Out-Check -Id 'ac.applockerlog' -Area 'AppControl' -Title 'Recent AppLocker block events on this machine' `
            -Status 'Warn' -Detail ($recent -join "`n") `
            -Impact 'Confirms application control is actively blocking things here.' -Data $recent
    }
}

# ==============================================================================
#  4. Write + execute, folder by folder. The decisive section.
# ==============================================================================

Out-Head 'Write and execute probes (the real test)'

$probe = New-ProbeExe -Dir $script:Scratch
if ($probe.UnsignedPath) {
    Out-Status 'Info' 'Compiled a genuinely unsigned test executable for the probes below.'
} else {
    Out-Status 'Warn' 'No unsigned test exe available; falling back to a Microsoft-signed binary.' $probe.UnsignedNote
    Add-Check -Id 'probe.unsigned' -Area 'Execute' -Title 'Unsigned-executable verdict is inferred, not measured' `
        -Status 'Unknown' -Detail $probe.UnsignedNote `
        -Impact 'A publisher-rule policy could still block Scribe even where these probes pass. Re-run under Windows PowerShell 5.1 for certainty.'
}

$targets = New-Object System.Collections.Generic.List[object]
$targets.Add(@{ Id = 'localappdata_programs'; Path = (Join-Path $env:LOCALAPPDATA 'Programs'); Label = '%LOCALAPPDATA%\Programs  (the EXE installer target)' })
$targets.Add(@{ Id = 'localappdata';          Path = $env:LOCALAPPDATA;                        Label = '%LOCALAPPDATA%           (portable ZIP, recommended)' })
$targets.Add(@{ Id = 'appdata';               Path = $env:APPDATA;                             Label = '%APPDATA%                (Scribe config.toml lives here)' })
$targets.Add(@{ Id = 'temp';                  Path = $env:TEMP;                                Label = '%TEMP%                   (installer + pip staging)' })
$targets.Add(@{ Id = 'desktop';               Path = [Environment]::GetFolderPath('Desktop');  Label = 'Desktop                  (where users unzip things)' })
$targets.Add(@{ Id = 'downloads';             Path = (Join-Path $env:USERPROFILE 'Downloads'); Label = 'Downloads                (where the installer lands)' })
$targets.Add(@{ Id = 'userprofile';           Path = $env:USERPROFILE;                         Label = '%USERPROFILE%' })
$targets.Add(@{ Id = 'programfiles';          Path = $env:ProgramFiles;                        Label = 'Program Files            (machine-wide install, needs admin)' })

Invoke-Safe 'removable' {
    $rm = Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=2' -ErrorAction SilentlyContinue
    foreach ($d in $rm) {
        $targets.Add(@{ Id = ('removable_' + $d.DeviceID.TrimEnd(':')); Path = ($d.DeviceID + '\'); Label = ("Removable drive $($d.DeviceID)      (portable-from-USB path)") })
    }
    if (-not $rm) {
        Out-Check -Id 'exec.removable_none' -Area 'Execute' -Title 'No removable drive attached - USB path not measured' `
            -Status 'Skip' -Detail 'Plug in a USB stick and re-run if you intend to ship the portable build on one.' -Data $null
    }
}

foreach ($t in $targets) {
    Invoke-Safe ('probe-' + $t.Id) {
        if (-not $t.Path) { return }
        Out-Line ''
        Out-Line ('  ' + $t.Label) 'White'

        $w = Test-DirWrite -Dir $t.Path
        if ($w.Ok) {
            Out-Check -Id ('write.' + $t.Id) -Area 'Write' -Title 'writable' -Status 'Pass' -Data $true
        } else {
            Out-Check -Id ('write.' + $t.Id) -Area 'Write' -Title 'NOT writable' -Status 'Fail' `
                -Detail $w.Error -Impact "Nothing can be deployed to $($t.Path)." -Data $false
            return   # no point testing execute where we cannot even write
        }

        $src = $probe.UnsignedPath
        $tok = $probe.Token
        $kind = 'unsigned exe'
        if (-not $src) { $src = $probe.SignedPath; $tok = $env:COMPUTERNAME; $kind = 'Microsoft-signed exe' }

        if (-not $src) {
            # Neither a compiled nor a system binary was available to test with.
            # That is "not measured", not "blocked" - do not let it poison the
            # verdict engine into declaring a working machine unusable.
            Out-Check -Id ('exec.' + $t.Id) -Area 'Execute' -Title 'execution not measured (no test binary available)' `
                -Status 'Unknown' -Detail $probe.UnsignedNote -Data $null
            return
        }

        $x = Test-DirExecute -Dir $t.Path -SourceExe $src -ExpectToken $tok
        if ($x.Ok) {
            Out-Check -Id ('exec.' + $t.Id) -Area 'Execute' -Title "executes ($kind)" -Status 'Pass' -Data $true
        } else {
            Out-Check -Id ('exec.' + $t.Id) -Area 'Execute' -Title "execution BLOCKED ($kind)" -Status 'Fail' `
                -Detail $x.Reason -Impact "No Scribe build can run from $($t.Path)." -Data $false
        }

        # For the two folders that decide the whole question, dig deeper:
        # does a Microsoft-signed binary fare better than our unsigned one, and
        # does Mark-of-the-Web change the answer?
        if ($t.Id -in @('localappdata_programs', 'desktop', 'downloads') -and $probe.UnsignedPath -and $probe.SignedPath) {
            if (-not $x.Ok) {
                $s = Test-DirExecute -Dir $t.Path -SourceExe $probe.SignedPath -ExpectToken $env:COMPUTERNAME
                if ($s.Ok) {
                    Out-Check -Id ('execsigned.' + $t.Id) -Area 'Execute' -Title 'but a Microsoft-SIGNED exe runs from here' `
                        -Status 'Warn' -Detail 'The policy is publisher-based, not path-based.' `
                        -Impact 'Code signing would unblock this location. An EV certificate is the fix, not a different folder.' -Data $true
                } else {
                    Out-Check -Id ('execsigned.' + $t.Id) -Area 'Execute' -Title 'a signed exe is blocked here too' `
                        -Status 'Fail' -Detail $s.Reason `
                        -Impact 'Path-based blocking: signing will not help for this folder. Another location or an IT path rule is needed.' -Data $false
                }
            }
            if ($x.Ok) {
                $m = Test-DirExecute -Dir $t.Path -SourceExe $probe.UnsignedPath -ExpectToken $tok -MarkOfTheWeb
                if ($m.Ok) {
                    Out-Check -Id ('execmotw.' + $t.Id) -Area 'Execute' -Title 'still executes when marked as downloaded from the internet' `
                        -Status 'Pass' -Detail 'Mark-of-the-Web does not block execution here.' -Data $true
                } else {
                    Out-Check -Id ('execmotw.' + $t.Id) -Area 'Execute' -Title 'BLOCKED once marked as downloaded from the internet' `
                        -Status 'Fail' -Detail $m.Reason `
                        -Impact 'Direct download will fail. Deliver via a channel that strips Mark-of-the-Web (internal file share, SCCM/Intune, or an IT-unblocked copy).' -Data $false
                }
            }
        }
    }
}

# ==============================================================================
#  5. Registry, shortcuts, autostart
# ==============================================================================

Out-Head 'Installer side effects (registry, shortcuts, autostart)'

Invoke-Safe 'runkey' {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
    $name = 'ScribeEnvCheckProbe'
    $ok = $false; $err = $null
    try {
        New-ItemProperty -Path $key -Name $name -Value 'probe' -PropertyType String -Force -ErrorAction Stop | Out-Null
        $ok = ((Get-RegValue $key $name) -eq 'probe')
        Remove-ItemProperty -Path $key -Name $name -Force -ErrorAction SilentlyContinue
    } catch { $err = $_.Exception.Message }

    if ($ok) {
        Out-Check -Id 'reg.runkey' -Area 'Autostart' -Title 'HKCU Run key is writable' -Status 'Pass' `
            -Detail 'The installer''s "start at sign-in" option will work.' -Data $true
    } else {
        Out-Check -Id 'reg.runkey' -Area 'Autostart' -Title 'HKCU Run key is NOT writable' -Status 'Fail' `
            -Detail $err -Impact 'Autostart must fall back to a Startup-folder shortcut or a scheduled task.' -Data $false
    }

    foreach ($hive in @('HKCU', 'HKLM')) {
        $p = "${hive}:\Software\Microsoft\Windows\CurrentVersion\Policies\Explorer"
        foreach ($v in @('DisableCurrentUserRun', 'DisableLocalMachineRun')) {
            $val = Get-RegValue $p $v
            if ($val -eq 1) {
                Out-Check -Id ("reg.policy.$hive.$v") -Area 'Autostart' -Title "Policy $v is set under $hive" `
                    -Status 'Fail' -Detail "$p\$v = 1" `
                    -Impact 'Run-key autostart entries are ignored even if the key can be written. Use the Startup folder or a scheduled task.' -Data 1
            }
        }
    }
}

Invoke-Safe 'uninstallkey' {
    # Plain concatenation, not Join-Path: Join-Path validates the drive and
    # emits its own noisy error where no HKCU: provider exists.
    $sub = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\ScribeEnvCheckProbe'
    try {
        New-Item -Path $sub -Force -ErrorAction Stop | Out-Null
        Remove-Item -Path $sub -Force -Recurse -ErrorAction SilentlyContinue
        Out-Check -Id 'reg.uninstall' -Area 'Autostart' -Title 'Per-user Uninstall registry branch is writable' -Status 'Pass' `
            -Detail 'The Inno Setup installer can register itself in Apps & features.' -Data $true
    } catch {
        Out-Check -Id 'reg.uninstall' -Area 'Autostart' -Title 'Per-user Uninstall branch is NOT writable' -Status 'Fail' `
            -Detail $_.Exception.Message `
            -Impact 'The EXE installer cannot register an uninstall entry. Prefer the portable build.' -Data $false
    }
}

Invoke-Safe 'shortcuts' {
    $places = @(
        @{ Id = 'startmenu'; Path = [Environment]::GetFolderPath('Programs'); Label = 'Start-menu Programs folder' }
        @{ Id = 'startup';   Path = [Environment]::GetFolderPath('Startup');  Label = 'Startup folder (autostart fallback)' }
        @{ Id = 'desktoplnk';Path = [Environment]::GetFolderPath('Desktop');   Label = 'Desktop (shortcut)' }
    )
    foreach ($p in $places) {
        if (-not $p.Path) { continue }
        $lnk = Join-Path $p.Path ('ScribeEnvCheckProbe-' + [Guid]::NewGuid().ToString('N').Substring(0,6) + '.lnk')
        try {
            $sh = New-Object -ComObject WScript.Shell -ErrorAction Stop
            $s  = $sh.CreateShortcut($lnk)
            $s.TargetPath = (Join-Path $env:SystemRoot 'System32\notepad.exe')
            $s.Save()
            $made = Test-Path -LiteralPath $lnk
            Remove-Item -LiteralPath $lnk -Force -ErrorAction SilentlyContinue
            if ($made) {
                Out-Check -Id ('lnk.' + $p.Id) -Area 'Autostart' -Title ("$($p.Label): shortcut created OK") -Status 'Pass' -Data $true
            } else {
                Out-Check -Id ('lnk.' + $p.Id) -Area 'Autostart' -Title ("$($p.Label): shortcut creation failed") -Status 'Fail' -Data $false
            }
        } catch {
            Out-Check -Id ('lnk.' + $p.Id) -Area 'Autostart' -Title ("$($p.Label): shortcut creation BLOCKED") -Status 'Fail' `
                -Detail $_.Exception.Message `
                -Impact 'Constrained Language Mode or COM policy. The installer''s shortcut step will fail; ship a portable build the user launches directly.' -Data $false
        }
    }
}

Invoke-Safe 'schtask' {
    $name = 'ScribeEnvCheckProbe'
    $out = ''
    try {
        $out = & schtasks.exe /Create /TN $name /TR "$env:SystemRoot\System32\notepad.exe" /SC ONCE /ST 23:59 /F 2>&1 | Out-String
        $exists = ((& schtasks.exe /Query /TN $name 2>&1 | Out-String) -match [regex]::Escape($name))
        & schtasks.exe /Delete /TN $name /F 2>&1 | Out-Null
        if ($exists) {
            Out-Check -Id 'task.create' -Area 'Autostart' -Title 'Scheduled tasks can be created by this user' -Status 'Pass' `
                -Detail 'A logon-triggered task is a viable autostart fallback if the Run key is blocked.' -Data $true
        } else {
            Out-Check -Id 'task.create' -Area 'Autostart' -Title 'Scheduled task creation failed' -Status 'Fail' `
                -Detail ($out.Trim()) -Impact 'Not available as an autostart fallback.' -Data $false
        }
    } catch {
        Out-Check -Id 'task.create' -Area 'Autostart' -Title 'Scheduled task creation BLOCKED' -Status 'Fail' `
            -Detail $_.Exception.Message -Data $false
    }
}

# ==============================================================================
#  6. Python-from-source path
# ==============================================================================

Out-Head 'Python (the from-source / setup.bat path)'

$script:PythonExe = $null

Invoke-Safe 'python' {
    $cands = @()
    foreach ($name in @('python.exe', 'python3.exe', 'py.exe')) {
        try {
            foreach ($c in (Get-Command $name -All -ErrorAction SilentlyContinue)) {
                if ($c.Source) { $cands += $c.Source }
            }
        } catch { }
    }
    $cands = @($cands | Select-Object -Unique)

    if ($cands.Count -eq 0) {
        Out-Check -Id 'py.present' -Area 'Python' -Title 'No Python interpreter on PATH' -Status 'Fail' `
            -Detail 'Neither python.exe, python3.exe nor py.exe was found.' `
            -Impact 'The from-source path requires installing Python first - itself often blocked. Ship a frozen build.' -Data @()
        return
    }

    $good = @()
    $rows = @()
    foreach ($c in $cands) {
        # The Microsoft Store "app execution alias" is a zero-length reparse
        # point that opens the Store instead of running Python. It looks like a
        # working interpreter on PATH and is not one.
        $len = 0
        try { $len = (Get-Item -LiteralPath $c -ErrorAction Stop).Length } catch { }
        if ($c -like '*\WindowsApps\*' -and $len -eq 0) {
            $rows += "$c  ->  Microsoft Store alias stub (opens the Store, not a real Python)"
            continue
        }
        $ver = ''
        try { $ver = (& $c --version 2>&1 | Out-String).Trim() } catch { $ver = 'failed to launch: ' + $_.Exception.Message }
        $rows += "$c  ->  $ver"
        if ($ver -match 'Python (\d+)\.(\d+)') {
            $maj = [int]$Matches[1]; $min = [int]$Matches[2]
            if ($maj -eq 3 -and $min -ge 10) { $good += $c }
        }
    }

    if ($good.Count -gt 0) {
        $script:PythonExe = $good[0]
        Out-Check -Id 'py.present' -Area 'Python' -Title "Usable Python found: $($script:PythonExe)" -Status 'Pass' `
            -Detail ($rows -join "`n") -Data $good
    } else {
        Out-Check -Id 'py.present' -Area 'Python' -Title 'Python present but not usable (needs 3.10+, or Store alias only)' -Status 'Fail' `
            -Detail ($rows -join "`n") -Impact 'setup.bat will stop at its Python check.' -Data @()
    }
}

Invoke-Safe 'venv' {
    if (-not $script:PythonExe) { return }
    if ($Quick) {
        Out-Check -Id 'py.venv' -Area 'Python' -Title 'venv creation not tested (-Quick)' -Status 'Skip' -Data $null
        return
    }
    $venv = Join-Path $script:Scratch 'venvprobe'
    $out = ''
    try { $out = & $script:PythonExe -m venv $venv 2>&1 | Out-String } catch { $out = $_.Exception.Message }
    $vpy = Join-Path $venv 'Scripts\python.exe'
    if (Test-Path -LiteralPath $vpy) {
        # Creating the venv is not enough - the copied interpreter has to be
        # allowed to execute from %TEMP%, which is exactly what AppLocker's
        # default rules forbid.
        $ran = ''
        try { $ran = (& $vpy -c "print('SCRIBE_VENV_OK')" 2>&1 | Out-String) } catch { $ran = $_.Exception.Message }
        if ($ran -match 'SCRIBE_VENV_OK') {
            Out-Check -Id 'py.venv' -Area 'Python' -Title 'Virtual environment created and its python.exe runs' -Status 'Pass' `
                -Detail 'setup.bat''s environment step will succeed.' -Data $true
        } else {
            Out-Check -Id 'py.venv' -Area 'Python' -Title 'venv created but its copied python.exe is BLOCKED' -Status 'Fail' `
                -Detail (Get-ExecBlockReason $ran) `
                -Impact 'Classic AppLocker signature: the venv interpreter is a copy in a user-writable path. The from-source path is dead here.' -Data $false
        }
    } else {
        Out-Check -Id 'py.venv' -Area 'Python' -Title 'Virtual environment could NOT be created' -Status 'Fail' `
            -Detail ($out.Trim()) -Impact 'The from-source path cannot proceed.' -Data $false
    }
    Remove-Item -LiteralPath $venv -Recurse -Force -ErrorAction SilentlyContinue
}

Invoke-Safe 'pipconfig' {
    if (-not $script:PythonExe) { return }
    $notes = @()
    foreach ($v in @('PIP_INDEX_URL', 'PIP_EXTRA_INDEX_URL', 'PIP_TRUSTED_HOST', 'PIP_CERT')) {
        $val = [Environment]::GetEnvironmentVariable($v)
        if ($val) { $notes += "$v = $val" }
    }
    foreach ($f in @((Join-Path $env:APPDATA 'pip\pip.ini'), (Join-Path $env:ProgramData 'pip\pip.ini'))) {
        if (Test-Path -LiteralPath $f) { $notes += "config file: $f" }
    }
    if ($notes.Count -gt 0) {
        Out-Check -Id 'py.pipconfig' -Area 'Python' -Title 'pip is pointed at a custom/internal index' -Status 'Info' `
            -Detail ($notes -join "`n") `
            -Impact 'Good news: an internal mirror usually means pip works where raw pypi.org does not. Verify Scribe''s dependencies are mirrored there.' -Data $notes
    }
}

# ==============================================================================
#  7. Network (pip + the one-time model download)
# ==============================================================================

Out-Head 'Network reachability (pip and the one-time model download)'

$script:PublicCaHints = @(
    'DigiCert', "Let's Encrypt", 'ISRG', 'Google Trust Services', 'GTS',
    'Amazon', 'Sectigo', 'GlobalSign', 'Baltimore', 'Cloudflare',
    'Microsoft Azure', 'Entrust', 'Comodo', 'USERTrust', 'Starfield', 'GoDaddy'
)

function Test-Endpoint {
    param([string] $HostName, [int] $Port = 443, [int] $TimeoutMs = 5000)
    $r = [pscustomobject]@{
        Host = $HostName; Reachable = $false; Error = $null
        Issuer = $null; Intercepted = $false
    }
    $client = $null; $ssl = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) {
            $r.Error = "timed out after ${TimeoutMs}ms"
            return $r
        }
        $client.EndConnect($iar)
        $r.Reachable = $true

        # Accept any certificate: we are inspecting the chain, not trusting it.
        $cb = [System.Net.Security.RemoteCertificateValidationCallback] { param($a, $b, $c, $d) $true }
        $ssl = New-Object System.Net.Security.SslStream($client.GetStream(), $false, $cb)
        # Pin TLS 1.2 explicitly: SslProtocols.Default on .NET Framework is
        # SSL3|TLS1.0, which every host in the list below now refuses, so the
        # handshake would fail for reasons that have nothing to do with policy.
        $ssl.AuthenticateAsClient($HostName, $null,
            [System.Security.Authentication.SslProtocols]::Tls12, $false)
        $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]$ssl.RemoteCertificate
        $r.Issuer = $cert.Issuer

        # TLS interception: the certificate the proxy presents is not issued by
        # a public CA. This is the single most common reason pip and
        # huggingface_hub fail on a corporate network with an SSL error, while
        # a browser on the same machine works fine.
        $known = $false
        foreach ($hint in $script:PublicCaHints) {
            if ($cert.Issuer -like "*$hint*") { $known = $true; break }
        }
        $r.Intercepted = -not $known
    } catch {
        $r.Error = $_.Exception.Message
    } finally {
        if ($ssl) { try { $ssl.Dispose() } catch { } }
        if ($client) { try { $client.Close() } catch { } }
    }
    return $r
}

Invoke-Safe 'proxy' {
    $notes = @()
    try { $notes += ((& netsh.exe winhttp show proxy 2>$null | Out-String).Trim()) } catch { }
    $ie = Get-RegValue 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' 'ProxyServer'
    $pac = Get-RegValue 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' 'AutoConfigURL'
    if ($ie)  { $notes += "WinINET proxy: $ie" }
    if ($pac) { $notes += "PAC script  : $pac" }
    foreach ($v in @('HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY')) {
        $val = [Environment]::GetEnvironmentVariable($v)
        if ($val) { $notes += "$v = $val" }
    }
    $hasProxy = ($ie -or $pac -or $env:HTTPS_PROXY)
    Out-Check -Id 'net.proxy' -Area 'Network' -Title $(if ($hasProxy) { 'Corporate proxy is configured' } else { 'No explicit proxy configuration' }) `
        -Status 'Info' -Detail (($notes | Where-Object { $_ }) -join "`n") `
        -Impact $(if ($pac) { 'A PAC script is respected by browsers but NOT by pip or huggingface_hub - those need HTTPS_PROXY set explicitly.' } else { '' }) `
        -Data $notes
}

if ($SkipNetwork) {
    Out-Check -Id 'net.skip' -Area 'Network' -Title 'Network probes skipped (-Quick / -SkipNetwork)' -Status 'Skip' -Data $null
} else {
    $endpoints = @(
        @{ Host = 'pypi.org';                  Why = 'pip package index' }
        @{ Host = 'files.pythonhosted.org';    Why = 'pip wheel downloads' }
        @{ Host = 'huggingface.co';            Why = 'speech model download (first run)' }
        @{ Host = 'cdn-lfs.huggingface.co';    Why = 'speech model binary CDN' }
        @{ Host = 'github.com';                Why = 'downloading the release itself' }
        @{ Host = 'objects.githubusercontent.com'; Why = 'GitHub release asset CDN' }
    )
    # script-scoped: the probes below run in child scopes, where a plain
    # `$intercepted +=` would silently write to a throwaway local copy.
    $script:Intercepted = @()
    foreach ($e in $endpoints) {
        Invoke-Safe ('net-' + $e.Host) {
            $res = Test-Endpoint -HostName $e.Host
            if ($res.Reachable -and $res.Issuer) {
                if ($res.Intercepted) { $script:Intercepted += $e.Host }
                Out-Check -Id ('net.' + $e.Host) -Area 'Network' -Title "$($e.Host) reachable  ($($e.Why))" `
                    -Status $(if ($res.Intercepted) { 'Warn' } else { 'Pass' }) `
                    -Detail ("issuer: {0}{1}" -f $res.Issuer, $(if ($res.Intercepted) { "`nNOT a public CA -> TLS is being intercepted" } else { '' })) `
                    -Data @{ Reachable = $true; Issuer = $res.Issuer; Intercepted = $res.Intercepted }
            } elseif ($res.Reachable) {
                Out-Check -Id ('net.' + $e.Host) -Area 'Network' -Title "$($e.Host) TCP open but TLS handshake failed" `
                    -Status 'Warn' -Detail $res.Error -Data @{ Reachable = $true }
            } else {
                Out-Check -Id ('net.' + $e.Host) -Area 'Network' -Title "$($e.Host) UNREACHABLE  ($($e.Why))" `
                    -Status 'Fail' -Detail $res.Error `
                    -Impact "Blocked by proxy/firewall." -Data @{ Reachable = $false }
            }
        }
    }
    if ($script:Intercepted.Count -gt 0) {
        Out-Check -Id 'net.tlsintercept' -Area 'Network' -Title 'TLS inspection detected on the endpoints Scribe needs' `
            -Status 'Warn' -Detail ('Affected: ' + ($script:Intercepted -join ', ')) `
            -Impact 'pip and huggingface_hub validate against their own bundled CA list and will fail with SSL errors here even though browsers work. Fix by setting REQUESTS_CA_BUNDLE / SSL_CERT_FILE to the corporate root, or by pre-seeding models and wheels so no download is needed.' `
            -Data $script:Intercepted
    }

    Invoke-Safe 'pipdownload' {
        # The definitive end-to-end test: does pip actually get a package through
        # this network, with this proxy, past this TLS interception?
        if (-not $script:PythonExe) { return }
        $dest = Join-Path $script:Scratch 'pipprobe'
        $out = ''
        try {
            $out = & $script:PythonExe -m pip download --no-deps --disable-pip-version-check `
                       --timeout 20 --dest $dest 'six' 2>&1 | Out-String
        } catch { $out = $_.Exception.Message }
        $got = @(Get-ChildItem -LiteralPath $dest -Filter '*.whl' -ErrorAction SilentlyContinue).Count
        if ($got -gt 0) {
            Out-Check -Id 'net.pip' -Area 'Network' -Title 'pip successfully downloaded a package end to end' -Status 'Pass' `
                -Detail 'Dependencies can be installed from source on this machine.' -Data $true
        } else {
            $why = 'pip download failed.'
            if ($out -match 'CERTIFICATE_VERIFY_FAILED|SSLError|SSLCertVerificationError') {
                $why = 'pip failed with an SSL certificate error - this is the TLS-interception symptom.'
            } elseif ($out -match 'ProxyError|Tunnel connection failed|407') {
                $why = 'pip failed at the proxy (authentication or CONNECT refused).'
            } elseif ($out -match 'Temporary failure in name resolution|getaddrinfo') {
                $why = 'DNS resolution failed for the package index.'
            }
            Out-Check -Id 'net.pip' -Area 'Network' -Title 'pip CANNOT download packages' -Status 'Fail' `
                -Detail ($why + "`n" + ($out.Trim() -split "`r?`n" | Select-Object -Last 6 | Out-String).Trim()) `
                -Impact 'The from-source path needs an offline wheelhouse, or a frozen build instead.' -Data $false
        }
        Remove-Item -LiteralPath $dest -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ==============================================================================
#  8. Will Scribe actually WORK once installed?
# ==============================================================================

Out-Head 'Runtime capability (does Scribe function after it is installed?)'

Invoke-Safe 'microphone' {
    $policy = Get-RegValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\AppPrivacy' 'LetAppsAccessMicrophone'
    if ($policy -eq 2) {
        Out-Check -Id 'run.micpolicy' -Area 'Runtime' -Title 'Microphone access is DENIED by Group Policy' -Status 'Fail' `
            -Detail 'LetAppsAccessMicrophone = 2 (Force Deny)' `
            -Impact 'Scribe cannot record. This is a hard blocker on every delivery path and must be resolved with IT before anything else.' -Data 2
    } else {
        $consent = Get-RegValue 'HKCU:\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone' 'Value'
        $nonPkg  = Get-RegValue 'HKCU:\Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone\NonPackaged' 'Value'
        $bad = ($consent -eq 'Deny') -or ($nonPkg -eq 'Deny')
        Out-Check -Id 'run.micpolicy' -Area 'Runtime' -Title $(if ($bad) { 'Microphone access is turned off for desktop apps' } else { 'Microphone access permitted' }) `
            -Status $(if ($bad) { 'Fail' } else { 'Pass' }) `
            -Detail "policy=$policy  user consent=$consent  desktop-apps=$nonPkg" `
            -Impact $(if ($bad) { 'User-fixable: Settings > Privacy & security > Microphone > "Let desktop apps access your microphone".' } else { '' }) `
            -Data @{ Policy = "$policy"; Consent = "$consent"; NonPackaged = "$nonPkg" }
    }

    $devs = @()
    try {
        $devs = @(Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture' -ErrorAction Stop |
            ForEach-Object {
                $st = Get-RegValue $_.PSPath 'DeviceState'
                $nm = $null
                try { $nm = (Get-ItemProperty -LiteralPath (Join-Path $_.PSPath 'Properties') -ErrorAction Stop).'{a45c254e-df1c-4efd-8020-67d146a850e0},2' } catch { }
                if ($st -eq 1 -and $nm) { $nm }
            } | Where-Object { $_ })
    } catch { }
    if ($devs.Count -gt 0) {
        Out-Check -Id 'run.micdevice' -Area 'Runtime' -Title "$($devs.Count) active recording device(s)" -Status 'Pass' `
            -Detail (($devs | Select-Object -First 5) -join "`n") -Data $devs
    } else {
        Out-Check -Id 'run.micdevice' -Area 'Runtime' -Title 'No active recording device detected' -Status 'Warn' `
            -Detail 'None found in MMDevices\Audio\Capture with DeviceState=1.' `
            -Impact 'A headset may simply not be plugged in - confirm with the user before treating this as a blocker.' -Data @()
    }
}

Invoke-Safe 'keyboardhook' {
    if (-not $TestInputHooks) {
        Out-Check -Id 'run.hook' -Area 'Runtime' -Title 'Global keyboard hook: NOT TESTED' -Status 'Unknown' `
            -Detail 'Re-run with -TestInputHooks to measure this.' `
            -Impact 'Scribe''s push-to-talk needs a WH_KEYBOARD_LL hook. Some EDR products block that as keylogger-like behaviour, which would make Scribe non-functional on every delivery path. This is the single most important unknown left.' `
            -Data $null
        return
    }
    if ($ExecutionContext.SessionState.LanguageMode -ne 'FullLanguage') {
        Out-Check -Id 'run.hook' -Area 'Runtime' -Title 'Global keyboard hook: cannot test (Constrained Language Mode)' -Status 'Unknown' `
            -Detail 'P/Invoke is unavailable in CLM.' -Data $null
        return
    }
    try {
        $sig = @'
using System;
using System.Runtime.InteropServices;
public class ScribeHookProbe {
    public delegate IntPtr Proc(int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern IntPtr SetWindowsHookEx(int idHook, Proc lpfn, IntPtr hMod, uint dwThreadId);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool UnhookWindowsHookEx(IntPtr hhk);
    [DllImport("user32.dll")]
    public static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")]
    public static extern IntPtr GetModuleHandle(string name);
}
'@
        Add-Type -TypeDefinition $sig -ErrorAction Stop
        # Pass-through callback: forwards every event untouched, inspects nothing.
        $cb = [ScribeHookProbe+Proc] {
            param($nCode, $wParam, $lParam)
            [ScribeHookProbe]::CallNextHookEx([IntPtr]::Zero, $nCode, $wParam, $lParam)
        }
        $h = [ScribeHookProbe]::SetWindowsHookEx(13, $cb, [ScribeHookProbe]::GetModuleHandle($null), 0)
        $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        if ($h -ne [IntPtr]::Zero) {
            Start-Sleep -Milliseconds 100
            [void][ScribeHookProbe]::UnhookWindowsHookEx($h)
            Out-Check -Id 'run.hook' -Area 'Runtime' -Title 'Global keyboard hook can be installed' -Status 'Pass' `
                -Detail 'Push-to-talk will work (subject to EDR behavioural rules that only trigger on sustained use).' -Data $true
        } else {
            Out-Check -Id 'run.hook' -Area 'Runtime' -Title 'Global keyboard hook was REFUSED' -Status 'Fail' `
                -Detail "SetWindowsHookEx(WH_KEYBOARD_LL) failed, Win32 error $err" `
                -Impact 'Scribe''s push-to-talk cannot work on this machine regardless of how it is installed. This needs an EDR exception before any delivery path is worth attempting.' -Data $false
        }
    } catch {
        Out-Check -Id 'run.hook' -Area 'Runtime' -Title 'Global keyboard hook probe failed to run' -Status 'Unknown' `
            -Detail $_.Exception.Message -Data $null
    }
}

Invoke-Safe 'uipi' {
    Out-Check -Id 'run.uipi' -Area 'Runtime' -Title 'Text injection into elevated windows (informational)' -Status 'Info' `
        -Detail 'Windows User Interface Privilege Isolation blocks synthetic keystrokes from a normal-privilege app into an elevated one.' `
        -Impact 'Dictation will not type into apps running as administrator. Expected behaviour, not a misconfiguration; mention it in user docs.' -Data $null
}

Invoke-Safe 'longpaths' {
    $lp = Get-RegValue 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' 'LongPathsEnabled'
    if ($lp -ne 1) {
        Out-Check -Id 'run.longpaths' -Area 'Runtime' -Title 'Long path support (>260 chars) is off' -Status 'Info' `
            -Detail 'LongPathsEnabled is not 1.' `
            -Impact 'Install Scribe to a short path. A deep user-profile path plus the HuggingFace cache layout can exceed MAX_PATH.' -Data 0
    } else {
        Out-Check -Id 'run.longpaths' -Area 'Runtime' -Title 'Long path support enabled' -Status 'Pass' -Data 1
    }
}

Invoke-Safe 'arm' {
    $arch = Get-Check 'host.arch'
    if ($arch -and $arch.Data.IsArm) {
        $npu = @()
        try {
            $npu = @(Get-CimInstance Win32_PnPEntity -ErrorAction Stop |
                Where-Object { $_.Name -match 'Hexagon|NPU|Neural|Qualcomm.*AI' } |
                Select-Object -ExpandProperty Name -Unique)
        } catch { }
        if ($npu.Count -gt 0) {
            Out-Check -Id 'run.npu' -Area 'Runtime' -Title 'Qualcomm NPU present' -Status 'Pass' `
                -Detail ($npu -join "`n") -Impact 'Ship the arm64 build; the ONNX/QNN backend can offload the encoder.' -Data $npu
        } else {
            Out-Check -Id 'run.npu' -Area 'Runtime' -Title 'ARM64 machine, no NPU device detected' -Status 'Warn' `
                -Detail 'Ship the arm64 build anyway - it falls back to the ARM64 CPU.' -Data @()
        }
    }
}

# ==============================================================================
#  9. Verdict per delivery path
# ==============================================================================

Out-Head 'VERDICT - what to ship for this machine'

function New-Verdict {
    param([string] $Name, [string] $Artifact)
    return [pscustomobject]@{
        Name = $Name; Artifact = $Artifact
        Status = 'GO'; Blockers = @(); Caveats = @(); Asks = @()
    }
}

function Add-Blocker { param($V, [string] $T) $V.Blockers += $T; $V.Status = 'BLOCKED' }
function Add-Caveat  { param($V, [string] $T) $V.Caveats  += $T; if ($V.Status -eq 'GO') { $V.Status = 'GO WITH CAVEATS' } }
function Add-Ask     { param($V, [string] $T) $V.Asks     += $T }

$verdicts = New-Object System.Collections.Generic.List[object]

$arch = Get-Check 'host.arch'
$archSuffix = 'x64'
if ($arch -and $arch.Data.IsArm) { $archSuffix = 'arm64' }

# ---- Path 1: per-user EXE installer -----------------------------------------
$v = New-Verdict 'Per-user EXE installer (Inno Setup)' "Scribe-Setup-$archSuffix.exe"
if (Test-CheckStatus 'write.localappdata_programs' 'Fail') { Add-Blocker $v 'Cannot write to %LOCALAPPDATA%\Programs - the installer''s target directory.' }
if (Test-CheckStatus 'exec.localappdata_programs' 'Fail')  { Add-Blocker $v 'Executables are blocked from %LOCALAPPDATA%\Programs by application control.' }
if (Test-CheckStatus 'execmotw.downloads' 'Fail')          { Add-Blocker $v 'A downloaded (Mark-of-the-Web) executable will not run from Downloads.' }
if (Test-CheckStatus 'ac.sac' 'Fail')                      { Add-Blocker $v 'Smart App Control blocks unsigned executables and cannot be relaxed per-app.' }
if (Test-CheckStatus 'ac.wdac' 'Fail')                     { Add-Blocker $v 'WDAC user-mode code integrity is enforced; an unsigned bundle will not load.' }
if (Test-CheckStatus 'ac.smartscreen' 'Fail')              { Add-Blocker $v 'SmartScreen is set to Block with no override, so an unsigned download cannot be launched.' }
if (Test-CheckStatus 'exec.localappdata_programs' 'Unknown') { Add-Caveat $v 'Execution from the install directory was NOT verified (no test binary could be produced). Re-run under Windows PowerShell 5.1 before trusting this verdict.' }
if (Test-CheckStatus 'execsigned.localappdata_programs' 'Warn') { Add-Ask $v 'Policy is publisher-based: an EV code-signing certificate would unblock this path outright.' }
if (Test-CheckStatus 'reg.runkey' 'Fail')                  { Add-Caveat $v 'Run-key autostart will fail - build the installer to drop a Startup-folder shortcut instead.' }
if (Test-CheckStatus 'reg.uninstall' 'Fail')               { Add-Caveat $v 'No Add/Remove Programs entry can be registered.' }
if (Test-CheckStatus 'lnk.startmenu' 'Fail')               { Add-Caveat $v 'Start-menu shortcut creation is blocked; drop the [Icons] section.' }
if (Test-CheckStatus 'av.defender' 'Warn')                 { Add-Caveat $v 'Defender real-time protection is on - PyInstaller bundles are a frequent false positive.' }
$verdicts.Add($v)

# ---- Path 2: portable ZIP ----------------------------------------------------
$v = New-Verdict 'Portable ZIP (no installer, no admin)' "Scribe-Portable-$archSuffix.zip"
$anyPortableHome = $false
$anyUnverifiedHome = $false
foreach ($id in @('localappdata', 'userprofile', 'appdata')) {
    if (-not (Test-CheckStatus "write.$id" 'Pass')) { continue }
    if     (Test-CheckStatus "exec.$id" 'Pass')    { $anyPortableHome = $true }
    elseif (Test-CheckStatus "exec.$id" 'Unknown') { $anyUnverifiedHome = $true }
}
if (-not $anyPortableHome) {
    if ($anyUnverifiedHome) {
        Add-Caveat $v 'A writable folder exists but execution from it was NOT verified (no test binary could be produced). Re-run under Windows PowerShell 5.1 before trusting this verdict.'
    } else {
        Add-Blocker $v 'No user-writable folder was found that also permits executing a program.'
    }
}
if (Test-CheckStatus 'av.cfa' 'Fail')          { Add-Caveat $v 'Controlled Folder Access is on - unzip to %LOCALAPPDATA%, never to Desktop/Documents/Downloads.' }
if (Test-CheckStatus 'exec.desktop' 'Fail')    { Add-Caveat $v 'Desktop is not an executable location; the docs'' "unzip anywhere" instruction is wrong on this machine.' }
if (Test-CheckStatus 'execmotw.downloads' 'Fail') { Add-Caveat $v 'The ZIP must be unblocked before extracting (right-click > Properties > Unblock, or Unblock-File).' }
if (Test-CheckStatus 'ac.wdac' 'Fail')         { Add-Blocker $v 'WDAC enforcement applies regardless of folder.' }
if (Test-CheckStatus 'ac.sac' 'Fail')          { Add-Blocker $v 'Smart App Control applies regardless of folder.' }
$verdicts.Add($v)

# ---- Path 3: portable from USB ----------------------------------------------
$usbTargets = @($script:Checks | Where-Object { $_.Id -like 'exec.removable_*' })
$v = New-Verdict 'Portable from a USB stick' "Scribe-Portable-$archSuffix.zip on removable media"
if ($usbTargets.Count -eq 0) {
    $v.Status = 'UNKNOWN'
    Add-Caveat $v 'No removable drive was attached during the check - re-run with the USB stick plugged in.'
} elseif (@($usbTargets | Where-Object { $_.Status -eq 'Pass' }).Count -eq 0) {
    Add-Blocker $v 'Execution from the removable drive was refused.'
}
$asr = Get-Check 'av.asr'
if ($asr -and $asr.Status -eq 'Fail' -and ($asr.Data -join ' ') -match 'USB') {
    Add-Blocker $v 'ASR rule "Block untrusted and unsigned processes that run from USB" is in Block mode.'
}
$verdicts.Add($v)

# ---- Path 4: Python from source ---------------------------------------------
$v = New-Verdict 'Python from source (setup.bat)' 'git clone / source ZIP'
if (Test-CheckStatus 'py.present' 'Fail')  { Add-Blocker $v 'No usable Python 3.10+ interpreter is present, and installing one usually needs admin.' }
if (Test-CheckStatus 'script.cmd' 'Fail')  { Add-Blocker $v 'Batch scripts are blocked, so setup.bat cannot start.' }
if (Test-CheckStatus 'py.venv' 'Fail')     { Add-Blocker $v 'A virtual environment cannot be created or its interpreter cannot execute.' }
if (Test-CheckStatus 'net.pip' 'Fail')     { Add-Blocker $v 'pip cannot reach a package index, so dependencies cannot be installed.' }
if (Test-CheckStatus 'py.venv' 'Skip')     { Add-Caveat $v 'venv creation was not tested (-Quick); re-run without -Quick to confirm.' }
if (Test-CheckStatus 'net.tlsintercept' 'Warn') { Add-Caveat $v 'TLS inspection will break pip unless REQUESTS_CA_BUNDLE points at the corporate root CA.' }
if (Test-CheckStatus 'host.disk' 'Warn')   { Add-Caveat $v 'Disk space is tight for a full source install.' }
$verdicts.Add($v)

# ---- Path 5: offline / pre-seeded -------------------------------------------
$v = New-Verdict 'Offline pre-seeded build (models + wheels bundled)' "Scribe-Portable-$archSuffix.zip with ScribeData\models pre-filled"
$netBad = (Test-CheckStatus 'net.huggingface.co' 'Fail') -or (Test-CheckStatus 'net.cdn-lfs.huggingface.co' 'Fail') -or (Test-CheckStatus 'net.tlsintercept' 'Warn')
if (-not $anyPortableHome -and -not $anyUnverifiedHome) { Add-Blocker $v 'Same execution blocker as the portable ZIP.' }
if ($netBad) {
    Add-Ask $v 'REQUIRED here: the first-run model download will fail, so the models must ship inside the ZIP.'
} elseif ($SkipNetwork) {
    Add-Caveat $v 'Network was not probed (-Quick / -SkipNetwork), so it is unknown whether the first-run model download works. Re-run without those switches before deciding.'
} else {
    Add-Caveat $v 'Not strictly needed on this machine - the model download works - but it removes the 500 MB first-run wait.'
}
$verdicts.Add($v)

# ---- Path 6: IT-deployed machine-wide ---------------------------------------
$v = New-Verdict 'IT-deployed machine-wide install (last resort)' 'MSI/Intune package built from the same bundle'
$v.Status = 'REQUIRES IT'
Add-Ask $v 'Deploy to Program Files via Intune/SCCM, plus an AppLocker/WDAC allow rule for the Scribe publisher or hashes.'
Add-Ask $v 'This is the fallback when every user-writable path is execution-blocked.'
$verdicts.Add($v)

# ---- Print ------------------------------------------------------------------
foreach ($v in $verdicts) {
    $color = switch ($v.Status) {
        'GO'              { 'Green' }
        'GO WITH CAVEATS' { 'Yellow' }
        'BLOCKED'         { 'Red' }
        'REQUIRES IT'     { 'Magenta' }
        default           { 'DarkGray' }
    }
    Out-Line ''
    Out-Line ("  {0,-17} {1}" -f ('[' + $v.Status + ']'), $v.Name) $color
    Out-Line ("                    ship: {0}" -f $v.Artifact) 'DarkGray'
    foreach ($b in $v.Blockers) { Out-Line ("      BLOCKER  " + $b) 'Red' }
    foreach ($c in $v.Caveats)  { Out-Line ("      caveat   " + $c) 'Yellow' }
    foreach ($a in $v.Asks)     { Out-Line ("      ask IT   " + $a) 'Magenta' }
}

# ==============================================================================
#  10. Hard blockers that apply to every path
# ==============================================================================

$universal = @()
if (Test-CheckStatus 'run.micpolicy' 'Fail') { $universal += 'Microphone access is denied - Scribe cannot record on this machine at all.' }
if (Test-CheckStatus 'run.hook' 'Fail')      { $universal += 'The global keyboard hook is refused - push-to-talk cannot work on this machine at all.' }
if (Test-CheckStatus 'run.hook' 'Unknown')   { $universal += 'The global keyboard hook was not tested - re-run with -TestInputHooks before committing to any path.' }
if (Test-CheckStatus 'host.disk' 'Fail')     { $universal += 'Not enough free disk space for the speech models.' }
if (Test-CheckStatus 'host.admin' 'Info') {
    $a = Get-Check 'host.admin'
    if ($a.Data -and $a.Data.Elevated) { $universal += 'This run was ELEVATED - the results above are optimistic. Re-run as a standard user.' }
}

if ($universal.Count -gt 0) {
    Out-Head 'Applies to EVERY path - fix these first'
    foreach ($u in $universal) { Out-Line ('  !! ' + $u) 'Red' }
}

# ---- Recommendation ---------------------------------------------------------

Out-Head 'Recommended build recipe for this machine'

$best = $verdicts | Where-Object { $_.Status -eq 'GO' } | Select-Object -First 1
if (-not $best) { $best = $verdicts | Where-Object { $_.Status -eq 'GO WITH CAVEATS' } | Select-Object -First 1 }

if ($best) {
    Out-Line ''
    Out-Line ("  Ship:  {0}" -f $best.Artifact) 'Green'
    Out-Line ("  Path:  {0}" -f $best.Name) 'Green'
    $installTo = '%LOCALAPPDATA%\Scribe'
    if (Test-CheckStatus 'exec.localappdata' 'Fail') { $installTo = 'a folder your IT has execution-allowlisted' }
    Out-Line ("  Install to: {0}" -f $installTo) 'Gray'

    $autostart = 'HKCU Run key (installer default)'
    if (Test-CheckStatus 'reg.runkey' 'Fail') {
        if (Test-CheckStatus 'lnk.startup' 'Pass') { $autostart = 'Startup-folder shortcut (Run key is blocked)' }
        elseif (Test-CheckStatus 'task.create' 'Pass') { $autostart = 'logon scheduled task (Run key and Startup folder are blocked)' }
        else { $autostart = 'NONE available - the user must launch Scribe manually' }
    }
    Out-Line ("  Autostart:  {0}" -f $autostart) 'Gray'

    if ($netBad)          { Out-Line '  Models:     PRE-SEED them into ScribeData\models - the first-run download will fail here.' 'Yellow' }
    elseif ($SkipNetwork) { Out-Line '  Models:     UNKNOWN - network was not probed. Re-run without -Quick/-SkipNetwork.' 'Yellow' }
    else                  { Out-Line '  Models:     first-run download is fine (~500 MB).' 'Gray' }

    if ((Test-CheckStatus 'execsigned.localappdata_programs' 'Warn') -or (Test-CheckStatus 'ac.sac' 'Fail') -or (Test-CheckStatus 'ac.wdac' 'Fail')) {
        Out-Line '  Signing:    REQUIRED - this machine enforces publisher/code-integrity rules. Budget an EV certificate.' 'Yellow'
    } elseif (Test-CheckStatus 'ac.smartscreen' 'Info') {
        Out-Line '  Signing:    optional - users will see one SmartScreen warning with a "Run anyway" override.' 'Gray'
    }
} else {
    Out-Line ''
    Out-Line '  No self-service delivery path is viable on this machine.' 'Red'
    Out-Line '  Take the "REQUIRES IT" route: an IT-deployed package plus an allowlist rule.' 'Red'
    Out-Line '  Send the JSON report below to whoever owns endpoint policy - it names' 'Red'
    Out-Line '  the exact controls that need an exception.' 'Red'
}

# ==============================================================================
#  11. Reports + cleanup
# ==============================================================================

$counts = @{}
foreach ($s in @('Pass', 'Fail', 'Warn', 'Info', 'Skip', 'Unknown')) {
    $counts[$s] = @($script:Checks | Where-Object { $_.Status -eq $s }).Count
}
Out-Line ''
Out-Line ("  Checks: {0} pass, {1} fail, {2} warn, {3} unknown, {4} skipped." -f `
    $counts['Pass'], $counts['Fail'], $counts['Warn'], $counts['Unknown'], $counts['Skip']) 'DarkGray'

if (-not $OutDir) { $OutDir = (Get-Location).Path }
$w = Test-DirWrite -Dir $OutDir
if (-not $w.Ok) { $OutDir = $env:TEMP }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$base  = Join-Path $OutDir ("scribe-envcheck-{0}-{1}" -f $env:COMPUTERNAME, $stamp)

Invoke-Safe 'report' {
    $report = [pscustomobject]@{
        SchemaVersion = 1
        ScriptVersion = $SCRIPT_VERSION
        GeneratedUtc  = (Get-Date).ToUniversalTime().ToString('o')
        Computer      = $env:COMPUTERNAME
        UserDomain    = $env:USERDOMAIN
        Elevated      = (Get-Check 'host.admin').Data
        Options       = @{ Quick = [bool]$Quick; SkipNetwork = [bool]$SkipNetwork; TestInputHooks = [bool]$TestInputHooks }
        Checks        = $script:Checks
        Verdicts      = $verdicts
        UniversalBlockers = $universal
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath ($base + '.json') -Encoding UTF8
    $script:Transcript.ToString() | Set-Content -LiteralPath ($base + '.txt') -Encoding UTF8
    Write-Host ''
    Write-Host ("  Report written:") -ForegroundColor Cyan
    Write-Host ("    " + $base + '.txt   (this transcript)') -ForegroundColor Cyan
    Write-Host ("    " + $base + '.json  (send this back - it diffs cleanly between companies)') -ForegroundColor Cyan
}

Remove-Item -LiteralPath $script:Scratch -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ''

# Exit code so this can be driven from a deployment tool:
#   0 = at least one path works    1 = only with caveats    2 = nothing works
if ($verdicts | Where-Object { $_.Status -eq 'GO' })              { exit 0 }
elseif ($verdicts | Where-Object { $_.Status -eq 'GO WITH CAVEATS' }) { exit 1 }
else { exit 2 }
