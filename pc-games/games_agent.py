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

DEFAULTS = {
    "port": 48500,
    "token": "",
    "game_dirs": [],             # added to every <drive>:\Games folder found
    "exclude": ["vortex", "uninstall", "desinstalar", "overwolf", "optiscaler", "setup", "redist", "dlss",
                "riot client", "rockstar games launcher"],
    "before_launch": "",         # optional: a shortcut or program run first (e.g. switch the PC's output to the TV)
    "covers": {},                # "Game name": "https://... or C:\\path\\cover.jpg"
    "search": {},                # "Game name": "name to look its cover up by" (e.g. the English title)
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

    def add(g, key):
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
        add({"name": pretty(l["name"]), "source": source, "launch": l["file"]}, os.path.normcase(target) + "|" + args.lower())
    for g in epic_games() + steam_games() + battlenet():
        add(g, g["launch"].lower())
    for i, g in enumerate(sorted(games, key=lambda g: g["name"].lower())):
        g["id"] = "g%02d" % i
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


def private(addr):
    try:
        ip = ipaddress.ip_address(addr)
        return ip.is_private or ip.is_loopback
    except ValueError:
        return False


def launch(g, cfg):
    before = cfg.get("before_launch")
    if before:
        try:
            os.startfile(before)
            time.sleep(2.5)
        except OSError as e:
            log("before_launch: %s" % e)
    log("launch %s -> %s" % (g["name"], g["launch"]))
    os.startfile(g["launch"])


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
            if not self.allowed(q):
                return self.send(403, b'{"error":"forbidden"}')
            if u.path == "/api/games" and method == "GET":
                body = [{"id": g["id"], "name": g["name"], "source": g["source"],
                         "cover": os.path.exists(cover_path(g))} for g in games(cfg, fresh="fresh" in q)]
                return self.send(200, json.dumps(body, ensure_ascii=False).encode("utf-8"))
            gid = (q.get("id") or [""])[0]
            g = next((x for x in games(cfg) if x["id"] == gid), None)
            if u.path == "/api/cover" and method == "GET":
                p = make_cover(g, cfg) if g else None
                if not p:
                    return self.send(404, b'{"error":"no cover"}')
                with open(p, "rb") as f:
                    return self.send(200, f.read(), "image/jpeg")
            if u.path == "/api/launch" and method == "POST":
                if not g:
                    return self.send(404, b'{"error":"no such game"}')
                try:
                    launch(g, cfg)
                except OSError as e:
                    return self.send(500, json.dumps({"error": str(e)}).encode())
                return self.send(200, json.dumps({"ok": True, "name": g["name"]}, ensure_ascii=False).encode("utf-8"))
            return self.send(404, b'{"error":"not found"}')

        def do_GET(self):
            self.route("GET")

        def do_POST(self):
            self.route("POST")
    return H


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
    if "--tv-config" in sys.argv:
        print(json.dumps({"games_pc": {"host": "%s:%d" % (local_ip(), cfg["port"]), "token": cfg["token"], "input": "PC"}}))
        return
    threading.Thread(target=warm_covers, args=(cfg,), daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", int(cfg["port"])), handler(cfg))
    log("listening on %s:%d" % (local_ip(), cfg["port"]))
    srv.serve_forever()


if __name__ == "__main__":
    main()
