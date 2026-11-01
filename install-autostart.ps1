# Builds the app and registers it to start in the background every time you sign in to Windows.
# Run from PowerShell:  .\install-autostart.ps1        Remove with:  .\install-autostart.ps1 -Uninstall
param([switch]$Uninstall)

$ErrorActionPreference = 'Stop'
$taskName = 'TeslaMileageTracker'

if ($Uninstall) {
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Host "Removed $taskName."
    return
}

$root = $PSScriptRoot
mvn -q -B -f "$root\pom.xml" -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

$javaw = (Get-Command javaw).Source
$jar = Join-Path $root 'target\tesla-tracking.jar'
$action = New-ScheduledTaskAction -Execute $javaw -Argument "-jar `"$jar`"" -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Start-ScheduledTask -TaskName $taskName
Write-Host "Tesla Miles is running and will start at every sign-in: http://localhost:8080"
