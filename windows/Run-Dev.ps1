# Run-Dev.ps1
# Mirrors the WSL project source to a Windows build directory, then builds and launches
# the RuneLite dev harness with the plugin loaded.
#
# Usage (from PowerShell):
#   \\wsl$\Ubuntu\home\<you>\dzwnk-rune\tools\character-export\windows\Run-Dev.ps1
#
# Output log:
#   %USERPROFILE%\.runelite\character-exporter\dev-harness.log

$projectRoot = Split-Path -Parent $PSScriptRoot
$buildDir    = "C:\Users\Public\character-export"
$logFile     = "$env:USERPROFILE\.runelite\character-exporter\dev-harness.log"

Write-Host "==> Mirroring source to $buildDir"
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
robocopy $projectRoot $buildDir /MIR /XD ".gradle" "build" ".git" /XF "*.log" "*.class" | Out-Null

New-Item -ItemType Directory -Force -Path (Split-Path $logFile) | Out-Null

Write-Host "==> Building and launching (log: $logFile)"
Write-Host "    Press Ctrl+C to stop."
Push-Location $buildDir
try
{
    gradle run 2>&1 | Tee-Object -FilePath $logFile
}
finally
{
    Pop-Location
}
