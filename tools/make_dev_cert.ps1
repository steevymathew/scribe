<#
.SYNOPSIS
  Generate a SELF-SIGNED code-signing certificate for TESTING the signing
  pipeline (tools\sign.ps1). NOT for production.

.DESCRIPTION
  Creates a self-signed CodeSigning certificate, exports it to a password-
  protected .pfx, and (by default) removes it again from the certificate store
  so nothing is left behind - the exported .pfx is all sign.ps1 needs.

  A self-signed certificate lets you prove the signing + verification pipeline
  end to end, but it does NOT establish trust: Windows SmartScreen and the
  "Verified publisher" chain are UNAFFECTED. Only a certificate purchased from
  a public CA (OV or, better, EV) removes the SmartScreen "unknown publisher"
  warning. See BUILDING.md > Code signing.

.PARAMETER Subject
  Certificate subject / publisher name. Default 'CN=Scribe Dev Test'.

.PARAMETER PfxPath
  Where to write the .pfx. Default: <repo>\dist\scribe-dev-cert.pfx.

.PARAMETER Password
  Password for the .pfx. Falls back to env SCRIBE_CERT_PASS, then a default
  test value. Never use a real secret here - this is a throwaway test cert.

.PARAMETER KeepInStore
  Leave the generated cert in Cert:\CurrentUser\My (default removes it).

.EXAMPLE
  tools\make_dev_cert.ps1 -Password 'test1234'
#>
[CmdletBinding()]
param(
    [string] $Subject  = 'CN=Scribe Dev Test',
    [string] $PfxPath  = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist\scribe-dev-cert.pfx'),
    [string] $Password,
    [switch] $KeepInStore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrEmpty($Password)) {
    $Password = if ($env:SCRIBE_CERT_PASS) { $env:SCRIBE_CERT_PASS } else { 'scribe-dev' }
}

Write-Host '=================================================================='
Write-Host ' Scribe DEV code-signing certificate (self-signed - TESTING ONLY)'
Write-Host '=================================================================='
Write-Warning 'A self-signed cert does NOT remove SmartScreen warnings. It only'
Write-Warning 'lets you exercise the signing pipeline. Ship with a real OV/EV cert.'

$pfxDir = Split-Path -Parent $PfxPath
if ($pfxDir -and -not (Test-Path -LiteralPath $pfxDir)) {
    New-Item -ItemType Directory -Path $pfxDir -Force | Out-Null
}

# Create the self-signed code-signing certificate in the current-user store.
$cert = New-SelfSignedCertificate `
    -Type CodeSigningCert `
    -Subject $Subject `
    -CertStoreLocation 'Cert:\CurrentUser\My' `
    -KeyExportPolicy Exportable `
    -KeyUsage DigitalSignature `
    -KeyAlgorithm RSA `
    -KeyLength 2048 `
    -HashAlgorithm SHA256 `
    -NotAfter (Get-Date).AddYears(3)

Write-Host ("Created cert: {0}" -f $cert.Subject)
Write-Host ("Thumbprint  : {0}" -f $cert.Thumbprint)

# Export to PFX.
$secure = ConvertTo-SecureString -String $Password -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath $PfxPath -Password $secure | Out-Null
Write-Host ("Exported PFX: {0}" -f $PfxPath)

# Clean up the store entry unless asked to keep it (the PFX is self-contained).
if (-not $KeepInStore) {
    Remove-Item -LiteralPath ("Cert:\CurrentUser\My\{0}" -f $cert.Thumbprint) -Force
    Write-Host 'Removed the cert from Cert:\CurrentUser\My (PFX is all you need).'
}
else {
    Write-Host ("Left the cert in Cert:\CurrentUser\My\{0}" -f $cert.Thumbprint)
}

Write-Host ''
Write-Host 'To sign with this dev cert, set these environment variables:'
Write-Host ''
Write-Host ("  PowerShell:  `$env:SCRIBE_CERT_PFX = '{0}'" -f $PfxPath)
Write-Host      "               `$env:SCRIBE_CERT_PASS = '<the password you chose>'"
Write-Host ''
Write-Host '  then:        tools\sign.ps1 dist\scribe\scribe.exe dist\scribe\scribe-tray.exe'
Write-Host ''
Write-Host 'Get-AuthenticodeSignature will report the file signed but the chain'
Write-Host 'UNTRUSTED (status UnknownError / NotTrusted) - expected for self-signed.'
