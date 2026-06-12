$action = New-ScheduledTaskAction -Execute 'C:\Users\sande\AppData\Local\hermes\hermes-agent\apps\desktop\release\win-unpacked\Hermes.exe'
$trigger = New-ScheduledTaskTrigger -AtLogon
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
Register-ScheduledTask -TaskName 'HermesGateway' -Action $action -Trigger $trigger -Settings $settings -Description 'Hermes AI Gateway Auto-Start' -Force
Write-Host "Scheduled task 'HermesGateway' created successfully!"
Write-Host "Hermes Gateway will auto-start on every user login."
