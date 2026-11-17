# Build, install on the Thor over Wi-Fi, launch, and stream this app's logs.
# Usage:  .\deploy.ps1            (uses whatever device adb is connected to)
#         .\deploy.ps1 -NoLogs    (skip the log stream)
param([switch]$NoLogs)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (adb devices | Select-String "`tdevice$")) {
    Write-Host "No device connected. On the Thor: Developer options > Wireless debugging, then:" -ForegroundColor Yellow
    Write-Host "  adb connect <ip>:<port shown under 'IP address & Port'>" -ForegroundColor Yellow
    exit 1
}

.\gradlew.bat installDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

adb shell am start -n dev.thorcorners/.MainActivity | Out-Null
Write-Host "Installed and launched." -ForegroundColor Green

if (-not $NoLogs) {
    adb logcat -c
    adb logcat ThorCorners:V AndroidRuntime:E *:S
}
