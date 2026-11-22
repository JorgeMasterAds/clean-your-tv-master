' Starts the PC games agent with no window (put a shortcut to this file in shell:startup).
' Also its safety net: if the agent stops (a crash, a Python error), it starts again within seconds,
' so the TV never finds the PC without it. Only one copy runs, even when this file is opened twice.
Set sh = CreateObject("WScript.Shell")
dir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
Set wmi = GetObject("winmgmts:\\.\root\cimv2")

Function Running(pattern)
    Running = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE CommandLine LIKE '%" & pattern & "%' AND Name LIKE 'python%'").Count > 0
End Function

Function OtherKeeper()
    Set me_ = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE Name = 'wscript.exe' AND CommandLine LIKE '%iniciar-jogos.vbs%'")
    OtherKeeper = me_.Count > 1
End Function

If OtherKeeper() Then WScript.Quit

Do
    If Not Running("games_agent.py") Then
        sh.Run "python """ & dir & "\games_agent.py""", 0, False
    End If
    WScript.Sleep 15000
Loop
