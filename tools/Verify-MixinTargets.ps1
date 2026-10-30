param([Parameter(Mandatory)][string]$Mods, [Parameter(Mandatory)][string]$Candidates, [string]$Minecraft)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
if (!$Minecraft) {
    $cache = Join-Path $project '.gradle-home/caches/neoformruntime/intermediate_results'
    $minecraftArtifacts = @(Get-ChildItem -LiteralPath $cache -File -Filter 'sourcesAndCompiledWithNeoForge*output.jar')
    if ($minecraftArtifacts.Count -ne 1) { throw 'Pass -Minecraft with the exact generated NeoForge Minecraft artifact' }
    $Minecraft = $minecraftArtifacts[0].FullName
}
$files = @((Get-ChildItem -LiteralPath $Mods -File -Filter '*.jar').FullName) + @((Get-ChildItem -LiteralPath $Candidates -File -Filter '*.jar').FullName) + @((Get-ChildItem -LiteralPath "$project/build/compile-nested" -File -Filter '*.jar').FullName)
$files += $Minecraft
$classpath = $files -join [IO.Path]::PathSeparator
Add-Type -AssemblyName System.IO.Compression.FileSystem
$classIndex = @{}
foreach($file in $files){$jar=[IO.Compression.ZipFile]::OpenRead($file);try{foreach($entry in $jar.Entries){if($entry.FullName.EndsWith('.class')){$classIndex[$entry.FullName.Substring(0,$entry.FullName.Length-6).Replace('/','.')]=1}}}finally{$jar.Dispose()}}
$config = Get-Content -Raw -LiteralPath "$project/src/main/resources/cmi.mixins.json" | ConvertFrom-Json
$checked = 0
foreach ($mixin in $config.mixins) {
    $path = "$project/src/main/java/" + $config.package.Replace('.', '/') + '/' + $mixin.Replace('.', '/') + '.java'
    $source = Get-Content -LiteralPath $path -Raw
    $annotation = [regex]::Match($source, '@Mixin\((.*?)\)\s*public', 'Singleline').Groups[1].Value
    $targets = @([regex]::Matches($annotation, '"([\w.$]+)"') | ForEach-Object {$_.Groups[1].Value})
    foreach ($type in [regex]::Matches($annotation, '([\w.$]+)\.class')) {
        $short = $type.Groups[1].Value
        $imports = @([regex]::Matches($source, 'import ([\w.$*]+);') | ForEach-Object {$_.Groups[1].Value})
        $resolved = $imports | Where-Object { $_.EndsWith(".$short") } | Select-Object -First 1
        if(!$resolved){$resolved=$imports | Where-Object {$_.EndsWith('.*')} | ForEach-Object {$_.Substring(0,$_.Length-1)+$short} | Where-Object {$classIndex.ContainsKey($_)} | Select-Object -First 1}
        if (!$resolved) { throw "Cannot resolve mixin target $short in $path" }
        $targets += $resolved
    }
    foreach ($target in $targets) {
        $listing = (& javap -p -classpath $classpath $target 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Missing target $target : $listing" }
        foreach ($method in [regex]::Matches($source, '@(?:Inject|Redirect|WrapMethod)\(\s*method\s*=\s*"([^"(]+)')) {
            $name = $method.Groups[1].Value
            if ($listing -notmatch ([regex]::Escape($name) + '\(')) { throw "Method not declared on target: $target.$name" }
        }
        $checked++
    }
}
"OK: $checked target classes and declared injection method names. Runtime weaving and full descriptors not certified."
