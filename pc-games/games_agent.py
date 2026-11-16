"""
Clean Your TV Master: game shelf agent for a Windows PC.

Lists the games installed on this PC (Start menu shortcuts that point into a games folder, Epic, Steam, Riot,
Battle.net), finds a cover for each, and opens one when the TV asks. The TV then switches its HDMI input to the PC.

Run:      pythonw games_agent.py          (no window; add a shortcut to it in shell:startup to start with Windows)
Config:   %USERPROFILE%\\.cleanyourtv\\games-agent.json   (created on first run: port, token, extra folders, covers)
Pair TV:  python games_agent.py --tv-config   prints the "games_pc" block for the TV's config.json

Only answers on the home network (private addresses) and only with the token. It can only open games from its own
list, never a path sent by the TV. Python 3.8+, standard library only.
"""
import hmac
import ipaddress
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.join(os.path.expanduser("~"), ".cleanyourtv")
CONFIG = os.path.join(HOME, "games-agent.json")
COVERS = os.path.join(HOME, "game-covers")
LOG = os.path.join(HOME, "games-agent.log")
SAVED = os.path.join(HOME, "displays-saved.json")

DEFAULTS = {
    "port": 48500,
    "token": "",
    "game_dirs": [],             # added to every <drive>:\Games folder found
    "exclude": ["vortex", "uninstall", "desinstalar", "overwolf", "optiscaler", "setup", "redist", "dlss",
                "riot client", "rockstar games launcher"],
    "tv_monitor": "",            # the TV's monitor id (e.g. "TOL2881"): the game plays only on it, then the screens come back
    "before_launch": "",         # optional: a shortcut or program run first (e.g. switch the PC's output to the TV)
    "covers": {},                # "Game name": "https://... or C:\\path\\cover.jpg"
    "search": {},                # "Game name": "name to look its cover up by" (e.g. the English title)
    "names": {},                 # "Name found": "name to show"
    "hidden": [],                # game names to leave out
}

STEAM_SKIP = {"228980"}         # Steamworks Common Redistributables


