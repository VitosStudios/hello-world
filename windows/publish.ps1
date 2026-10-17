<#
.SYNOPSIS
  Baut Tippli für Windows: Tests + eine einzelne, eigenständige Tippli.exe (win-x64, .NET 8 inklusive).

.EXAMPLE
  ./publish.ps1                 # Tests + publish nach windows/publish/Tippli.exe
  ./publish.ps1 -SkipTests
  ./publish.ps1 -Runtime win-arm64

  Gleichbedeutender Befehl ohne Skript (im Ordner windows/):
  dotnet publish src/Tippli.Windows -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o publish
#>
param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64",
    [string]$Output = "publish",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $SkipTests) {
    dotnet test tests/Tippli.Core.Tests -c $Configuration
    if ($LASTEXITCODE -ne 0) { throw "Tests fehlgeschlagen." }
}

dotnet publish src/Tippli.Windows -c $Configuration -r $Runtime --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $Output
if ($LASTEXITCODE -ne 0) { throw "Publish fehlgeschlagen." }

Write-Host ""
Write-Host "Fertig: $(Join-Path (Resolve-Path $Output) 'Tippli.exe')"
