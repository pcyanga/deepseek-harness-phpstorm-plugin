# DeepSeek Harness Web plugin - PhpStorm offline build
# NOTE: keep this file pure ASCII - Windows PowerShell 5.1 misreads UTF-8
# Chinese characters as GBK and breaks the parser.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1 [-NoInstall]
param(
  [switch]$NoInstall
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

$phpStorm = "D:\PhpStorm 2025.1.7"
$javac = "$phpStorm\jbr\bin\javac.exe"
$java = "$phpStorm\jbr\bin\java.exe"
$out = "$root\out"
$dist = "$root\build"
$pluginName = "ai-harness-web"
$pluginDir = "$dist\$pluginName"

if (-not (Test-Path $javac)) { Write-Host "javac not found: $javac"; exit 1 }
if (-not (Test-Path "$phpStorm\lib")) { Write-Host "PhpStorm lib not found: $phpStorm"; exit 1 }

Write-Host "== 1/4 clean & compile =="
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$out\classes" | Out-Null

$sources = @()
Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object {
  $p = $_.FullName
  $bytes = [System.IO.File]::ReadAllBytes($p)
  if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
    [System.IO.File]::WriteAllBytes($p, $bytes[3..($bytes.Length - 1)])
  }
  $sources += $p
}
Write-Host "source files: $($sources.Count)"

$cp = "$phpStorm\lib\*"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $javac -encoding UTF-8 -source 17 -target 17 -Xlint:none -cp $cp -d "$out\classes" $sources 2>&1 | ForEach-Object { Write-Host $_ }
$ErrorActionPreference = $prevEAP
if ($LASTEXITCODE -ne 0) { Write-Host "!! COMPILE FAILED (exit $LASTEXITCODE)"; exit 1 }
Write-Host "compile OK"
# bundle icons into the classes jar so IntelliJ resolves /icons/* from classpath
if (Test-Path "$root\resources\icons") {
  Copy-Item "$root\resources\icons" "$out\classes\" -Recurse -Force
  Write-Host "icons bundled into classes"
}
# bundle injected web scripts (e.g. /web/chip-overlay.js) into the classes jar
if (Test-Path "$root\resources\web") {
  Copy-Item "$root\resources\web" "$out\classes\" -Recurse -Force
  Write-Host "web scripts bundled into classes"
}

Write-Host "== 2/4 package =="
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$pluginDir\META-INF" | Out-Null
New-Item -ItemType Directory -Force -Path "$pluginDir\lib" | Out-Null
Copy-Item "$root\resources\*" "$pluginDir" -Recurse -Force

New-Item -ItemType Directory -Force -Path "$out\tools" | Out-Null
& $javac -encoding UTF-8 -source 17 -target 17 -Xlint:none -d "$out\tools" "$root\tools\MakeJar.java" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { Write-Host "!! MakeJar COMPILE FAILED"; exit 1 }
& $java -cp "$out\tools" MakeJar "$out\classes" "$pluginDir\lib\$pluginName.jar" 2>&1 | ForEach-Object { Write-Host $_ }
& $java -cp "$out\tools" MakeJar "$pluginDir" "$dist\$pluginName.zip" 2>&1 | ForEach-Object { Write-Host $_ }
Write-Host "package ready: $dist\$pluginName.zip"

if ($NoInstall) { Write-Host "== skip install =="; exit 0 }

Write-Host "== 3/3 install to PhpStorm =="
$installDir = "$env:APPDATA\JetBrains\PhpStorm2025.1\plugins\$pluginName"
for ($i = 0; $i -lt 5 -and (Test-Path $installDir); $i++) {
  Get-ChildItem $installDir -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Attributes = 'Normal' } catch {} }
  Remove-Item $installDir -Recurse -Force -ErrorAction SilentlyContinue
  if (Test-Path $installDir) { cmd /c "rd /s /q `"$installDir`"" }
  Start-Sleep -Milliseconds 400
}
if (Test-Path $installDir) { Write-Host "!! old install dir could not be removed"; exit 1 }
for ($i = 0; $i -lt 5 -and -not (Test-Path $installDir); $i++) {
  try { New-Item -ItemType Directory -Force -Path $installDir | Out-Null } catch {}
  Start-Sleep -Milliseconds 300
}
if (-not (Test-Path $installDir)) { Write-Host "!! install dir create failed"; exit 1 }
Copy-Item "$pluginDir\*" $installDir -Recurse -Force
Write-Host "installed to: $installDir"
Write-Host ""
Write-Host "DONE."
