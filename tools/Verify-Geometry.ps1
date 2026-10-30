$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Get-Content -Raw -LiteralPath "$root/src/main/java/local/cmi/core/TrajectorySafety.java"
foreach ($required in @('transformPositionInverse', 'plot.contains(chunkPos)', 'plot.getChunk(chunkPos)', 'chunk.getBlockState(pos)', 'ship.getLevel()', '--budget[0] < 0', 'inflate(blastRadius + 0.5 + 1.0e-6)')) {
    if (!$source.Contains($required)) { throw "Missing geometry guard: $required" }
}
if ($source.Contains('level.getBlockState(pos)')) { throw 'Plot state must come from its plot chunk' }
$output = Join-Path $root 'build/geometry-regression'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& javac --release 21 -d $output "$root/src/main/java/local/cmi/core/PlotGeometryBounds.java" "$PSScriptRoot/PlotGeometryBoundsRegression.java"
if ($LASTEXITCODE -ne 0) { throw 'Geometry regression compilation failed' }
& java -cp $output PlotGeometryBoundsRegression
if ($LASTEXITCODE -ne 0) { throw 'Geometry regression failed' }
Write-Output 'Geometry static scoping checks passed; Sable transformations require runtime validation.'
