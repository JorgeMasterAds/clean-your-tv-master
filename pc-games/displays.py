"""
The PC's screens while a game plays on the TV: the TV becomes the only screen with the game (the main display), the
other physical monitors go dark, and everything goes back as it was when the game closes.

Windows API through ctypes (ChangeDisplaySettingsEx), standard library only. The layout before the switch is saved in
%USERPROFILE%\\.cleanyourtv\\displays-saved.json, so `python games_agent.py --restore` can always put it back.
Screens of other kinds (spacedesk, virtual displays) are left as they are.
"""
import ctypes
import json
import os
import subprocess
import time
from ctypes import wintypes

user32 = ctypes.windll.user32 if os.name == "nt" else None

ENUM_CURRENT, ENUM_REGISTRY = -1, -2
DM_POSITION, DM_PELSWIDTH, DM_PELSHEIGHT, DM_DISPLAYFREQUENCY, DM_BITSPERPEL = 0x20, 0x80000, 0x100000, 0x400000, 0x40000
CDS_UPDATEREGISTRY, CDS_SET_PRIMARY, CDS_NORESET = 0x1, 0x10, 0x10000000
ATTACHED, PRIMARY = 0x1, 0x4


class DISPLAY_DEVICE(ctypes.Structure):
    _fields_ = [("cb", wintypes.DWORD), ("DeviceName", wintypes.WCHAR * 32), ("DeviceString", wintypes.WCHAR * 128),
                ("StateFlags", wintypes.DWORD), ("DeviceID", wintypes.WCHAR * 128), ("DeviceKey", wintypes.WCHAR * 128)]


class DEVMODE(ctypes.Structure):
    _fields_ = [("dmDeviceName", wintypes.WCHAR * 32), ("dmSpecVersion", wintypes.WORD), ("dmDriverVersion", wintypes.WORD),
                ("dmSize", wintypes.WORD), ("dmDriverExtra", wintypes.WORD), ("dmFields", wintypes.DWORD),
                ("dmPositionX", wintypes.LONG), ("dmPositionY", wintypes.LONG), ("dmDisplayOrientation", wintypes.DWORD),
                ("dmDisplayFixedOutput", wintypes.DWORD), ("dmColor", ctypes.c_short), ("dmDuplex", ctypes.c_short),
                ("dmYResolution", ctypes.c_short), ("dmTTOption", ctypes.c_short), ("dmCollate", ctypes.c_short),
                ("dmFormName", wintypes.WCHAR * 32), ("dmLogPixels", wintypes.WORD), ("dmBitsPerPel", wintypes.DWORD),
                ("dmPelsWidth", wintypes.DWORD), ("dmPelsHeight", wintypes.DWORD), ("dmDisplayFlags", wintypes.DWORD),
                ("dmDisplayFrequency", wintypes.DWORD), ("dmICMMethod", wintypes.DWORD), ("dmICMIntent", wintypes.DWORD),
                ("dmMediaType", wintypes.DWORD), ("dmDitherType", wintypes.DWORD), ("dmReserved1", wintypes.DWORD),
                ("dmReserved2", wintypes.DWORD), ("dmPanningWidth", wintypes.DWORD), ("dmPanningHeight", wintypes.DWORD)]


def devices():
    """[{name, adapter, monitor, attached, primary}] for every display output with a monitor."""
    out, i = [], 0
    while True:
        d = DISPLAY_DEVICE()
        d.cb = ctypes.sizeof(d)
        if not user32.EnumDisplayDevicesW(None, i, ctypes.byref(d), 0):
            break
        m = DISPLAY_DEVICE()
        m.cb = ctypes.sizeof(m)
        monitor = m.DeviceID if user32.EnumDisplayDevicesW(d.DeviceName, 0, ctypes.byref(m), 0) else ""
        if monitor:
            out.append({"name": d.DeviceName, "adapter": d.DeviceString, "monitor": monitor,
                        "attached": bool(d.StateFlags & ATTACHED), "primary": bool(d.StateFlags & PRIMARY)})
        i += 1
    return out


