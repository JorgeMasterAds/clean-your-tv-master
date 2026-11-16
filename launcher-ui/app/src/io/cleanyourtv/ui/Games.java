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
                JSONArray list = new JSONArray(request("GET", "/api/games?t=" + token()));
                List<Tmdb.Title> titles = new ArrayList<>();
                Map<Integer, String> ids = new HashMap<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject g = list.getJSONObject(i);
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

    /** One game for the dock on the home screen: its cover is already loaded (or null: the name is shown). */
    static final class Game {
        final String id, name;
        final android.graphics.Bitmap cover;

        Game(String id, String name, android.graphics.Bitmap cover) {
            this.id = id;
            this.name = name;
            this.cover = cover;
        }
    }

    /** The PC's games with their covers, for the dock under Inputs and Shortcuts; null when the PC doesn't answer. */
    static void list(java.util.function.Consumer<List<Game>> done) {
        new Thread(() -> {
            List<Game> out = null;
            try {
                JSONArray list = new JSONArray(request("GET", "/api/games?t=" + token()));
                out = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject g = list.getJSONObject(i);
                    android.graphics.Bitmap cover = g.optBoolean("cover")
                            ? Images.fetch(base() + "/api/cover?id=" + g.getString("id") + "&v=" + g.optLong("v") + "&t=" + token()) : null;
                    out.add(new Game(g.getString("id"), g.getString("name"), cover));
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
                request("POST", "/api/launch?id=" + URLEncoder.encode(id, "UTF-8") + "&t=" + token());
                MAIN.post(() -> showPc(a));
            } catch (Exception e) {
                AppLog.w("games launch: " + e.getMessage());
                MAIN.post(() -> Toast.makeText(a, Ui.tr("O PC não abriu o jogo: ", "The PC didn't open the game: ") + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** The HDMI input named like the PC's ("PC" by default), as the TV shows it under Inputs. */
    private static void showPc(Activity a) {
        String want = Prefs.gamesInput();
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
