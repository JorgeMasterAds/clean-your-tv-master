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


class _LUID(ctypes.Structure):
    _fields_ = [("low", wintypes.DWORD), ("high", wintypes.LONG)]


class _PATH(ctypes.Structure):   # DISPLAYCONFIG_PATH_INFO, 72 bytes
    _fields_ = [("src_adapter", _LUID), ("src_id", ctypes.c_uint32), ("src_mode", ctypes.c_uint32), ("src_flags", ctypes.c_uint32),
                ("tgt_adapter", _LUID), ("tgt_id", ctypes.c_uint32), ("tgt_mode", ctypes.c_uint32), ("tech", ctypes.c_uint32),
                ("rotation", ctypes.c_uint32), ("scaling", ctypes.c_uint32), ("rate_n", ctypes.c_uint32), ("rate_d", ctypes.c_uint32),
                ("scan", ctypes.c_uint32), ("available", wintypes.BOOL), ("tgt_flags", ctypes.c_uint32), ("flags", ctypes.c_uint32)]


class _MODE(ctypes.Structure):   # DISPLAYCONFIG_MODE_INFO, 64 bytes (the mode itself is not read here)
    _fields_ = [("type", ctypes.c_uint32), ("id", ctypes.c_uint32), ("adapter", _LUID), ("data", ctypes.c_byte * 48)]


class _HEADER(ctypes.Structure):
    _fields_ = [("type", ctypes.c_uint32), ("size", ctypes.c_uint32), ("adapter", _LUID), ("id", ctypes.c_uint32)]


class _SOURCE_NAME(ctypes.Structure):
    _fields_ = [("header", _HEADER), ("gdi", wintypes.WCHAR * 32)]


class _TARGET_NAME(ctypes.Structure):
    _fields_ = [("header", _HEADER), ("flags", ctypes.c_uint32), ("tech", ctypes.c_uint32), ("maker", ctypes.c_uint16),
                ("product", ctypes.c_uint16), ("connector", ctypes.c_uint32), ("friendly", wintypes.WCHAR * 64),
                ("path", wintypes.WCHAR * 128)]


def extend():
    """Every screen on, as Windows last had them extended. SetDisplayConfig does what DisplaySwitch /extend does,
    without leaving the Win+P panel open on the screen."""
    if user32.SetDisplayConfig(0, None, 0, None, 0x80 | 0x4):   # SDC_APPLY | SDC_TOPOLOGY_EXTEND
        subprocess.run(["DisplaySwitch.exe", "/extend"], creationflags=0x08000000)


def attach(model):
    """
    Turns on a screen that is off (as "Disconnect this display" left it), by its model id ("TOL2881"): Windows'
    "extend" only brings back the screens it had on last time, so the screen's display path is switched on here.
    """
    try:
        QDC_ALL, QDC_ACTIVE = 1, 2
        na, nma = ctypes.c_uint32(), ctypes.c_uint32()
        if user32.GetDisplayConfigBufferSizes(QDC_ACTIVE, ctypes.byref(na), ctypes.byref(nma)):
            return False
        active, amodes = (_PATH * na.value)(), (_MODE * nma.value)()
        if user32.QueryDisplayConfig(QDC_ACTIVE, ctypes.byref(na), active, ctypes.byref(nma), amodes, None):
            return False
        nall, nmall = ctypes.c_uint32(), ctypes.c_uint32()
        if user32.GetDisplayConfigBufferSizes(QDC_ALL, ctypes.byref(nall), ctypes.byref(nmall)):
            return False
        every, emodes = (_PATH * nall.value)(), (_MODE * nmall.value)()
        if user32.QueryDisplayConfig(QDC_ALL, ctypes.byref(nall), every, ctypes.byref(nmall), emodes, None):
            return False
        used = {(p.src_adapter.low, p.src_adapter.high, p.src_id) for p in active[:na.value]}
        on_targets = {(p.tgt_adapter.low, p.tgt_adapter.high, p.tgt_id) for p in active[:na.value]}
        pick = None
        for p in every[:nall.value]:
            if not p.available or (p.tgt_adapter.low, p.tgt_adapter.high, p.tgt_id) in on_targets:
                continue
            if (p.src_adapter.low, p.src_adapter.high, p.src_id) in used:
                continue
            tgt = _TARGET_NAME()
            tgt.header.type, tgt.header.size, tgt.header.adapter, tgt.header.id = 2, ctypes.sizeof(tgt), p.tgt_adapter, p.tgt_id
            if user32.DisplayConfigGetDeviceInfo(ctypes.byref(tgt)):
                continue
            if model.lower() in tgt.path.lower():
                pick = p
                break
        if pick is None:
            return False
        INVALID = 0xFFFFFFFF
        paths = (_PATH * (na.value + 1))()
        for i in range(na.value):
            paths[i] = active[i]
            paths[i].src_mode = paths[i].tgt_mode = INVALID
        paths[na.value] = pick
        paths[na.value].src_mode = paths[na.value].tgt_mode = INVALID
        paths[na.value].flags = 1   # DISPLAYCONFIG_PATH_ACTIVE
        # SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_SAVE_TO_DATABASE | SDC_ALLOW_CHANGES: Windows picks the modes
        return user32.SetDisplayConfig(na.value + 1, paths, 0, None, 0x80 | 0x20 | 0x200 | 0x400) == 0
    except Exception:
        return False


