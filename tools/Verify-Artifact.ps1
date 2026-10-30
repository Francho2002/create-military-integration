param([string]$Artifact = "$PSScriptRoot/../build/libs/create-military-integration-0.1.0-dev.jar")
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Artifact))
try {
    $configEntry = $zip.GetEntry('cmi.mixins.json')
    if (!$configEntry -or !$zip.GetEntry('META-INF/neoforge.mods.toml')) { throw 'Missing metadata' }
    $reader = [IO.StreamReader]::new($configEntry.Open())
    $config = $reader.ReadToEnd() | ConvertFrom-Json
    $reader.Dispose()
    foreach ($mixin in $config.mixins) {
        $name = $config.package.Replace('.', '/') + '/' + $mixin.Replace('.', '/') + '.class'
        if (!$zip.GetEntry($name)) { throw "Missing registered mixin: $name" }
    }
    $count = 0
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('.jar')) { throw "Bundled dependency: $($entry.FullName)" }
        if (!$entry.FullName.EndsWith('.class')) { continue }
        if (!$entry.FullName.StartsWith('local/cmi/')) { throw "Third-party class: $($entry.FullName)" }
        $stream = $entry.Open()
        $header = [byte[]]::new(8)
        $stream.ReadExactly($header)
        $stream.Dispose()
        $major = [int]$header[6] * 256 + $header[7]
        if ($major -ne 65) { throw "Unexpected class version: $major" }
        $count++
    }
    "OK: $count Java 21 classes; $($config.mixins.Count) registered mixins; no bundled third-party JARs or classes."
} finally { $zip.Dispose() }
