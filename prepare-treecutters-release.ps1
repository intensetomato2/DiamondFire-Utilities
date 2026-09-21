$ErrorActionPreference = "Stop"
$dfu = "modules\treecutters\build\dfu\treecutters-1.0.0.dfu"
if (!(Test-Path $dfu)) { throw "Build the module first with: .\gradlew :modules:treecutters:packageDfu" }
$hash = (Get-FileHash $dfu -Algorithm SHA256).Hash.ToLower()
$url = "https://github.com/intensetomato2/DFU-Modules/releases/download/treecutters-1.0.0/treecutters-1.0.0.dfu"
Write-Host "Release asset: $dfu"
Write-Host "download: $url"
Write-Host "sha256: $hash"
