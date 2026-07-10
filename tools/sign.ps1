<#
.SYNOPSIS
  Authenticode-sign Scribe's exes and/or installers with a PFX certificate.

.DESCRIPTION
  Signs each given file with an Authenticode signature and a trusted
  timestamp, then verifies the signature landed. Driven entirely by two
  environment variables so no secret is ever hardcoded or passed on the
  command line:

      SCRIBE_CERT_PFX   full path to a .pfx code-signing certificate
      SCRIBE_CERT_PASS  password for that .pfx (optional if the pfx has none)

  If SCRIBE_CERT_PFX is unset the script is a deliberate no-op: it prints a
  clear message and exits 0, so unsigned builds keep working unchanged.

  Signing uses Set-AuthenticodeSignature (built into Windows PowerShell — no
  Windows SDK required). If signtool.exe from a Windows SDK happens to be on
  PATH it is preferred because it produces an RFC3161 (SHA-256) timestamp;
  otherwise Set-AuthenticodeSignature applies a legacy Authenticode timestamp.
  Either way the timestamp keeps the signature valid after the cert expires.

.PARAMETER Path
  One or more files to sign. A directory is expanded to the signable binaries
  it contains (*.exe, *.dll, *.msi). Defaults to the two app exes under
  dist\scribe if nothing is given.

.PARAMETER TimestampServer
  RFC3161 / Authenticode timestamp URL. Defaults to DigiCert's public server.

.EXAMPLE
  # sign the two app exes (env vars already set)
  tools\sign.ps1 dist\scribe\scribe.exe dist\scribe\scribe-tray.exe

.EXAMPLE
  # sign a whole bundle directory, then the installer
  tools\sign.ps1 dist\scribe
  tools\sign.ps1 dist\Scribe-Setup-x64.exe
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true, Position = 0)]
    [string[]] $Path,

    [string] $TimestampServer = 'http://timestamp.digicert.com'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Info  ($m) { Write-Host "[sign] $m" }
function Write-Warn2 ($m) { Write-Warning $m }

# --- 1. No cert configured -> clean no-op so unsigned builds still work -------
$pfx = $env:SCRIBE_CERT_PFX
if ([string]::IsNullOrWhiteSpace($pfx)) {
    Write-Info 'SCRIBE_CERT_PFX is not set - skipping code signing (build stays unsigned).'
    Write-Info 'Set SCRIBE_CERT_PFX / SCRIBE_CERT_PASS to enable signing (see BUILDING.md).'
    exit 0
}
if (-not (Test-Path -LiteralPath $pfx)) {
    throw "SCRIBE_CERT_PFX points at a file that does not exist: $pfx"
}

# --- 2. Resolve the list of files to sign ------------------------------------
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Path -or $Path.Count -eq 0) {
    $Path = @(
        (Join-Path $repoRoot 'dist\scribe\scribe.exe'),
        (Join-Path $repoRoot 'dist\scribe\scribe-tray.exe')
    )
}

$targets = New-Object System.Collections.Generic.List[string]
foreach ($p in $Path) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "File to sign does not exist: $p"
    }
    $item = Get-Item -LiteralPath $p
    if ($item.PSIsContainer) {
        Get-ChildItem -LiteralPath $item.FullName -Recurse -Include *.exe, *.dll, *.msi -File |
            ForEach-Object { $targets.Add($_.FullName) }
    }
    else {
        $targets.Add($item.FullName)
    }
}
if ($targets.Count -eq 0) {
    throw 'No signable files (*.exe, *.dll, *.msi) were found in the given paths.'
}

# --- 3. Load the certificate from the PFX ------------------------------------
$pass = $env:SCRIBE_CERT_PASS
try {
    if ([string]::IsNullOrEmpty($pass)) {
        $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 `
            $pfx, '', 'Exportable'
    }
    else {
        $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 `
            $pfx, $pass, 'Exportable'
    }
}
catch {
    throw "Could not open PFX '$pfx' (wrong SCRIBE_CERT_PASS?): $($_.Exception.Message)"
}
if (-not $cert.HasPrivateKey) {
    throw "PFX '$pfx' has no private key - it cannot be used for signing."
}
Write-Info ("Certificate: {0}  (expires {1:yyyy-MM-dd})" -f $cert.Subject, $cert.NotAfter)

# --- 4. Optional: prefer signtool.exe when a Windows SDK is installed ---------
$signtool = (Get-Command signtool.exe -ErrorAction SilentlyContinue |
             Select-Object -First 1 -ExpandProperty Source)

$failures = New-Object System.Collections.Generic.List[string]

foreach ($file in $targets) {
    Write-Info "Signing $file"
    try {
        if ($signtool) {
            # SDK present: true RFC3161 SHA-256 timestamp.
            & $signtool sign /fd SHA256 /f $pfx `
                ($(if ($pass) { @('/p', $pass) } else { @() })) `
                /tr $TimestampServer /td SHA256 $file | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "signtool exited $LASTEXITCODE" }
        }
        else {
            # No SDK: built-in signing + legacy Authenticode timestamp.
            $r = Set-AuthenticodeSignature -FilePath $file -Certificate $cert `
                    -HashAlgorithm SHA256 -TimestampServer $TimestampServer `
                    -ErrorAction Stop
            if ($r.Status -ne 'Valid' -and $null -eq $r.SignerCertificate) {
                throw "Set-AuthenticodeSignature returned status '$($r.Status)'"
            }
        }
    }
    catch {
        Write-Warn2 "Failed to sign $file : $($_.Exception.Message)"
        $failures.Add($file)
        continue
    }

    # --- 5. Verify a signature is actually present ---------------------------
    $sig = Get-AuthenticodeSignature -LiteralPath $file
    $present = ($null -ne $sig.SignerCertificate) -and
               ($sig.Status -ne 'NotSigned') -and
               ($sig.Status -ne 'HashMismatch')
    if (-not $present) {
        Write-Warn2 "Verification FAILED for $file (status: $($sig.Status))"
        $failures.Add($file)
        continue
    }

    $thumb = $sig.SignerCertificate.Thumbprint
    $ts    = if ($sig.TimeStamperCertificate) { 'timestamped' } else { 'NOT timestamped' }
    if ($sig.Status -eq 'Valid') {
        Write-Info "  OK  status=Valid  $ts  thumbprint=$thumb"
    }
    else {
        # A signature is present but the chain is not trusted on this machine.
        # Expected for a self-signed dev cert; a real OV/EV cert reports Valid.
        Write-Warn2 ("  Signature present but status='{0}' ({1}). " -f $sig.Status, $ts +
                     "Expected for a self-signed dev cert; a purchased OV/EV cert verifies as Valid.")
        Write-Info  "  thumbprint=$thumb"
    }
}

# --- 6. Fail loudly if anything did not get signed ---------------------------
if ($failures.Count -gt 0) {
    Write-Error ("Signing FAILED for {0} file(s):`n  {1}" -f `
        $failures.Count, ($failures -join "`n  "))
    exit 1
}

Write-Info ("Done - {0} file(s) signed and verified." -f $targets.Count)
exit 0