def mode(name, which=ENUM_CURRENT):
    dm = DEVMODE()
    dm.dmSize = ctypes.sizeof(DEVMODE)
    if not user32.EnumDisplaySettingsW(name, which, ctypes.byref(dm)):
        return None
    return dm


def _set(name, dm, flags):
    return user32.ChangeDisplaySettingsExW(name, ctypes.byref(dm) if dm else None, None, flags, None)


def _apply():
    user32.ChangeDisplaySettingsExW(None, None, None, 0, None)


def physical(d):
    """A monitor on the graphics card (not spacedesk or another virtual screen)."""
    a = d["adapter"].lower()
    return not any(v in a for v in ("spacedesk", "virtual", "indirect", "parsec", "remote"))


def find_tv(tv_monitor):
    for d in devices():
        if tv_monitor and tv_monitor.lower() in d["monitor"].lower():
            return d
    return None


def tv_only(tv_monitor, saved_path):
    """The TV becomes the main screen, at the top left; the other physical monitors are switched off."""
    tv = find_tv(tv_monitor)
    if not tv or not tv["attached"]:
        return "TV não está ligada ao PC como tela"
    if tv["primary"] and not any(d["attached"] and physical(d) and d["name"] != tv["name"] for d in devices()):
        return ""   # already only the TV
    layout = []
    for d in devices():
        if not d["attached"]:
            continue
        dm = mode(d["name"])
        if dm:
            layout.append({"name": d["name"], "monitor": d["monitor"], "primary": d["primary"], "x": dm.dmPositionX,
                           "y": dm.dmPositionY, "w": dm.dmPelsWidth, "h": dm.dmPelsHeight, "hz": dm.dmDisplayFrequency,
                           "bits": dm.dmBitsPerPel})
    if not os.path.exists(saved_path):   # keep the first saved layout (the real one) until it is restored
        with open(saved_path, "w", encoding="utf-8") as f:
            json.dump(layout, f, indent=2)
    tvm = mode(tv["name"])
    ox, oy = tvm.dmPositionX, tvm.dmPositionY
    tvm.dmFields = DM_POSITION
    tvm.dmPositionX, tvm.dmPositionY = 0, 0
    _set(tv["name"], tvm, CDS_UPDATEREGISTRY | CDS_SET_PRIMARY | CDS_NORESET)
    for d in layout:
        if d["name"] == tv["name"]:
            continue
        dm = mode(d["name"])
        if not dm:
            continue
        if physical(next(x for x in devices() if x["name"] == d["name"])):
            dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT
            dm.dmPelsWidth = dm.dmPelsHeight = 0
            dm.dmPositionX = dm.dmPositionY = 0
        else:   # virtual screens stay on, moved along with the new origin
            dm.dmFields = DM_POSITION
            dm.dmPositionX, dm.dmPositionY = d["x"] - ox, d["y"] - oy
        _set(d["name"], dm, CDS_UPDATEREGISTRY | CDS_NORESET)
    _apply()
    return ""


def hw(monitor):
    """The monitor's model id (GSM76FA, TOL2881): stays the same when Windows renumbers DISPLAY1, DISPLAY2..."""
    parts = (monitor or "").split("\\")
    return parts[1].upper() if len(parts) > 1 else (monitor or "").upper()


def _attached_physical():
    return [d for d in devices() if d["attached"] and physical(d)]


