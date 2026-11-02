param(
  [Parameter(Mandatory=$true)][string]$Version,
  [string]$Destination = "$env:LOCALAPPDATA\Shamash\$Version"
)
$ErrorActionPreference = 'Stop'
if ($Version -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
  throw 'Invalid Shamash version: expected MAJOR.MINOR.PATCH.'
}
$zipName = "shamash-cli-$Version.zip"
$destinationPath = [IO.Path]::GetFullPath($Destination).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$homePath = [IO.Path]::GetFullPath($HOME).TrimEnd([IO.Path]::DirectorySeparatorChar)
$cwdPath = [IO.Path]::GetFullPath((Get-Location).Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
$rootPath = [IO.Path]::GetPathRoot($destinationPath).TrimEnd([IO.Path]::DirectorySeparatorChar)
if (-not $destinationPath -or $destinationPath -eq $rootPath -or
    $destinationPath -eq $homePath -or $destinationPath -eq $cwdPath -or
    $homePath.StartsWith($destinationPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    $cwdPath.StartsWith($destinationPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Unsafe installation destination'
}
$ancestor = $destinationPath
while ($ancestor) {
  if ((Test-Path -LiteralPath $ancestor) -and
      ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Installation destination must not traverse a symbolic link'
  }
  $parent = [IO.Path]::GetDirectoryName($ancestor)
  if (-not $parent -or $parent -eq $ancestor) { break }
  $ancestor = $parent
}
$markerName = '.shamash-install'
if (Test-Path -LiteralPath $destinationPath) {
  if (-not (Test-Path -LiteralPath $destinationPath -PathType Container)) {
    throw "Refusing to replace a destination not owned by Shamash: $destinationPath"
  }
  $marker = Join-Path $destinationPath $markerName
  if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
    throw "Refusing to replace a destination not owned by Shamash: $destinationPath"
  }
  $markerValue = Get-Content -LiteralPath $marker -Raw
  if ($markerValue.Trim() -cnotmatch '^shamash-cli:(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
    throw "Refusing to replace an unrecognized Shamash installation: $destinationPath"
  }
}
$parentDir = [IO.Path]::GetDirectoryName($destinationPath)
[IO.Directory]::CreateDirectory($parentDir) | Out-Null
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('shamash-download-' + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $parentDir ('.shamash-stage-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($tmp) | Out-Null
[IO.Directory]::CreateDirectory($stage) | Out-Null
$backup = $null
$published = $false
function Get-ReleaseFile {
  param([string]$Name, [string]$OutFile)
  $lastError = $null
  foreach ($tag in @("v$Version", $Version)) {
    $url = "https://github.com/aalsanie/shamash/releases/download/$tag/$Name"
    try {
      Invoke-WebRequest -Uri $url -OutFile $OutFile -UseBasicParsing | Out-Null
      return
    } catch { $lastError = $_ }
  }
  throw "Unable to download $Name for Shamash $Version. $lastError"
}
try {
  Get-ReleaseFile -Name $zipName -OutFile (Join-Path $tmp $zipName)
  Get-ReleaseFile -Name 'SHA256SUMS.txt' -OutFile (Join-Path $tmp 'SHA256SUMS.txt')
  $checksumLines = @(Get-Content -LiteralPath (Join-Path $tmp 'SHA256SUMS.txt') |
    Where-Object { $_ -cmatch ('^([0-9a-fA-F]{64})\s+\*?' + [regex]::Escape($zipName) + '$') })
  if ($checksumLines.Count -ne 1) { throw "Missing or ambiguous checksum for $zipName" }
  $expected = ($checksumLines[0] -split '\s+')[0].ToLowerInvariant()
  $archivePath = Join-Path $tmp $zipName
  $actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($expected -cne $actual) { throw "Checksum mismatch for $zipName" }

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
  try {
    $entries = @($archive.Entries)
    $root = "shamash-cli-$Version"
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $totalBytes = [long]0
    if ($entries.Count -lt 1 -or $entries.Count -gt 10000) { throw 'Invalid distribution archive size' }
    foreach ($entry in $entries) {
      $name = $entry.FullName
      $parts = $name.TrimEnd('/').Split('/')
      $unixMode = ($entry.ExternalAttributes -shr 16) -band 0xF000
      if (-not $name -or $name.Contains('\') -or $name.StartsWith('/') -or
          $parts[0] -cne $root -or @($parts | Where-Object { $_ -eq '' -or $_ -eq '.' -or $_ -eq '..' }).Count -gt 0 -or
          $unixMode -eq 0xA000 -or ($unixMode -notin @(0, 0x8000, 0x4000)) -or
          ($parts.Count -eq 1 -and -not $name.EndsWith('/')) -or -not $seen.Add($name.TrimEnd('/'))) {
        throw 'Unsafe distribution archive entry'
      }
      $totalBytes += $entry.Length
      if ($totalBytes -gt 2000000000) { throw 'Invalid distribution archive size' }
    }
    foreach ($entry in $entries) {
      $relative = $entry.FullName.TrimEnd('/').Replace('/', [IO.Path]::DirectorySeparatorChar)
      $target = Join-Path $stage $relative
      if ($entry.FullName.EndsWith('/')) {
        [IO.Directory]::CreateDirectory($target) | Out-Null
      } else {
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
        $inputStream = $entry.Open()
        try {
          $outputStream = [IO.File]::Create($target)
          try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
        } finally { $inputStream.Dispose() }
      }
    }
  } finally { $archive.Dispose() }
  $distribution = Join-Path $stage "shamash-cli-$Version"
  $bat = Join-Path $distribution 'bin/shamash.bat'
  $unixLauncher = Join-Path $distribution 'bin/shamash'
  $lib = Join-Path $distribution 'lib'
  if (-not (Test-Path -LiteralPath $bat -PathType Leaf) -or
      -not (Test-Path -LiteralPath $unixLauncher -PathType Leaf) -or
      -not (Test-Path -LiteralPath $lib -PathType Container) -or
      @((Get-ChildItem -LiteralPath $lib -Filter '*.jar' -File)).Count -lt 1) {
    throw 'Distribution is missing its launcher or libraries'
  }
  [IO.File]::WriteAllText((Join-Path $stage $markerName), "shamash-cli:$Version" + [Environment]::NewLine)
  if (Test-Path -LiteralPath $destinationPath) {
    $backup = Join-Path $parentDir ('.shamash-previous-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::Move($destinationPath, $backup)
  }
  [IO.Directory]::Move($stage, $destinationPath)
  $published = $true
  Write-Output (Join-Path $destinationPath "shamash-cli-$Version/bin/shamash.bat")
} finally {
  if (-not $published -and $backup -and (Test-Path -LiteralPath $backup) -and
      -not (Test-Path -LiteralPath $destinationPath)) {
    try { [IO.Directory]::Move($backup, $destinationPath) } catch {
      Write-Warning "Restore previous installation manually from $backup"
    }
  }
  if ($published -and $backup -and (Test-Path -LiteralPath $backup)) {
    Remove-Item -LiteralPath $backup -Recurse -Force
  }
  if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
  if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
}
