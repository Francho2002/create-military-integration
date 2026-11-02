[CmdletBinding()]
param(
    [string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar",
    [string]$Sources = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev-sources.jar",
    [string]$OutputDirectory = "$PSScriptRoot/../build/distributions"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifactPath = (Resolve-Path -LiteralPath $Artifact -PathType Leaf).Path
$sourcePath = (Resolve-Path -LiteralPath $Sources -PathType Leaf).Path
$branch = (& git -C $repo rev-parse --abbrev-ref HEAD).Trim()
$commit = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or !$commit -or $branch -eq 'main') { throw 'Package preparation requires a committed review branch, not main.' }
$status = @(& git -C $repo status --porcelain)
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) { throw 'Commit tracked source and documentation before preparing the package.' }
$versionLine = Select-String -LiteralPath (Join-Path $repo 'build.gradle') -Pattern '^version\s*=\s*[''"]([^''"]+)[''"]' | Select-Object -First 1
if (!$versionLine -or !$versionLine.Matches[0].Groups[1].Value) { throw 'Could not read the project version.' }
$version = $versionLine.Matches[0].Groups[1].Value
$short = $commit.Substring(0, 12)
$out = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $out -Force | Out-Null
$outFull = [IO.Path]::GetFullPath($out).TrimEnd([IO.Path]::DirectorySeparatorChar)
$stage = Join-Path $out "staging-$short"
$stageFull = [IO.Path]::GetFullPath($stage)
if (!$stageFull.StartsWith($outFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to stage outside the requested distribution directory.'
}
$zip = Join-Path $out "create-military-integration-$version-experimental-$short.zip"
if (Test-Path -LiteralPath $stage) { throw "Staging path already exists; refusing to overwrite: $stage" }
if (Test-Path -LiteralPath $zip) { throw "Package already exists; refusing to overwrite: $zip" }
$required = @('README.md','LICENSE','NOTICE','THIRD_PARTY.md',
    'RELEASE_NOTES.md','COMPATIBILITY.md','KNOWN_LIMITATIONS.md','dependency-hashes.properties')
foreach ($name in $required) { if (!(Test-Path -LiteralPath (Join-Path $repo $name) -PathType Leaf)) { throw "Missing package document: $name" } }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
foreach ($name in $required) { Copy-Item -LiteralPath (Join-Path $repo $name) -Destination (Join-Path $stage $name) }
Copy-Item -LiteralPath $artifactPath -Destination (Join-Path $stage (Split-Path $artifactPath -Leaf))
Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $stage (Split-Path $sourcePath -Leaf))
$artifactHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash
$sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
$java = (& java -version 2>&1 | Out-String).Trim() -replace '\s+', ' '
$provenance = @(
    'Project: Francho2002/create-military-integration'
    "Version: $version"
    "Source branch: $branch"
    "Source commit: $commit"
    "Base: 08aecb9c1f073b3f544c01a49d0819b09612f954"
    "Java runtime used for package preparation: $java"
    "Artifact SHA-256: $artifactHash"
    "Sources SHA-256: $sourceHash"
    'Dependency pins: dependency-hashes.properties (included; verified by the build)'
    'Runtime status: Minecraft, client, server and GameTest were not run for Cycle 2L.'
    'Distribution status: local review package only; no release, tag, installation or deployment.'
    'External mod JARs are intentionally excluded.'
)
[IO.File]::WriteAllLines((Join-Path $stage 'PROVENANCE.txt'), $provenance, [Text.UTF8Encoding]::new($false))
$sumLines = Get-ChildItem -LiteralPath $stage -File | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    "$hash  $($_.Name)"
}
[IO.File]::WriteAllLines((Join-Path $stage 'SHA256SUMS.txt'), $sumLines, [Text.UTF8Encoding]::new($false))
New-Item -ItemType Directory -Path $out -Force | Out-Null
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -CompressionLevel Optimal
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($zip)
try {
    $names = @($archive.Entries | ForEach-Object { $_.FullName })
    foreach ($name in @('LICENSE','NOTICE','THIRD_PARTY.md','RELEASE_NOTES.md','COMPATIBILITY.md','KNOWN_LIMITATIONS.md','SHA256SUMS.txt','PROVENANCE.txt')) {
        if ($name -notin $names) { throw "Package archive is missing $name" }
    }
    $jars = @($names | Where-Object { $_ -like '*.jar' })
    if ($jars.Count -ne 2 -or ($names | Where-Object { $_ -match '\.jar$' -and $_ -notin @((Split-Path $artifactPath -Leaf),(Split-Path $sourcePath -Leaf)) })) {
        throw 'Package must contain exactly the CMI binary and its source archive, with no dependency JARs.'
    }
} finally { $archive.Dispose() }
$stageParent = [IO.Path]::GetFullPath((Split-Path -Parent $stageFull)).TrimEnd([IO.Path]::DirectorySeparatorChar)
if ($stageParent -ne $outFull) { throw 'Refusing to remove staging outside the distribution directory.' }
Remove-Item -LiteralPath $stageFull -Recurse -Force
$zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
Write-Host "PACKAGE: $zip"
Write-Host "SHA256: $zipHash"
Write-Host "Source commit: $commit"
Write-Host 'Static review package prepared locally. No game runtime or deployment was performed.'