def restore(saved_path):
    """
    Puts back the layout saved before the game. Windows may renumber the screens (the TV became DISPLAY1 once), so
    nothing here goes by name: Windows' own "extend" brings every screen back as it last was, then each monitor gets
    its saved size, position and main-screen role by its model id.
    """
    if not os.path.exists(saved_path):
        return False
    with open(saved_path, encoding="utf-8") as f:
        layout = json.load(f)
    want = {hw(d["monitor"]): d for d in layout if d.get("monitor")}
    physical_saved = [d for d in layout if d.get("monitor")]
    subprocess.run(["DisplaySwitch.exe", "/extend"], creationflags=0x08000000)
    for _ in range(20):   # up to 10 s for the monitors to come back
        time.sleep(0.5)
        if len(_attached_physical()) >= min(2, len(physical_saved)):
            break
    time.sleep(1.5)
    now = {}
    for d in devices():
        if d["attached"] and hw(d["monitor"]) in want and hw(d["monitor"]) not in now:
            now[hw(d["monitor"])] = d
    primary = next((d for d in layout if d["primary"]), None)
    if primary and hw(primary["monitor"]) in now and len(now) == len(want):
        px, py = primary["x"], primary["y"]
        for key in sorted(now, key=lambda key: not want[key]["primary"]):
            d, s = now[key], want[key]
            dm = mode(d["name"])
            if not dm:
                continue
            dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY | DM_BITSPERPEL
            dm.dmPositionX, dm.dmPositionY = s["x"] - px, s["y"] - py
            dm.dmPelsWidth, dm.dmPelsHeight = s["w"], s["h"]
            dm.dmDisplayFrequency, dm.dmBitsPerPel = s["hz"], s["bits"] or 32
            _set(d["name"], dm, CDS_UPDATEREGISTRY | CDS_NORESET | (CDS_SET_PRIMARY if s["primary"] else 0))
        _apply()
    os.remove(saved_path)
    return True


def wait_tv_only(tv_monitor, seconds=12):
    """After the switch: waits until the TV is the main screen and the only physical one, and a little more for the
    picture to settle, so the game opens its window there and not on a screen that is going away."""
    for _ in range(int(seconds * 2)):
        tv = find_tv(tv_monitor)
        others = [d for d in _attached_physical() if hw(d["monitor"]) != hw(tv["monitor"])] if tv else []
        if tv and tv["primary"] and tv["attached"] and not others:
            time.sleep(3)
            return True
        time.sleep(0.5)
    return False


class RECT(ctypes.Structure):
    _fields_ = [("left", wintypes.LONG), ("top", wintypes.LONG), ("right", wintypes.LONG), ("bottom", wintypes.LONG)]


class MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", RECT), ("rcWork", RECT), ("dwFlags", wintypes.DWORD)]


def fullscreen_window(pids):
    """True when one of these processes shows a window that covers its whole screen (fullscreen or borderless)."""
    pids = set(pids)
    found = [False]
    proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def each(hwnd, _):
        if not user32.IsWindowVisible(hwnd) or user32.IsIconic(hwnd):
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value not in pids:
            return True
        r, mi = RECT(), MONITORINFO()
        mi.cbSize = ctypes.sizeof(MONITORINFO)
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        if not user32.GetMonitorInfoW(user32.MonitorFromWindow(hwnd, 2), ctypes.byref(mi)):
            return True
        m = mi.rcMonitor
        if r.left <= m.left and r.top <= m.top and r.right >= m.right and r.bottom >= m.bottom:
            found[0] = True
            return False
        return True

    user32.EnumWindows(proc(each), 0)
    return found[0]


def processes():
    """[(pid, path)] of every process this user can see."""
    arr = (wintypes.DWORD * 4096)()
    needed = wintypes.DWORD()
    psapi.EnumProcesses(ctypes.byref(arr), ctypes.sizeof(arr), ctypes.byref(needed))
    out = []
    for pid in arr[:needed.value // ctypes.sizeof(wintypes.DWORD)]:
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not h:
            continue
        buf = ctypes.create_unicode_buffer(1024)
        size = wintypes.DWORD(1024)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            out.append((int(pid), buf.value))
        kernel32.CloseHandle(h)
    return out


# MARK: - Is a game still running?

PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
kernel32 = ctypes.windll.kernel32 if os.name == "nt" else None
psapi = ctypes.windll.psapi if os.name == "nt" else None


def process_paths():
    arr = (wintypes.DWORD * 4096)()
    needed = wintypes.DWORD()
    psapi.EnumProcesses(ctypes.byref(arr), ctypes.sizeof(arr), ctypes.byref(needed))
    paths = []
    for pid in arr[:needed.value // ctypes.sizeof(wintypes.DWORD)]:
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not h:
            continue
        buf = ctypes.create_unicode_buffer(1024)
        size = wintypes.DWORD(1024)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            paths.append(buf.value)
        kernel32.CloseHandle(h)
    return paths