def active_monitors():
    """\\.\DISPLAYn -> (monitor device path, friendly name) for the screens on now, from Windows' display paths
    (QueryDisplayConfig). EnumDisplayDevices gave every output the same monitor on this PC, so it can't be trusted."""
    out = {}
    try:
        np, nm = ctypes.c_uint32(), ctypes.c_uint32()
        if user32.GetDisplayConfigBufferSizes(2, ctypes.byref(np), ctypes.byref(nm)):   # QDC_ONLY_ACTIVE_PATHS
            return out
        paths, modes = (_PATH * np.value)(), (_MODE * nm.value)()
        if user32.QueryDisplayConfig(2, ctypes.byref(np), paths, ctypes.byref(nm), modes, None):
            return out
        for pth in paths[:np.value]:
            src = _SOURCE_NAME()
            src.header.type, src.header.size, src.header.adapter, src.header.id = 1, ctypes.sizeof(src), pth.src_adapter, pth.src_id
            tgt = _TARGET_NAME()
            tgt.header.type, tgt.header.size, tgt.header.adapter, tgt.header.id = 2, ctypes.sizeof(tgt), pth.tgt_adapter, pth.tgt_id
            if user32.DisplayConfigGetDeviceInfo(ctypes.byref(src)) or user32.DisplayConfigGetDeviceInfo(ctypes.byref(tgt)):
                continue
            # "\\?\DISPLAY#TOL2881#5&...#{...}" -> "MONITOR\TOL2881\..." like EnumDisplayDevices' ids
            parts = tgt.path.split("#")
            out[src.gdi] = ("MONITOR\\%s\\%s" % (parts[1], parts[2]) if len(parts) > 2 else tgt.path, tgt.friendly)
    except Exception:
        pass
    return out


def devices():
    """[{name, adapter, monitor, attached, primary}] for every display output with a monitor."""
    out, i = [], 0
    active = active_monitors() or None
    while True:
        d = DISPLAY_DEVICE()
        d.cb = ctypes.sizeof(d)
        if not user32.EnumDisplayDevicesW(None, i, ctypes.byref(d), 0):
            break
        m = DISPLAY_DEVICE()
        m.cb = ctypes.sizeof(m)
        monitor = m.DeviceID if user32.EnumDisplayDevicesW(d.DeviceName, 0, ctypes.byref(m), 0) else ""
        attached = bool(d.StateFlags & ATTACHED)
        if attached and active is not None:
            monitor = active.get(d.DeviceName, (monitor, ""))[0]
        elif active is not None and physical({"adapter": d.DeviceString}):
            monitor = ""   # an output that is off: which monitor it would drive isn't known
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
    def snapshot():
        out = []
        for d in devices():
            if not d["attached"]:
                continue
            dm = mode(d["name"])
            if dm:
                out.append({"name": d["name"], "monitor": d["monitor"], "primary": d["primary"], "x": dm.dmPositionX,
                            "y": dm.dmPositionY, "w": dm.dmPelsWidth, "h": dm.dmPelsHeight, "hz": dm.dmDisplayFrequency,
                            "bits": dm.dmBitsPerPel})
        return out

    if not os.path.exists(saved_path):   # the real layout, saved before anything moves (switching the TV on can move it)
        with open(saved_path, "w", encoding="utf-8") as f:
            json.dump(snapshot(), f, indent=2)
    tv = find_tv(tv_monitor)
    if not tv or not tv["attached"]:
        # The TV may be off as a screen (only the monitor on, or "Disconnect this display"): its path is turned on.
        if not attach(hw(tv_monitor) if "\\" in tv_monitor else tv_monitor):
            extend()
        for _ in range(20):
            time.sleep(0.5)
            tv = find_tv(tv_monitor)
            if tv and tv["attached"]:
                break
        if not tv or not tv["attached"]:
            return "TV não está ligada ao PC como tela"
        time.sleep(1.5)
    if tv["primary"] and not any(d["attached"] and physical(d) and d["name"] != tv["name"] for d in devices()):
        return ""   # already only the TV
    layout = snapshot()
    with open(saved_path + ".tv", "w", encoding="utf-8") as f:   # the TV goes off again once the game is over
        f.write(hw(tv["monitor"]))
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
    try:
        with open(saved_path, encoding="utf-8") as f:
            layout = json.load(f)
    except (OSError, ValueError):
        return False
    want = {hw(d["monitor"]): d for d in layout if d.get("monitor")}
    physical_saved = [d for d in layout if d.get("monitor")]
    extend()
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
    for _ in range(10):   # the main screen (the LG) must be back before the layout is set; a tablet may come later
        if not primary or hw(primary["monitor"]) in now:
            break
        time.sleep(0.5)
        for d in devices():
            if d["attached"] and hw(d["monitor"]) in want and hw(d["monitor"]) not in now:
                now[hw(d["monitor"])] = d
    if primary and hw(primary["monitor"]) in now:
        px, py = primary["x"], primary["y"]
        tv_hw = ""
        try:
            with open(saved_path + ".tv", encoding="utf-8") as f:
                tv_hw = f.read().strip()
        except OSError:
            pass
        for key in sorted(now, key=lambda key: not want[key]["primary"]):
            if key == tv_hw and not want[key]["primary"]:
                continue   # the TV is switched off right after
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
        time.sleep(1)
        main = next((d for d in devices() if d["primary"]), None)
        if main and hw(main["monitor"]) != hw(primary["monitor"]) and hw(primary["monitor"]) in now:
            d = next((x for x in devices() if hw(x["monitor"]) == hw(primary["monitor"])), None)
            dm = mode(d["name"]) if d else None
            if dm:   # Windows kept another screen as the main one: hand the role back to the saved main screen
                dm.dmFields = DM_POSITION
                dm.dmPositionX = dm.dmPositionY = 0
                _set(d["name"], dm, CDS_UPDATEREGISTRY | CDS_SET_PRIMARY | CDS_NORESET)
                _apply()
    detach_tv(saved_path)
    try:
        os.remove(saved_path)
    except OSError:
        pass   # restored at the same time by the controller's close and the game watcher
    return True


