param(
  [string]$DataDirectory = "data",
  [switch]$Apply
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $DataDirectory).Path
$legacyDatabase = Join-Path $root "harvex.mv.db"
$targetDatabase = Join-Path $root "contextcrate.mv.db"

if (-not (Test-Path -LiteralPath $legacyDatabase)) {
  throw "No legacy H2 database found at $legacyDatabase"
}
if (Test-Path -LiteralPath $targetDatabase) {
  throw "The target database already exists at $targetDatabase. Nothing was changed."
}

Write-Host "Source: $legacyDatabase"
Write-Host "Target: $targetDatabase"
Write-Host "Stop every application/worker process and back up the complete data directory first."

if (-not $Apply) {
  Write-Host "Dry run only. Re-run with -Apply to copy the database."
  exit 0
}

Copy-Item -LiteralPath $legacyDatabase -Destination $targetDatabase
$legacyTrace = Join-Path $root "harvex.trace.db"
$targetTrace = Join-Path $root "contextcrate.trace.db"
if ((Test-Path -LiteralPath $legacyTrace) -and -not (Test-Path -LiteralPath $targetTrace)) {
  Copy-Item -LiteralPath $legacyTrace -Destination $targetTrace
}

Write-Host "Database copied. Rehoming filesystem artifacts into the Legacy crate namespace."
$artifactRoot = Join-Path $root "artifacts"
$legacyCrateRoot = Join-Path $artifactRoot "crates\00000000-0000-0000-0000-000000000001"
if (Test-Path -LiteralPath $artifactRoot) {
  New-Item -ItemType Directory -Path $legacyCrateRoot -Force | Out-Null
  $sourceFiles = Get-ChildItem -LiteralPath $artifactRoot -File -Recurse |
    Where-Object { -not $_.FullName.StartsWith((Join-Path $artifactRoot "crates"), [System.StringComparison]::OrdinalIgnoreCase) }
  foreach ($sourceFile in $sourceFiles) {
    $relative = [System.IO.Path]::GetRelativePath($artifactRoot, $sourceFile.FullName)
    $destination = [System.IO.Path]::GetFullPath((Join-Path $legacyCrateRoot $relative))
    if (-not $destination.StartsWith([System.IO.Path]::GetFullPath($legacyCrateRoot), [System.StringComparison]::OrdinalIgnoreCase)) {
      throw "Unsafe artifact path detected: $relative"
    }
    $destinationDirectory = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
    if (Test-Path -LiteralPath $destination) {
      if ((Get-FileHash -LiteralPath $sourceFile.FullName -Algorithm SHA256).Hash -ne
          (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash) {
        throw "Artifact conflict at $destination. Nothing was overwritten."
      }
      continue
    }
    Copy-Item -LiteralPath $sourceFile.FullName -Destination $destination
    if ((Get-FileHash -LiteralPath $sourceFile.FullName -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash) {
      throw "Checksum verification failed for $relative"
    }
  }
}

Write-Host "Database and artifacts copied with checksum verification. Sources remain intact for rollback."
Write-Host "Start ContextCrate and wait for Flyway and the Legacy crate migration to finish."
