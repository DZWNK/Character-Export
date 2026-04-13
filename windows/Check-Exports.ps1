# Check-Exports.ps1
# Lists the current contents of the character-exporter output directory.
# Run this to verify export files are being written after a dev session.

$exportDir = "$env:USERPROFILE\.runelite\character-exporter"

if (-not (Test-Path $exportDir))
{
    Write-Host "Export directory does not exist yet: $exportDir"
    Write-Host "Log in to RuneLite with the plugin loaded to create it."
    exit 0
}

Write-Host "==> $exportDir"
Get-ChildItem -Path $exportDir -Recurse | ForEach-Object {
    $indent = "  " * ($_.FullName.Split("\").Count - $exportDir.Split("\").Count - 1)
    $age    = [math]::Round((Get-Date).Subtract($_.LastWriteTime).TotalMinutes, 1)
    Write-Host "$indent$($_.Name)  ($age min ago)"
}