def detach_tv(saved_path):
    """
    After the game the TV is no longer a PC screen (as "Disconnect this display" in Windows): only the main monitor
    and the tablet stay, and the windows left on the TV come back to the main monitor.
    """
    try:
        with open(saved_path + ".tv", encoding="utf-8") as f:
            tv = f.read().strip()
    except OSError:
        return
    for _ in range(3):
        on = [d for d in devices() if d["attached"] and hw(d["monitor"]) == tv]
        if not on:
            break
        others = [d for d in devices() if d["attached"] and physical(d) and hw(d["monitor"]) != tv]
        if not others:
            return   # the TV is the only screen left: never switch every screen off
        for d in on:
            dm = mode(d["name"])
            if not dm:
                continue
            dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT
            dm.dmPelsWidth = dm.dmPelsHeight = 0
            dm.dmPositionX = dm.dmPositionY = 0
            _set(d["name"], dm, CDS_UPDATEREGISTRY | CDS_NORESET)
        _apply()
        time.sleep(1)
    try:
        os.remove(saved_path + ".tv")
    except OSError:
        pass


def friendly(d):
    """A screen's name for people: the monitor's own name from Windows (EDID), else its model id."""
    try:
        import winreg
        parts = d["monitor"].split("\\")
        key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Enum\DISPLAY\%s" % parts[1])
        for i in range(winreg.QueryInfoKey(key)[0]):
            try:
                sub = winreg.OpenKey(key, winreg.EnumKey(key, i) + r"\Device Parameters")
                edid = winreg.QueryValueEx(sub, "EDID")[0]
            except OSError:
                continue
            for o in range(54, 126, 18):   # descriptor 0xFC = the monitor's name
                if edid[o:o + 3] == b"\x00\x00\x00" and edid[o + 3] == 0xFC:
                    name = edid[o + 5:o + 18].split(b"\n")[0].decode("ascii", "ignore").strip()
                    if name:
                        return name
    except Exception:
        pass
    return hw(d["monitor"])


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


def bring_to_front(pids):
    """Puts the biggest visible window of these processes in front, with the keyboard focus. Returns True if found."""
    pids = set(pids)
    best = [None, 0]
    proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def each(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value in pids:
            r = RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(r))
            area = max(0, r.right - r.left) * max(0, r.bottom - r.top)
            if area > best[1]:
                best[0], best[1] = hwnd, area
        return True

    user32.EnumWindows(proc(each), 0)
    hwnd = best[0]
    if not hwnd:
        return False
    kernel32 = ctypes.windll.kernel32
    fg = user32.GetForegroundWindow()
    fg_thread = user32.GetWindowThreadProcessId(fg, None) if fg else 0
    me = kernel32.GetCurrentThreadId()
    attached = bool(fg_thread) and fg_thread != me and user32.AttachThreadInput(me, fg_thread, True)
    try:
        user32.keybd_event(0x12, 0, 0, 0)   # an Alt tap: Windows then lets this background program set the focus
        user32.keybd_event(0x12, 0, 2, 0)
        user32.ShowWindow(hwnd, 9 if user32.IsIconic(hwnd) else 5)   # SW_RESTORE / SW_SHOW
        flags = 0x0001 | 0x0002 | 0x0040   # SWP_NOSIZE | SWP_NOMOVE | SWP_SHOWWINDOW
        user32.SetWindowPos(hwnd, -1, 0, 0, 0, 0, flags)   # topmost for a moment...
        user32.SetWindowPos(hwnd, -2, 0, 0, 0, 0, flags)   # ...then back to normal, now on top of the others
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
        user32.SetFocus(hwnd)
    finally:
        if attached:
            user32.AttachThreadInput(me, fg_thread, False)
    return user32.GetForegroundWindow() == hwnd


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
