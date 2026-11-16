' Starts the PC games agent with no window (put a shortcut to this file in shell:startup).
Set sh = CreateObject("WScript.Shell")
dir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
sh.Run "python """ & dir & "\games_agent.py""", 0, False
