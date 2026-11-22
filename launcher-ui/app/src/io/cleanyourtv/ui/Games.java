package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The PC's games on the TV. A small program on the computer (pc-games/games_agent.py) lists the games installed there
 * with their covers; OK on one asks the PC to open it and switches the TV to the PC's HDMI input. Set up by config.json
 * ("games_pc": host, token and the input's name), on the home network only.
 */
final class Games {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Games() {}

    static boolean configured() {
        return !Prefs.gamesHost().isEmpty() && !Prefs.gamesToken().isEmpty();
    }

    private static String base() {
        return "http://" + Prefs.gamesHost();
    }

    private static String token() {
        try {
            return URLEncoder.encode(Prefs.gamesToken(), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** The grid of games; each poster opens its game. */
    static void open(Activity a) {
        Toast.makeText(a, Ui.tr("Buscando os jogos do PC…", "Getting the PC's games…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<JSONObject> list = objects(new JSONArray(request("GET", "/api/games?t=" + token())));
                List<Tmdb.Title> titles = new ArrayList<>();
                Map<Integer, String> ids = new HashMap<>();
                for (int i = 0; i < list.size(); i++) {
                    JSONObject g = list.get(i);
                    String cover = g.optBoolean("cover") ? base() + "/api/cover?id=" + g.getString("id") + "&v=" + g.optLong("v") + "&t=" + token() : "";
                    titles.add(new Tmdb.Title("game", i, g.getString("name"), cover, "", g.optString("source"), 0));
                    ids.put(i, g.getString("id"));
                }
                MAIN.post(() -> {
                    if (a.isFinishing()) return;
                    GridActivity.show(a, Ui.tr("Jogos do PC", "PC games"), titles, t -> launch(a, ids.get(t.id), t.name));
                });
            } catch (Exception e) {
                AppLog.w("games: " + e.getMessage());
                MAIN.post(() -> Toast.makeText(a, explain(e), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * Settings › Jogos do PC: finds the computer running pc-games on the network (a UDP broadcast), then asks it to
     * connect; the person allows it on the PC's screen and the TV keeps the address and the access code.
     */
    static void pair(Activity a, Runnable done) {
        Toast.makeText(a, Ui.tr("Procurando o computador na rede…", "Looking for the computer on the network…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String host = null, name = "";
            try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(1200);
                byte[] ask = "CYT-GAMES?".getBytes("UTF-8");
                for (int round = 0; round < 3 && host == null; round++) {
                    s.send(new java.net.DatagramPacket(ask, ask.length, java.net.InetAddress.getByName("255.255.255.255"), 48501));
                    try {
                        byte[] buf = new byte[512];
                        java.net.DatagramPacket in = new java.net.DatagramPacket(buf, buf.length);
                        s.receive(in);
                        JSONObject o = new JSONObject(new String(buf, 0, in.getLength(), "UTF-8"));
                        host = in.getAddress().getHostAddress() + ":" + o.optInt("port", 48500);
                        name = o.optString("name");
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                AppLog.w("games find: " + e.getMessage());
            }
            if (host == null) {
                MAIN.post(() -> Toast.makeText(a, Ui.tr("Não achei o computador. Abra o Jogos do PC nele (pasta pc-games) e use a mesma rede.",
                        "Couldn't find the computer. Start PC Games on it (pc-games folder) and use the same network."), Toast.LENGTH_LONG).show());
                return;
            }
            String pc = name.isEmpty() ? host : name;
            MAIN.post(() -> Toast.makeText(a, Ui.tr("Achei " + pc + ". Clique em Sim na tela do computador.",
                    "Found " + pc + ". Click Yes on the computer's screen."), Toast.LENGTH_LONG).show());
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + "/api/pair?tv="
                        + URLEncoder.encode(android.os.Build.MODEL, "UTF-8")).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(90_000);   // the person walks to the computer and clicks Yes
                int code = c.getResponseCode();
                if (code == 403) throw new Problem(Ui.tr("O computador recusou a conexão. Tente de novo e clique em Sim na tela dele.",
                        "The computer refused. Try again and click Yes on its screen."));
                if (code != 200) throw new Problem(Ui.tr("O Jogos do PC deu erro (" + code + "). No computador, feche e abra o Jogos do PC e tente de novo.",
                        "PC Games failed (" + code + "). On the computer, close and reopen PC Games, then try again."));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                try (InputStream in = c.getInputStream()) {
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                c.disconnect();
                JSONObject o = new JSONObject(out.toString("UTF-8"));
                String token = o.getString("token");
                String h = host;
                MAIN.post(() -> {
                    Prefs.setGames(h, token, Prefs.gamesInput());
                    Prefs.setGamesName(pc);
                    HomeData.get(a).settingsChanged();
                    Toast.makeText(a, Ui.tr("Conectado a " + pc + ". Os jogos aparecem na aba Jogos e no dock.",
                            "Connected to " + pc + ". The games show in the Games tab and the dock."), Toast.LENGTH_LONG).show();
                    MAIN.postDelayed(() -> askOverlay(a), 3500);
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                AppLog.w("games pair: " + e.getMessage());
                String why = e instanceof Problem ? e.getMessage() : e instanceof java.net.SocketTimeoutException
                        ? Ui.tr("Ninguém clicou em Sim no computador a tempo. Tente de novo.", "Nobody clicked Yes on the computer in time. Try again.")
                        : Ui.tr("O computador parou de responder. Confira se o Jogos do PC está aberto nele e tente de novo.",
                                "The computer stopped answering. Check that PC Games is running on it and try again.");
                MAIN.post(() -> Toast.makeText(a, why, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * When the game closes, the TV comes back Home from the PC's input: Android lets the app do that only with
     * "Display over other apps". Asked once here, on the TV itself (no computer needed).
     */
    static void askOverlay(Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(a)) return;
        Toast.makeText(a, Ui.tr("Para a TV voltar sozinha à Início quando o jogo fechar, permita \"Sobrepor a outros apps\" para o Clean Your TV Master.",
                "So the TV comes back Home when the game closes, allow \"Display over other apps\" for Clean Your TV Master."), Toast.LENGTH_LONG).show();
        try {
            a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + a.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {   // some TVs hide that screen: Settings › Apps › Special app access
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + a.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** The PC's screens, [model id, name], for Settings › Tela do computador (null when the PC doesn't answer). */
    static List<String[]> screens() {
        try {
            JSONArray a = new JSONArray(request("GET", "/api/screens?t=" + token()));
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(new String[] {a.getJSONObject(i).getString("id"), a.getJSONObject(i).getString("name")});
            return out;
        } catch (Exception e) {
            AppLog.w("games screens: " + e.getMessage());
            return null;
        }
    }

    /** Forgets the computer: the Games tab and the dock go away. */
    static void forget() {
        Prefs.setGames("", "", Prefs.gamesInput());
        Prefs.setGamesName("");
    }

    /** One game for the dock on the home screen: its cover is already loaded (or null: the name is shown). */
    static final class Game {
        final String id, name, art;
        final android.graphics.Bitmap cover;
        String source = "";   // Steam, Epic, Riot, PC
        long played;          // last played, in seconds (0 = never seen)
        double hours;         // hours played (Steam's count, or the PC's own for games opened from the TV)

        Game(String id, String name, android.graphics.Bitmap cover, String art) {
            this.id = id;
            this.name = name;
            this.cover = cover;
            this.art = art;
        }
    }

    /**
     * The PC sends the latest played first; once you moved games on the dock, your order wins. Games the PC lists that
     * your order doesn't know yet (new, or just played for the first time) go in front.
     */
    static void sortByOrder(List<JSONObject> list) {
        String saved = Prefs.gamesOrder();
        if (saved.isEmpty()) return;
        List<String> order = java.util.Arrays.asList(saved.split(","));
        java.util.Collections.sort(list, (a, b) -> Integer.compare(order.indexOf(a.optString("id")), order.indexOf(b.optString("id"))));
    }

    static void saveOrder(List<Game> games) {
        StringBuilder b = new StringBuilder();
        for (Game g : games) b.append(b.length() == 0 ? "" : ",").append(g.id);
        Prefs.setGamesOrder(b.toString());
    }

    private static List<JSONObject> objects(JSONArray a) throws org.json.JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        sortByOrder(out);
        return out;
    }

    /** The PC's games with their covers, for the dock under Inputs and Shortcuts; null when the PC doesn't answer. */
    static void list(java.util.function.Consumer<List<Game>> done) {
        new Thread(() -> {
            List<Game> out = null;
            try {
                List<JSONObject> list = objects(new JSONArray(request("GET", "/api/games?t=" + token())));
                out = new ArrayList<>();
                for (JSONObject g : list) {
                    android.graphics.Bitmap cover = g.optBoolean("cover")
                            ? Images.fetch(base() + "/api/cover?id=" + g.getString("id") + "&v=" + g.optLong("v") + "&t=" + token()) : null;
                    Game one = new Game(g.getString("id"), g.getString("name"), cover,
                            base() + "/api/art?id=" + URLEncoder.encode(g.getString("id"), "UTF-8") + "&t=" + token());
                    one.source = g.optString("source");
                    one.played = g.optLong("played");
                    one.hours = g.optDouble("hours", 0);
                    out.add(one);
                }
            } catch (Exception e) {
                AppLog.w("games dock: " + e.getMessage());
            }
            List<Game> result = out;
            MAIN.post(() -> done.accept(result));
        }).start();
    }

    /** The game's trailer, which the PC looks up on YouTube (Portuguese first), in SmartTube or YouTube. */
    static void trailer(Activity a, String id) {
        new Thread(() -> {
            String key = null;
            try {
                key = new JSONObject(request("GET", "/api/trailer?id=" + URLEncoder.encode(id, "UTF-8") + "&t=" + token())).optString("key", null);
            } catch (Exception e) {
                AppLog.w("games trailer: " + e.getMessage());
            }
            String k = key == null || key.isEmpty() || "null".equals(key) ? null : key;
            MAIN.post(() -> DeepLinks.playYouTube(a, k));
        }).start();
    }

    /** "Jogado hoje · 12 h jogadas · Steam": when, how long and where, for the line under the game's name. */
    static String about(Game g) {
        StringBuilder b = new StringBuilder();
        if (g.played > 0) {
            java.util.Calendar now = java.util.Calendar.getInstance(), then = java.util.Calendar.getInstance();
            then.setTimeInMillis(g.played * 1000);
            int gap = (int) Math.round((startOfDay(now) - startOfDay(then)) / 86400000.0);
            b.append(gap <= 0 ? Ui.tr("Jogado hoje", "Played today") : gap == 1 ? Ui.tr("Jogado ontem", "Played yesterday")
                    : gap < 60 ? Ui.tr("Jogado há " + gap + " dias", "Played " + gap + " days ago")
                    : Ui.tr("Jogado há " + gap / 30 + " meses", "Played " + gap / 30 + " months ago"));
        } else {
            b.append(Ui.tr("Ainda não jogado", "Not played yet"));
        }
        if (g.hours >= 0.1) {
            String h = g.hours < 10 ? String.format(java.util.Locale.ROOT, "%.1f", g.hours).replace('.', Ui.en ? '.' : ',')
                    : String.valueOf(Math.round(g.hours));
            b.append(" · ").append(h).append(Ui.tr(" h jogadas", " h played"));
        }
        if (g.source != null && !g.source.isEmpty() && !"PC".equals(g.source)) b.append(" · ").append(g.source);
        return b.toString();
    }

    private static long startOfDay(java.util.Calendar c) {
        java.util.Calendar d = (java.util.Calendar) c.clone();
        d.set(java.util.Calendar.HOUR_OF_DAY, 0);
        d.set(java.util.Calendar.MINUTE, 0);
        d.set(java.util.Calendar.SECOND, 0);
        d.set(java.util.Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** Asks the PC to open the game, then shows the PC's HDMI input. */
    static void launch(Activity a, String id, String name) {
        if (id == null) return;
        Toast.makeText(a, Ui.tr("Abrindo " + name + " no PC…", "Opening " + name + " on the PC…"), Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                request("POST", "/api/launch?id=" + URLEncoder.encode(id, "UTF-8") + "&t=" + token()
                        + "&screen=" + URLEncoder.encode(Prefs.gamesScreen(), "UTF-8"));
                MAIN.post(() -> showPc(a));
                watchStart(a);
            } catch (Exception e) {
                AppLog.w("games launch: " + e.getMessage());
                MAIN.post(() -> Toast.makeText(a, Ui.tr("Não deu para abrir " + name + ". ", "Couldn't open " + name + ". ") + explain(e),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * While the PC opens the game: if it reports that the game didn't open (or didn't go full screen), the PC has put
     * its screens back; the TV leaves the HDMI input and comes back to the games, saying why.
     */
    private static void watchStart(Activity a) {
        long start = System.currentTimeMillis(), until = start + 12 * 3600_000L;   // a long evening of play at most
        boolean playing = false;
        int failures = 0;   // the PC asleep or off: stop asking after ~5 minutes of silence
        while (System.currentTimeMillis() < until && failures < 100) {
            try {
                Thread.sleep(playing ? 2500 : 3000);   // a closed game brings the TV Home within seconds
                JSONObject o = new JSONObject(request("GET", "/api/status?t=" + token()));
                failures = 0;
                String state = o.optString("state");
                if ("idle".equals(state)) return;
                if ("playing".equals(state)) {
                    playing = true;
                    continue;
                }
                if ("closed".equals(state)) {   // the game was closed on the PC: its screens are back, the TV goes Home
                    MAIN.post(() -> goHome(a));
                    return;
                }
                if (!playing && System.currentTimeMillis() - start > 6 * 60_000L) return;
                if ("failed".equals(state)) {
                    String why = o.optString("error");
                    MAIN.post(() -> {
                        Intent home = new Intent(a, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        try {
                            a.startActivity(home);
                        } catch (RuntimeException e) {
                            AppLog.w("games back: " + e.getMessage());
                        }
                        Toast.makeText(a, Ui.tr("Não deu para jogar: " + why + ". As telas do PC voltaram ao normal.",
                                "Couldn't play: " + why + ". The PC's screens are back."), Toast.LENGTH_LONG).show();
                        MAIN.postDelayed(() -> open(a), 800);
                    });
                    return;
                }
            } catch (Exception e) {
                failures++;
                if (failures == 1) AppLog.w("games status: " + e.getMessage());
            }
        }
    }

    private static void goHome(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(a.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("games home: " + e.getMessage());
        }
    }

    /** The HDMI input named like the PC's ("PC" by default), as the TV shows it under Inputs. */
    private static void showPc(Activity a) {
        String want = Prefs.gamesInput();
        if (want.isEmpty()) return;   // no input to switch to: the PC's screen shows on this TV by itself
        for (HomeApps.Item it : HomeApps.allInputs(a)) {
            if (it.inputId == null || !it.label.equalsIgnoreCase(want)) continue;
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            RemoteKeys.rememberInput(a, it.inputId);
            try {
                a.startActivity(i);
            } catch (RuntimeException e) {
                AppLog.w("games input: " + e.getMessage());
            }
            return;
        }
        Toast.makeText(a, Ui.tr("Jogo aberto no PC. Não achei a entrada \"" + want + "\": troque o HDMI no controle.",
                "Game opened on the PC. No input named \"" + want + "\": switch HDMI with the remote."), Toast.LENGTH_LONG).show();
    }

    /** An error already worded for the person: what went wrong and what to do about it. */
    static final class Problem extends IOException {
        Problem(String message) {
            super(message);
        }
    }

    /** The PC didn't take the connection at the saved address (off, asleep, agent closed, or a new address). */
    private static final class Unreachable extends IOException {
        Unreachable(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** The person-facing words for any failure talking to the PC. */
    static String explain(Exception e) {
        if (e instanceof Problem) return e.getMessage();
        return Ui.tr("Algo deu errado com o Jogos do PC. No computador, feche e abra o Jogos do PC e tente de novo.",
                "Something went wrong with PC Games. On the computer, close and reopen PC Games, then try again.");
    }

    /**
     * Asks the PC. Routers hand the PC a new address now and then: when the saved one doesn't answer, the TV looks
     * for the PC on the network again and, if the same PC (same access code) answers there, keeps the new address.
     */
    private static String request(String method, String path) throws IOException {
        try {
            return send(Prefs.gamesHost(), method, path);
        } catch (Unreachable e) {
            String old = Prefs.gamesHost(), found = find();
            if (found != null && !found.equals(old)) {
                try {
                    String body = send(found, method, path);
                    Prefs.setGames(found, Prefs.gamesToken(), Prefs.gamesInput());
                    AppLog.w("games: the PC moved to a new address");
                    return body;
                } catch (Unreachable ignored) {
                    // fall through: the PC is not reachable at all
                }
            }
            AppLog.w("games: " + e.getMessage());
            throw new Problem(Ui.tr("O PC não respondeu. Confira se ele está ligado (sem estar em espera), na mesma rede da TV e com o Jogos do PC aberto.",
                    "The PC didn't answer. Check that it's on (not asleep), on the TV's network, with PC Games running."));
        }
    }

    /** The PC running pc-games on this network, "address:port", from a UDP broadcast; null when none answers. */
    private static String find() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(1200);
            byte[] ask = "CYT-GAMES?".getBytes("UTF-8");
            for (int round = 0; round < 3; round++) {
                s.send(new java.net.DatagramPacket(ask, ask.length, java.net.InetAddress.getByName("255.255.255.255"), 48501));
                try {
                    byte[] buf = new byte[512];
                    java.net.DatagramPacket in = new java.net.DatagramPacket(buf, buf.length);
                    s.receive(in);
                    JSONObject o = new JSONObject(new String(buf, 0, in.getLength(), "UTF-8"));
                    return in.getAddress().getHostAddress() + ":" + o.optInt("port", 48500);
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            AppLog.w("games find: " + e.getMessage());
        }
        return null;
    }

    private static String send(String host, String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(4000);
        c.setReadTimeout(method.equals("POST") ? 15000 : 30000);
        if (method.equals("POST")) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
        }
        int code;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            try {
                c.connect();
            } catch (IOException e) {
                throw new Unreachable(e);
            }
            code = c.getResponseCode();
            try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
                byte[] buf = new byte[8192];
                int n;
                while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (java.net.SocketTimeoutException e) {
            AppLog.w("games: " + e.getMessage());
            throw new Problem(Ui.tr("O PC demorou demais para responder. Espere alguns segundos e tente de novo.",
                    "The PC took too long to answer. Wait a few seconds and try again."));
        } finally {
            c.disconnect();
        }
        String body = out.toString("UTF-8");
        if (code == 403) throw new Problem(Ui.tr("Esta TV perdeu a permissão no PC. Vá em Configurações › Jogos do PC e conecte de novo.",
                "This TV is no longer allowed on the PC. Go to Settings › PC games and connect again."));
        if (code == 404 && path.startsWith("/api/launch")) throw new Problem(Ui.tr("O PC não achou esse jogo. Abra a lista de jogos de novo para atualizar.",
                "The PC can't find that game. Open the games list again to refresh it."));
        if (code >= 400) {
            AppLog.w("games: HTTP " + code + " " + path.replaceAll("t=[^&]*", "t=…"));
            throw new Problem(Ui.tr("O Jogos do PC deu erro (" + code + "). No computador, feche e abra o Jogos do PC e tente de novo.",
                    "PC Games failed (" + code + "). On the computer, close and reopen PC Games, then try again."));
        }
        return body;
    }

    static boolean isGame(Context c, Tmdb.Title t) {
        return t != null && "game".equals(t.kind);
    }
}
