param(
  [Parameter(Mandatory=$true)][string]$Version,
  [string]$Destination = "$env:LOCALAPPDATA\Shamash\$Version"
)

$ErrorActionPreference = "Stop"
$zipName = "shamash-cli-$Version.zip"
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("shamash-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null

function Get-ReleaseFile {
  param([string]$Name, [string]$OutFile)
  $lastError = $null
  foreach ($tag in @("v$Version", $Version)) {
    $url = "https://github.com/aalsanie/shamash/releases/download/$tag/$Name"
    try {
      Invoke-WebRequest -Uri $url -OutFile $OutFile
      return
    } catch {
      $lastError = $_
    }
  }
  throw "Unable to download $Name for Shamash $Version (tried tags v$Version and $Version). $lastError"
}

try {
  Get-ReleaseFile -Name $zipName -OutFile (Join-Path $tmp $zipName)
  Get-ReleaseFile -Name "SHA256SUMS.txt" -OutFile (Join-Path $tmp "SHA256SUMS.txt")

  $line = Get-Content (Join-Path $tmp "SHA256SUMS.txt") |
    Where-Object { $_ -match "\s+$([regex]::Escape($zipName))$" } |
    Select-Object -First 1
  if (-not $line) { throw "Checksum entry missing for $zipName" }

  $expected = ($line -split '\s+')[0].ToLowerInvariant()
  $actual = (Get-FileHash (Join-Path $tmp $zipName) -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($expected -ne $actual) { throw "Checksum mismatch for $zipName" }

  if (Test-Path $Destination) { Remove-Item $Destination -Recurse -Force }
  New-Item -ItemType Directory -Path $Destination | Out-Null
  Expand-Archive (Join-Path $tmp $zipName) -DestinationPath $Destination

  $cmd = Get-ChildItem $Destination -Recurse -Filter shamash.bat | Select-Object -First 1
  if (-not $cmd) { throw "shamash.bat not found after extraction" }
  Write-Output $cmd.FullName
} finally {
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