def log(msg):
    try:
        os.makedirs(HOME, exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n")
    except OSError:
        pass


def load_config():
    os.makedirs(HOME, exist_ok=True)
    own = {}
    if os.path.exists(CONFIG):
        with open(CONFIG, encoding="utf-8") as f:
            own = json.load(f)
    if not own.get("token"):   # only your own keys are saved: the defaults stay here and can improve
        own["token"] = secrets.token_urlsafe(24)
        with open(CONFIG, "w", encoding="utf-8") as f:
            json.dump(own, f, indent=2, ensure_ascii=False)
    cfg = dict(DEFAULTS)
    cfg.update(own)
    cfg["exclude"] = DEFAULTS["exclude"] + own.get("exclude", [])
    return cfg


# MARK: - Finding the games

def game_dirs(cfg):
    dirs = []
    for d in "CDEFGHIJ":
        p = d + ":\\Games"
        if os.path.isdir(p):
            dirs.append(p)
    dirs += [p for p in cfg.get("game_dirs", []) if os.path.isdir(p)]
    return [os.path.normcase(os.path.normpath(p)) for p in dirs]


def read_shortcuts():
    """Start menu (yours, then everyone's) and Desktop shortcuts, with their targets (read by Windows itself)."""
    ps = r"""
$sh = New-Object -ComObject WScript.Shell
$roots = @(
  @{p=[Environment]::GetFolderPath('Programs'); d=3},
  @{p=[Environment]::GetFolderPath('Desktop'); d=1},
  @{p=[Environment]::GetFolderPath('CommonPrograms'); d=3},
  @{p=[Environment]::GetFolderPath('CommonDesktopDirectory'); d=1})
$out = @()
foreach ($r in $roots) {
  if (-not $r.p -or -not (Test-Path $r.p)) { continue }
  Get-ChildItem $r.p -Filter *.lnk -Recurse -Depth $r.d -ErrorAction SilentlyContinue | ForEach-Object {
    try { $l = $sh.CreateShortcut($_.FullName)
      $out += [pscustomobject]@{ file=$_.FullName; name=$_.BaseName; folder=$_.Directory.Name; target=$l.TargetPath; args=$l.Arguments; icon=$l.IconLocation }
    } catch {}
  }
}
$out | ConvertTo-Json -Compress
"""
    flags = 0x08000000 if os.name == "nt" else 0   # CREATE_NO_WINDOW
    r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", creationflags=flags, timeout=60)
    text = r.stdout.strip()
    if not text:
        return []
    data = json.loads(text)
    return data if isinstance(data, list) else [data]


def pretty(name):
    name = re.sub(r"[_]+", " ", name)
    name = re.sub(r"(?<=[a-z])(?=[A-Z0-9])", " ", name)   # DetroitBecomeHuman -> Detroit Become Human
    return re.sub(r"\s+", " ", name).strip()


def norm(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


def steam_games():
    out = []
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam")
        steam = winreg.QueryValueEx(k, "SteamPath")[0]
    except Exception:
        return out
    libs = {os.path.normpath(steam)}
    for vdf in (os.path.join(steam, "config", "libraryfolders.vdf"), os.path.join(steam, "steamapps", "libraryfolders.vdf")):
        if os.path.exists(vdf):
            with open(vdf, encoding="utf-8", errors="replace") as f:
                for m in re.finditer(r'"path"\s+"([^"]+)"', f.read()):
                    libs.add(os.path.normpath(m.group(1).replace("\\\\", "\\")))
    for d in "CDEFGHIJ":   # libraries Steam forgot about
        for p in (d + ":\\SteamLibrary", d + ":\\Games\\SteamLibrary"):
            if os.path.isdir(p):
                libs.add(os.path.normpath(p))
    seen = set()
    for lib in libs:
        apps = os.path.join(lib, "steamapps")
        if not os.path.isdir(apps):
            continue
        for fn in os.listdir(apps):
            if not (fn.startswith("appmanifest_") and fn.endswith(".acf")):
                continue
            with open(os.path.join(apps, fn), encoding="utf-8", errors="replace") as f:
                t = f.read()
            appid = re.search(r'"appid"\s+"(\d+)"', t)
            name = re.search(r'"name"\s+"([^"]+)"', t)
            if not appid or not name or appid.group(1) in STEAM_SKIP or appid.group(1) in seen:
                continue
            seen.add(appid.group(1))
            out.append({"name": name.group(1), "source": "Steam", "launch": "steam://rungameid/" + appid.group(1),
                        "steam": appid.group(1)})
    return out


def epic_games():
    out = []
    d = r"C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests"
    if not os.path.isdir(d):
        return out
    for fn in os.listdir(d):
        if not fn.endswith(".item"):
            continue
        try:
            with open(os.path.join(d, fn), encoding="utf-8") as f:
                m = json.load(f)
        except (OSError, ValueError):
            continue
        if not m.get("AppName") or "UE_" in m.get("AppName", ""):
            continue
        out.append({"name": m.get("DisplayName", m["AppName"]), "source": "Epic",
                    "launch": "com.epicgames.launcher://apps/%s?action=launch&silent=true" % m["AppName"]})
    return out


def battlenet():
    for base in ("E:\\Games\\Battle.net", "C:\\Program Files (x86)\\Battle.net", "D:\\Games\\Battle.net"):
        exe = os.path.join(base, "Battle.net Launcher.exe")
        if os.path.exists(exe):
            return [{"name": "Battle.net", "source": "Battle.net", "launch": exe}]
    return []


def scan(cfg):
    dirs = game_dirs(cfg)
    exclude = [e.lower() for e in cfg.get("exclude", [])]
    hidden = {norm(h) for h in cfg.get("hidden", [])}
    games, keys, names = [], set(), set()

    renames = cfg.get("names", {})

    def add(g, key):
        g["name"] = renames.get(g["name"], g["name"])   # your own name for it ("Hookshot" -> the game it runs)
        n = norm(g["name"])
        if not n or n in hidden or key in keys or n in names:
            return
        keys.add(key)
        names.add(n)
        games.append(g)

    try:
        links = read_shortcuts()
    except Exception as e:
        log("shortcuts: %s" % e)
        links = []
    # A folder of your own ("Meus Jogos") names games best: it goes first.
    links.sort(key=lambda l: 0 if "jogo" in (l.get("folder") or "").lower() or "game" in (l.get("folder") or "").lower() else 1)
    for l in links:
        target, args = l.get("target") or "", l.get("args") or ""
        both = (target + " " + args).lower()
        if (l.get("folder") or "").lower() in ("startup", "inicializar"):   # runs at sign-in, not a game
            continue
        if any(e in l["name"].lower() or e in os.path.basename(target).lower() for e in exclude):
            continue
        in_games = any(d in os.path.normcase(both) for d in dirs)
        store = "epicgames.launcher://" in both or "--launch-product=" in both or "steam://" in both
        if not (in_games or store):
            continue
        source = "Epic" if "epicgames" in both else "Riot" if "launch-product" in both else "Steam" if "steam://" in both else "PC"
        add({"name": pretty(l["name"]), "source": source, "launch": l["file"], "target": target}, os.path.normcase(target) + "|" + args.lower())
    for g in epic_games() + steam_games() + battlenet():
        add(g, g["launch"].lower())
    for g in games:   # stable ids: the TV keeps covers by address, so an id must always mean the same game
        g["id"] = norm(g["name"])
    return sorted(games, key=lambda g: g["name"].lower())


# MARK: - Covers (portrait art from the Steam store, cached on disk)

UA = {"User-Agent": "CleanYourTV-Games/1.0"}


def fetch(url, timeout=12):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def steam_portrait(appid):
    """The game's portrait capsule, wherever Steam keeps it now (newer games use hashed paths)."""
    q = json.dumps({"ids": [{"appid": int(appid)}], "context": {"language": "english", "country_code": "US"},
                    "data_request": {"include_assets": True}})
    try:
        data = json.loads(fetch("https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?input_json=" + urllib.parse.quote(q)))
        assets = data["response"]["store_items"][0]["assets"]
        fmt = assets["asset_url_format"]
        for key in ("library_capsule_2x", "library_capsule", "main_capsule", "header"):
            if assets.get(key):
                return "https://shared.cloudflare.steamstatic.com/store_item_assets/" + fmt.replace("${FILENAME}", assets[key])
    except Exception as e:
        log("steam assets %s: %s" % (appid, e))
    return None


def steam_id_for(name):
    name = re.sub(r"\b(\d{3,4}p|4k|enhanced|deluxe edition|premium edition)\b", "", name, flags=re.I).strip() or name
    q = urllib.parse.quote(name)
    try:
        data = json.loads(fetch("https://store.steampowered.com/api/storesearch/?term=%s&l=english&cc=US" % q))
    except Exception:
        return None
    items = data.get("items") or []
    want = norm(name)
    for it in items:   # the closest name first
        if norm(it.get("name", "")) == want:
            return str(it["id"])
    for it in items:   # only a clear match: a wrong cover is worse than the name on a plain card
        got = norm(it.get("name", ""))
        if len(want) >= 6 and len(got) >= 6 and (got.startswith(want) or want.startswith(got)) and abs(len(got) - len(want)) <= 10:
            return str(it["id"])
    return None


def cover_path(g):
    return os.path.join(COVERS, norm(g["name"]) + ".jpg")


def make_cover(g, cfg):
    os.makedirs(COVERS, exist_ok=True)
    path = cover_path(g)
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        return path
    own = cfg.get("covers", {}).get(g["name"])
    try:
        if own:
            data = fetch(own) if own.startswith("http") else open(own, "rb").read()
        else:
            appid = g.get("steam") or steam_id_for(cfg.get("search", {}).get(g["name"], g["name"]))
            if not appid:
                return None
            data = None
            urls = [steam_portrait(appid), "https://cdn.cloudflare.steamstatic.com/steam/apps/%s/library_600x900.jpg" % appid]
            for url in [u for u in urls if u]:
                try:
                    data = fetch(url)
                    if data and len(data) > 1000:
                        break
                except Exception:
                    data = None
            if not data:
                return None
        with open(path, "wb") as f:
            f.write(data)
        return path
    except Exception as e:
        log("cover %s: %s" % (g["name"], e))
        return None


# MARK: - Wide art for the TV's background (Steam's library hero), and when each game was last played

ART = os.path.join(HOME, "game-art")
ART_OWN = os.path.join(HOME, "game-art-own")
PLAYED = os.path.join(HOME, "games-played.json")


def make_art(g, cfg):
    """A wide picture of the game for the TV's background: your own (game-art-own/<name>.jpg) or Steam's hero."""
    for d in (ART_OWN, ART):
        p = os.path.join(d, norm(g["name"]) + ".jpg")
        if os.path.exists(p) and os.path.getsize(p) > 1000:
            return p
    miss = os.path.join(ART, norm(g["name"]) + ".none")
    if os.path.exists(miss) and time.time() - os.path.getmtime(miss) < 7 * 86400:
        return None
    os.makedirs(ART, exist_ok=True)
    appid = g.get("steam") or steam_id_for(cfg.get("search", {}).get(g["name"], g["name"]))
    for url in ["https://cdn.cloudflare.steamstatic.com/steam/apps/%s/library_hero.jpg" % appid,
                "https://cdn.cloudflare.steamstatic.com/steam/apps/%s/page_bg_generated_v6b.jpg" % appid] if appid else []:
        try:
            data = fetch(url)
            if data and len(data) > 5000:
                path = os.path.join(ART, norm(g["name"]) + ".jpg")
                with open(path, "wb") as f:
                    f.write(data)
                return path
        except Exception:
            pass
    open(miss, "w").close()   # no wide art: the TV uses the cover; asked again in a week
    return None


def played_on_tv():
    try:
        with open(PLAYED, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def mark_played(g):
    d = played_on_tv()
    d[g["id"]] = int(time.time())
    try:
        with open(PLAYED, "w", encoding="utf-8") as f:
            json.dump(d, f)
    except OSError as e:
        log("played: %s" % e)


def steam_last_played():
    """appid -> last time played, from Steam's own record (localconfig.vdf of each Steam account on this PC)."""
    out = {}
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam")
        steam = winreg.QueryValueEx(k, "SteamPath")[0]
    except Exception:
        return out
    root = os.path.join(steam, "userdata")
    for user in os.listdir(root) if os.path.isdir(root) else []:
        vdf = os.path.join(root, user, "config", "localconfig.vdf")
        try:
            with open(vdf, encoding="utf-8", errors="replace") as f:
                text = f.read()
        except OSError:
            continue
        for m in re.finditer(r'"(\d+)"\s*\{[^{}]*?"LastPlayed"\s+"(\d+)"', text):
            out[m.group(1)] = max(out.get(m.group(1), 0), int(m.group(2)))
    return out


def windows_last_run():
    """Path -> last run, from Windows' own list of programs and shortcuts opened (UserAssist, names in ROT13)."""
    import codecs
    out = {}
    try:
        import winreg
        base = r"Software\Microsoft\Windows\CurrentVersion\Explorer\UserAssist"
        top = winreg.OpenKey(winreg.HKEY_CURRENT_USER, base)
        for i in range(winreg.QueryInfoKey(top)[0]):
            try:
                k = winreg.OpenKey(top, winreg.EnumKey(top, i) + "\\Count")
            except OSError:
                continue
            for j in range(winreg.QueryInfoKey(k)[1]):
                name, data, _ = winreg.EnumValue(k, j)
                if not isinstance(data, bytes) or len(data) < 68:
                    continue
                ft = int.from_bytes(data[60:68], "little")
                if ft:
                    path = os.path.normcase(codecs.decode(name, "rot13"))
                    out[path] = max(out.get(path, 0), int(ft / 10_000_000 - 11644473600))
    except Exception as e:
        log("userassist: %s" % e)
    return out


def last_played(cfg, items):
    """Each game's last play (0 = never seen): opened from the TV, Steam's record, or Windows' record."""
    tv, steam, win = played_on_tv(), steam_last_played(), windows_last_run()
    out = {}
    for g in items:
        t = tv.get(g["id"], 0)
        if g.get("steam"):
            t = max(t, steam.get(g["steam"], 0))
        launch = os.path.normcase(g.get("launch", ""))
        for key in (launch, os.path.normcase(g.get("target", ""))):
            if key:
                t = max(t, win.get(key, 0), max((v for p, v in win.items() if p.endswith(os.path.basename(key)) and len(os.path.basename(key)) > 4), default=0))
        out[g["id"]] = t
    return out


# MARK: - Server

STATE = {"games": [], "at": 0}
LOCK = threading.Lock()


def games(cfg, fresh=False):
    with LOCK:
        if fresh or not STATE["games"] or time.time() - STATE["at"] > 300:
            STATE["games"] = scan(cfg)
            STATE["at"] = time.time()
        return STATE["games"]


def warm_covers(cfg):
    for g in games(cfg):
        make_cover(g, cfg)
        make_art(g, cfg)


def private(addr):
    try:
        ip = ipaddress.ip_address(addr)
        return ip.is_private or ip.is_loopback
    except ValueError:
        return False


LAUNCHERS = ("\\battle.net\\", "\\riot client\\", "\\epic games\\launcher", "\\overwolf\\", "\\vortex",
             "\\steam.exe", "steamwebhelper", "\\ubisoft game launcher\\", "rockstar games launcher", "\\nswtl")
WATCH = {"on": False}


def game_running(cfg):
    import displays
    dirs = game_dirs(cfg)
    for p in displays.process_paths():
        n = os.path.normcase(p)
        if any(n.startswith(d + "\\") for d in dirs) and not any(x in n for x in LAUNCHERS):
            return True
    return False


def restore_screens():
    try:
        import displays
        if displays.restore(SAVED):
            log("screens restored")
    except Exception as e:
        log("restore: %s" % e)


def watch_game(cfg):
    """Puts the screens back once the game has closed (or if it never started)."""
    if WATCH["on"]:
        return
    WATCH["on"] = True
    try:
        start, seen, gone = time.time(), False, 0
        while True:
            time.sleep(10)
            if game_running(cfg):
                seen, gone = True, 0
            elif seen:
                gone += 1
                if gone >= 2:
                    break
            elif time.time() - start > 240:
                break
        restore_screens()
    finally:
        WATCH["on"] = False


RUN = {"state": "idle", "game": "", "error": "", "at": 0}


def check_start(g, cfg):
    """
    After a launch from the TV: the game must start (a process under the game folders) and show a full-screen window.
    If it doesn't, the PC's screens go back and the TV hears it (/api/status says "failed") and returns to its games.
    """
    t0 = time.time()
    start_wait, show_wait = float(cfg.get("start_timeout", 120)), float(cfg.get("fullscreen_timeout", 150))
    seen = None
    while True:
        time.sleep(3)
        if RUN.get("at") != t0 and RUN.get("at", 0) > t0:
            return   # another launch took over
        pids = game_pids(cfg)
        if pids and seen is None:
            seen = time.time()
        if pids:
            try:
                import displays
                if displays.fullscreen_window(pids):
                    RUN.update(state="playing")
                    log("start ok: %s" % g["name"])
                    return watch_until_closed(g, cfg, t0)
            except Exception as e:
                log("fullscreen check: %s" % e)
        if seen is None and time.time() - t0 > start_wait:
            return start_failed(g, "o jogo não abriu")
        if seen is not None and not pids and time.time() - seen > 5:
            return start_failed(g, "o jogo fechou logo depois de abrir")
        if seen is not None and time.time() - seen > show_wait:
            return start_failed(g, "o jogo não ficou em tela cheia")


def watch_until_closed(g, cfg, t0):
    """The game is on: once it closes, the PC's screens go back and /api/status says "closed" (the TV goes Home)."""
    gone = 0
    while True:
        time.sleep(5)
        if RUN.get("at", 0) > t0:
            return   # another game was opened meanwhile
        gone = 0 if game_pids(cfg) else gone + 1
        if gone >= 2:
            break
    log("game closed: %s" % g["name"])
    restore_screens()
    RUN.update(state="closed")


def start_failed(g, why):
    log("start failed: %s (%s)" % (g["name"], why))
    RUN.update(state="failed", error=why)
    restore_screens()


def launch(g, cfg, screen=""):
    RUN.update(state="starting", game=g["name"], error="", at=time.time())
    # The TV says which PC screen is its own (Settings › Tela do computador); else the PC's "tv_monitor".
    cfg = dict(cfg, tv_monitor="" if screen == "none" else screen or cfg.get("tv_monitor", ""))
    if cfg.get("tv_monitor"):
        try:
            import displays
            problem = displays.tv_only(cfg["tv_monitor"], SAVED)
            if problem:
                log("tv_only: " + problem)
            elif not displays.wait_tv_only(cfg["tv_monitor"]):   # the game opens only once the TV is the screen
                log("tv_only: the TV took long to become the only screen")
        except Exception as e:
            log("tv_only: %s" % e)
    before = cfg.get("before_launch")
    if before:
        try:
            os.startfile(before)
            time.sleep(2.5)
        except OSError as e:
            log("before_launch: %s" % e)
    log("launch %s -> %s" % (g["name"], g["launch"]))
    try:
        os.startfile(g["launch"])
    except OSError as e:
        start_failed(g, str(e))
        raise
    if cfg.get("tv_monitor"):
        threading.Thread(target=watch_game, args=(cfg,), daemon=True).start()
    threading.Thread(target=check_start, args=(g, cfg), daemon=True).start()


# MARK: - Controller shortcut: hold View + Menu to close the game and put the screens back

PAD_BUTTONS = {"back": 0x0020, "view": 0x0020, "start": 0x0010, "menu": 0x0010, "lb": 0x0100, "rb": 0x0200,
               "ls": 0x0040, "rs": 0x0080, "a": 0x1000, "b": 0x2000, "x": 0x4000, "y": 0x8000,
               "up": 0x0001, "down": 0x0002, "left": 0x0004, "right": 0x0008}


def game_pids(cfg):
    import displays
    dirs = game_dirs(cfg)
    out = []
    for pid, path in displays.processes():
        n = os.path.normcase(path)
        if any(n.startswith(d + "\\") for d in dirs) and not any(x in n for x in LAUNCHERS):
            out.append(pid)
    return out


def close_game(cfg):
    """Asks the game to close, forces it after a few seconds, then puts the screens back."""
    pids = game_pids(cfg)
    log("pad: closing %d game process(es)" % len(pids))
    hide = 0x08000000
    for pid in pids:
        subprocess.run(["taskkill", "/PID", str(pid)], creationflags=hide, capture_output=True)
    for _ in range(16):
        time.sleep(0.5)
        if not game_pids(cfg):
            break
    for pid in game_pids(cfg):
        subprocess.run(["taskkill", "/F", "/PID", str(pid)], creationflags=hide, capture_output=True)
    time.sleep(1)
    restore_screens()
    if RUN.get("state") in ("starting", "playing"):
        RUN.update(state="closed")


def watch_pad(cfg):
    """Xbox-style controllers (XInput): the combo held for `pad_hold` seconds closes the game. Buzzes to confirm."""
    import ctypes

    class PAD(ctypes.Structure):
        _fields_ = [("buttons", ctypes.c_ushort), ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
                    ("lx", ctypes.c_short), ("ly", ctypes.c_short), ("rx", ctypes.c_short), ("ry", ctypes.c_short)]

    class STATE(ctypes.Structure):
        _fields_ = [("packet", ctypes.c_ulong), ("pad", PAD)]

    class BUZZ(ctypes.Structure):
        _fields_ = [("left", ctypes.c_ushort), ("right", ctypes.c_ushort)]

    xi = None
    for dll in ("xinput1_4", "xinput1_3", "xinput9_1_0"):
        try:
            xi = getattr(ctypes.windll, dll)
            break
        except OSError:
            pass
    if xi is None:
        log("pad: no XInput")
        return
    def mask(names):
        m = 0
        for name in names:
            m |= PAD_BUTTONS.get(str(name).lower(), 0)
        return m

    # [mask, seconds held, what to do]; the most buttons first, so a bigger combo wins over one inside it.
    combos = []
    if cfg.get("pad_close", ["view", "menu"]):
        combos.append([mask(cfg.get("pad_close", ["view", "menu"])), float(cfg.get("pad_hold", 2)), None])
    for sc in cfg.get("pad_shortcuts", []):   # {"buttons": ["lb", "rb", "y"], "hold": 3, "run": "C:\...\x.lnk"}
        if sc.get("run") and mask(sc.get("buttons", [])):
            combos.append([mask(sc["buttons"]), float(sc.get("hold", 3)), sc["run"]])
    combos.sort(key=lambda c: (-bin(c[0]).count("1"), c[2] is not None))   # same buttons: closing the game comes first
    since, fired, st = {}, set(), STATE()
    while True:
        time.sleep(0.15)
        pressed = {}
        for i in range(4):
            if xi.XInputGetState(i, ctypes.byref(st)) != 0:
                continue
            for n, (m, hold, run) in enumerate(combos):
                if st.pad.buttons & m == m:
                    pressed.setdefault(n, i)
                    break
        for n in list(since):
            if n not in pressed:
                since.pop(n, None)
                fired.discard(n)
        for n, who in pressed.items():
            m, hold, run = combos[n]
            since.setdefault(n, time.time())
            if n in fired or time.time() - since[n] < hold:
                continue
            fired.add(n)
            if run is None and not game_pids(cfg) and not os.path.exists(SAVED):
                # No game open: the same buttons may have another job then (turning the controllers off).
                run = next((c[2] for c in combos if c[0] == m and c[2]), None)
                if run is None:
                    continue
            try:
                xi.XInputSetState(who, ctypes.byref(BUZZ(40000, 40000)))
                time.sleep(0.35)
                xi.XInputSetState(who, ctypes.byref(BUZZ(0, 0)))
            except Exception:
                pass
            if run is None:
                threading.Thread(target=close_game, args=(cfg,), daemon=True).start()
            else:
                log("pad: run %s" % run)
                try:
                    os.startfile(run)
                except OSError as e:
                    log("pad run: %s" % e)


def handler(cfg):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, body, kind="application/json"):
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store" if kind.endswith("json") else "max-age=86400")
            self.end_headers()
            self.wfile.write(body)

        def allowed(self, q):
            if not private(self.client_address[0]):
                return False
            token = self.headers.get("X-Token") or (q.get("t") or [""])[0]
            return hmac.compare_digest(token.encode(), cfg["token"].encode())

        def route(self, method):
            u = urllib.parse.urlparse(self.path)
            q = urllib.parse.parse_qs(u.query)
            if u.path == "/api/ping":
                return self.send(200, json.dumps({"ok": True, "name": socket.gethostname()}).encode())
            if u.path == "/api/pair" and private(self.client_address[0]):
                # A TV found this PC on the network and asks to connect: the person allows it on the PC's screen.
                tv = ((q.get("tv") or [""])[0] or "TV")[:40]
                if not ask_pair(tv, self.client_address[0]):
                    return self.send(403, b'{"error":"denied"}')
                body = {"token": cfg["token"], "name": socket.gethostname(), "port": int(cfg["port"])}
                return self.send(200, json.dumps(body).encode())
            if not self.allowed(q):
                return self.send(403, b'{"error":"forbidden"}')
            if u.path == "/api/games" and method == "GET":
                items = games(cfg, fresh="fresh" in q)
                played = last_played(cfg, items)
                items = sorted(items, key=lambda g: (-played.get(g["id"], 0), g["name"].lower()))   # latest played first
                body = [{"id": g["id"], "name": g["name"], "source": g["source"],
                         "cover": bool(make_cover(g, cfg)),   # quick when cached; a new cover is made here
                         "v": int(os.path.getmtime(cover_path(g))) if os.path.exists(cover_path(g)) else 0,
                         "played": played.get(g["id"], 0)}
                        for g in items]
                return self.send(200, json.dumps(body, ensure_ascii=False).encode("utf-8"))
            gid = (q.get("id") or [""])[0]
            g = next((x for x in games(cfg) if x["id"] == gid), None)
            if u.path == "/api/cover" and method == "GET":
                p = make_cover(g, cfg) if g else None
                if not p:
                    return self.send(404, b'{"error":"no cover"}')
                with open(p, "rb") as f:
                    return self.send(200, f.read(), "image/jpeg")
            if u.path == "/api/screens" and method == "GET":
                import displays
                seen, out = set(), []
                for d in displays.devices():
                    key = displays.hw(d["monitor"])
                    if d["attached"] and displays.physical(d) and key not in seen:
                        seen.add(key)
                        out.append({"id": key, "name": displays.friendly(d)})
                return self.send(200, json.dumps(out, ensure_ascii=False).encode("utf-8"))
            if u.path == "/api/status" and method == "GET":
                return self.send(200, json.dumps({k: RUN[k] for k in ("state", "game", "error")}, ensure_ascii=False).encode("utf-8"))
            if u.path == "/api/art" and method == "GET":
                p = make_art(g, cfg) if g else None
                if not p:
                    return self.send(404, b'{"error":"no art"}')
                with open(p, "rb") as f:
                    return self.send(200, f.read(), "image/jpeg")
            if u.path == "/api/restore" and method == "POST":
                restore_screens()
                return self.send(200, b'{"ok":true}')
            if u.path == "/api/launch" and method == "POST":
                if not g:
                    return self.send(404, b'{"error":"no such game"}')
                # Answered at once: switching the screens can take ~15 s and the TV must switch its input meanwhile;
                # a game that fails to open shows up in /api/status, and the TV comes back from the input.
                RUN.update(state="starting", game=g["name"], error="", at=time.time())
                screen = (q.get("screen") or [""])[0]

                def go():
                    try:
                        launch(g, cfg, screen)
                        mark_played(g)
                    except Exception as e:
                        log("launch: %s" % e)

                threading.Thread(target=go, daemon=True).start()
                return self.send(200, json.dumps({"ok": True, "name": g["name"]}, ensure_ascii=False).encode("utf-8"))
            return self.send(404, b'{"error":"not found"}')

        def do_GET(self):
            self.route("GET")

        def do_POST(self):
            self.route("POST")
    return H


PAIR_LOCK = threading.Lock()


def ask_pair(tv, ip):
    """Windows dialog on top of everything: Yes gives that TV the access code. One question at a time."""
    if not PAIR_LOCK.acquire(blocking=False):
        return False
    try:
        import ctypes
        text = ("A TV \"%s\" (%s) quer mostrar os jogos deste computador e abri-los pelo controle.\n\n"
                "Permitir?" % (tv, ip))
        # MB_YESNO | MB_ICONQUESTION | MB_TOPMOST | MB_SETFOREGROUND
        return ctypes.windll.user32.MessageBoxW(None, text, "Jogos do PC na TV", 0x4 | 0x20 | 0x40000 | 0x10000) == 6
    except Exception as e:
        log("pair: %s" % e)
        return False
    finally:
        PAIR_LOCK.release()


def answer_discovery(cfg):
    """The TV looks for this PC with a broadcast ("CYT-GAMES?") on UDP; the answer says where the agent listens."""
    port = int(cfg.get("discovery_port", 48501))
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("0.0.0.0", port))
    except OSError as e:
        log("discovery off: %s" % e)
        return
    reply = json.dumps({"name": socket.gethostname(), "port": int(cfg["port"])}).encode()
    while True:
        try:
            data, addr = s.recvfrom(256)
            if data.strip() == b"CYT-GAMES?" and private(addr[0]):
                s.sendto(reply, addr)
        except OSError:
            pass


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.0.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    cfg = load_config()
    if "--list" in sys.argv:
        for g in scan(cfg):
            print("%-5s %-34s %-9s %s" % (g["id"], g["name"], g["source"], g["launch"]))
        return
    if "--restore" in sys.argv:
        restore_screens()
        return
    if "--displays" in sys.argv:
        import displays
        for d in displays.devices():
            print(d)
        return
    if "--tv-config" in sys.argv:
        print(json.dumps({"games_pc": {"host": "%s:%d" % (local_ip(), cfg["port"]), "token": cfg["token"], "input": "PC"}}))
        return
    threading.Thread(target=warm_covers, args=(cfg,), daemon=True).start()
    threading.Thread(target=answer_discovery, args=(cfg,), daemon=True).start()
    if cfg.get("pad_close", ["view", "menu"]) or cfg.get("pad_shortcuts"):
        threading.Thread(target=watch_pad, args=(cfg,), daemon=True).start()
    if os.path.exists(SAVED) and not game_running(cfg):   # the PC was turned off mid-game: screens back first
        restore_screens()
    elif os.path.exists(SAVED):
        threading.Thread(target=watch_game, args=(cfg,), daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", int(cfg["port"])), handler(cfg))
    log("listening on %s:%d" % (local_ip(), cfg["port"]))
    srv.serve_forever()


if __name__ == "__main__":
    main()
