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
                MAIN.post(() -> Toast.makeText(a, Ui.tr("Não achei o PC. Ele está ligado, na mesma rede e com o Jogos do PC aberto?",
                        "Couldn't reach the PC. Is it on, on the same network, with PC Games running?"), Toast.LENGTH_LONG).show());
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
                if (code != 200) throw new IOException(code == 403 ? Ui.tr("o computador não permitiu", "the computer said no") : "HTTP " + code);
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
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                AppLog.w("games pair: " + e.getMessage());
                MAIN.post(() -> Toast.makeText(a, Ui.tr("Não conectou: ", "Didn't connect: ") + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
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
                    out.add(new Game(g.getString("id"), g.getString("name"), cover,
                            base() + "/api/art?id=" + URLEncoder.encode(g.getString("id"), "UTF-8") + "&t=" + token()));
                }
            } catch (Exception e) {
                AppLog.w("games dock: " + e.getMessage());
            }
            List<Game> result = out;
            MAIN.post(() -> done.accept(result));
        }).start();
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
                MAIN.post(() -> Toast.makeText(a, Ui.tr("O PC não abriu o jogo: ", "The PC didn't open the game: ") + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * While the PC opens the game: if it reports that the game didn't open (or didn't go full screen), the PC has put
     * its screens back; the TV leaves the HDMI input and comes back to the games, saying why.
     */
    private static void watchStart(Activity a) {
        long until = System.currentTimeMillis() + 6 * 60_000L;
        while (System.currentTimeMillis() < until) {
            try {
                Thread.sleep(3000);
                JSONObject o = new JSONObject(request("GET", "/api/status?t=" + token()));
                String state = o.optString("state");
                if ("playing".equals(state) || "idle".equals(state)) return;
                if ("failed".equals(state)) {
                    String why = o.optString("error");
                    MAIN.post(() -> {
                        Intent home = new Intent(a, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        try {
                            a.startActivity(home);
                        } catch (RuntimeException e) {
                            AppLog.w("games back: " + e.getMessage());
                        }
                        Toast.makeText(a, Ui.tr("Não deu para jogar: " + why + ". As telas do PC voltaram.",
                                "Couldn't play: " + why + ". The PC's screens are back."), Toast.LENGTH_LONG).show();
                        MAIN.postDelayed(() -> open(a), 800);
                    });
                    return;
                }
            } catch (Exception e) {
                AppLog.w("games status: " + e.getMessage());
            }
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

    private static String request(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(4000);
        c.setReadTimeout(method.equals("POST") ? 15000 : 30000);
        if (method.equals("POST")) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
        c.disconnect();
        String body = out.toString("UTF-8");
        if (code >= 400) throw new IOException(code == 403 ? Ui.tr("código de acesso errado", "wrong access code") : "HTTP " + code);
        return body;
    }

    static boolean isGame(Context c, Tmdb.Title t) {
        return t != null && "game".equals(t.kind);
    }
}
