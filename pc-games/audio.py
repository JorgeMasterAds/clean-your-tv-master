"""The PC's sound output follows the picture: while a game is on the TV, the TV's speakers play it; when the screens
come back, the output you had comes back too. Windows has no documented call for the default output, so this uses the
same internal one the Sound settings use (IPolicyConfig), through comtypes."""
import ctypes
import json
import os
from ctypes import POINTER, c_int, c_uint, c_void_p, c_wchar_p
from ctypes import wintypes

import comtypes
from comtypes import COMMETHOD, GUID, HRESULT, IUnknown

ERENDER, ACTIVE = 0, 1
ROLES = (0, 1, 2)   # console, multimedia, communications
# Names Windows gives the sound of a screen's cable: the TV is the one of these that is on after the switch.
DISPLAY_AUDIO = ("nvidia high definition audio", "nvidia hdmi", "hdmi", "display audio", "intel(r) display",
                 "amd high definition audio", "amd hdmi")


class IPolicyConfig(IUnknown):
    _iid_ = GUID("{f8679f50-850a-41cf-9c72-430f290290c8}")
    _methods_ = [COMMETHOD([], HRESULT, n) for n in (
        "GetMixFormat", "GetDeviceFormat", "ResetDeviceFormat", "SetDeviceFormat", "GetProcessingPeriod",
        "SetProcessingPeriod", "GetShareMode", "SetShareMode", "GetPropertyValue", "SetPropertyValue")] + [
        COMMETHOD([], HRESULT, "SetDefaultEndpoint", (["in"], c_wchar_p, "id"), (["in"], c_int, "role")),
        COMMETHOD([], HRESULT, "SetEndpointVisibility", (["in"], c_wchar_p, "id"), (["in"], c_int, "visible"))]


CLSID_POLICY = GUID("{870af99c-171d-4f9e-af0d-e63df40c2bc9}")


def _enumerator():
    from pycaw.pycaw import AudioUtilities
    return AudioUtilities.GetDeviceEnumerator()


def outputs():
    """[(id, name)] of the sound outputs that are on now."""
    from pycaw.pycaw import AudioUtilities
    out = []
    coll = _enumerator().EnumAudioEndpoints(ERENDER, ACTIVE)
    for i in range(coll.GetCount()):
        dev = coll.Item(i)
        out.append((dev.GetId(), AudioUtilities.CreateDevice(dev).FriendlyName or ""))
    return out


def default_id():
    try:
        return _enumerator().GetDefaultAudioEndpoint(ERENDER, 0).GetId()
    except Exception:
        return ""


def set_default(dev_id):
    policy = comtypes.CoCreateInstance(CLSID_POLICY, IPolicyConfig, comtypes.CLSCTX_ALL)
    for role in ROLES:
        policy.SetDefaultEndpoint(dev_id, role)


def pick_tv(tv_name):
    """The TV's output among the active ones: by the screen's name when Windows put it in the output's name, else the
    only display output that is on (the other screens are off by then)."""
    outs = outputs()
    name = (tv_name or "").lower()
    if name:
        for i, n in outs:
            if name in n.lower():
                return i, n
    shown = [(i, n) for i, n in outs if any(k in n.lower() for k in DISPLAY_AUDIO)]
    return shown[0] if len(shown) == 1 else (None, "")


def to_tv(tv_name, saved_path):
    """Makes the TV the default output; the one you had is saved to come back to. Returns a note for the log."""
    comtypes.CoInitialize()
    try:
        dev, name = pick_tv(tv_name)
        if not dev:
            return "sem saída de som da TV (saídas: %s)" % ", ".join(n for _, n in outputs())
        before = default_id()
        if before == dev:
            return "som já na TV (%s)" % name
        if not os.path.exists(saved_path):   # the first one only: asking twice must not forget the real one
            with open(saved_path, "w", encoding="utf-8") as f:
                json.dump({"id": before}, f)
        set_default(dev)
        return "som na TV (%s)" % name
    finally:
        comtypes.CoUninitialize()


def restore(saved_path):
    """Puts the saved output back (if it is still there). Returns a note, or '' when nothing was saved."""
    if not os.path.exists(saved_path):
        return ""
    comtypes.CoInitialize()
    try:
        try:
            with open(saved_path, encoding="utf-8") as f:
                before = json.load(f).get("id", "")
        except (OSError, ValueError):
            before = ""
        try:
            os.remove(saved_path)
        except OSError:
            pass
        if before and before in [i for i, _ in outputs()]:
            set_default(before)
            return "som devolvido"
        return "a saída de som anterior não está ligada: ficou como está"
    finally:
        comtypes.CoUninitialize()
