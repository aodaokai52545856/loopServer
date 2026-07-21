# Minimal repair-node installer for Windows Beta.
# Verifies checksum/signature, copies to an absolute destination, creates the
# state directory, and prints the join command. Does not enroll, download
# Runner, or change firewall policy.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Archive,

    [Parameter(Mandatory = $true)]
    [string]$Checksum,

    [Parameter(Mandatory = $true)]
    [string]$Dest,

    [string]$Signature,

    [string]$StateDir = 'C:\ProgramData\repair-node'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-AbsolutePath([string]$PathValue, [string]$Name) {
    if (-not [System.IO.Path]::IsPathRooted($PathValue)) {
        throw "$Name must be an absolute path: $PathValue"
    }
}

Assert-AbsolutePath -PathValue $Dest -Name '--Dest'
Assert-AbsolutePath -PathValue $StateDir -Name '--StateDir'

if (-not (Test-Path -LiteralPath $Archive)) {
    throw "archive not found: $Archive"
}
if (-not (Test-Path -LiteralPath $Checksum)) {
    throw "checksum file not found: $Checksum"
}

Write-Host 'Verifying SHA-256 checksum...'
$expectedLine = (Get-Content -LiteralPath $Checksum -Raw).Trim()
if ($expectedLine -match '([A-Fa-f0-9]{64})') {
    $expected = $Matches[1].ToLowerInvariant()
} else {
    throw "unable to parse checksum file: $Checksum"
}
$hash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $expected) {
    throw "checksum mismatch: expected $expected got $hash"
}

if ($Signature) {
    if (-not (Test-Path -LiteralPath $Signature)) {
        throw "signature file not found: $Signature"
    }
    $cosign = Get-Command cosign -ErrorAction SilentlyContinue
    if ($cosign) {
        & cosign verify-blob --signature $Signature $Archive
        if ($LASTEXITCODE -ne 0) {
            throw 'cosign signature verification failed'
        }
    } elseif ($env:REPAIR_NODE_SIGNING_CERT) {
        & openssl dgst -sha256 -verify $env:REPAIR_NODE_SIGNING_CERT -signature $Signature $Archive
        if ($LASTEXITCODE -ne 0) {
            throw 'openssl signature verification failed'
        }
    } else {
        throw 'signature provided but no verifier available (cosign or openssl+REPAIR_NODE_SIGNING_CERT)'
    }
}

$tmpdir = Join-Path ([System.IO.Path]::GetTempPath()) ("repair-node-install-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmpdir | Out-Null
try {
    tar -xzf $Archive -C $tmpdir
    $pkgDir = Get-ChildItem -LiteralPath $tmpdir -Directory | Select-Object -First 1
    if (-not $pkgDir) {
        throw 'archive did not contain a package directory'
    }

    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Copy-Item -LiteralPath (Join-Path $pkgDir.FullName 'repair-node.exe') -Destination (Join-Path $Dest 'repair-node.exe') -Force
    $executor = Join-Path $pkgDir.FullName 'repair-executor.exe'
    if (Test-Path -LiteralPath $executor) {
        Copy-Item -LiteralPath $executor -Destination (Join-Path $Dest 'repair-executor.exe') -Force
    }
    foreach ($name in @('config.sample.yaml', 'repair-node.service.md', 'LICENSE', 'SUPPORT.txt')) {
        $src = Join-Path $pkgDir.FullName $name
        if (Test-Path -LiteralPath $src) {
            Copy-Item -LiteralPath $src -Destination (Join-Path $Dest $name) -Force
        }
    }

    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

    Write-Host @"
Installed repair-node to $Dest
State directory: $StateDir

Windows support is Beta. Prefer an interactive user session for local model tools
until Windows Service compatibility is certified.

Next step (manual enrollment only):
  & '$Dest\repair-node.exe' join --server <control-plane-url> --code <one-time-invite> --state-dir '$StateDir'

This installer does not enroll the node, download GitLab Runner, or change firewall policy.
"@
}
finally {
    Remove-Item -LiteralPath $tmpdir -Recurse -Force -ErrorAction SilentlyContinue
}
