Set oWS = WScript.CreateObject("WScript.Shell")
sLinkFile = oWS.SpecialFolders("Startup") & "\HermesGateway.lnk"
Set oLink = oWS.CreateShortcut(sLinkFile)
oLink.TargetPath = "C:\Users\sande\AppData\Local\hermes\hermes-agent\apps\desktop\release\win-unpacked\Hermes.exe"
oLink.WorkingDirectory = "C:\Users\sande\AppData\Local\hermes\hermes-agent\apps\desktop\release\win-unpacked"
oLink.Description = "Hermes AI Gateway Auto-Start"
oLink.Save