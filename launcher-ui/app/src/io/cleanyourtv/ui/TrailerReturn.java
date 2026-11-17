package io.cleanyourtv.ui;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A trailer opened from Home comes back Home when it ends. SmartTube (or YouTube) only pauses at the end and tells no
 * one, and the remote's accessibility helper may be off, so: the trailer's length comes from YouTube, and once that
 * time has passed, the TV going quiet (no sound for two checks in a row) means it ended. Sound still playing (you
 * paused and went on, or moved to another video) waits; Home shown again by you, or a new trailer, calls it off.
 */
final class TrailerReturn {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int armed;   // the trailer being watched (0 = none); only touched on the main thread

    private TrailerReturn() {}

    static void arm(Context c, String videoId) {
        final Context app = c.getApplicationContext();
        final int id = ++armed;
        final long startedAt = SystemClock.uptimeMillis();
        new Thread(() -> {
            int seconds = length(videoId);
            // Unknown length: start listening for the quiet after 40 s (no trailer is shorter).
            long firstCheck = startedAt + (seconds > 0 ? seconds * 1000L + 2000L : 40_000L);
            MAIN.postAtTime(() -> check(app, id, 0, startedAt), Math.max(firstCheck, SystemClock.uptimeMillis() + 1000));
        }).start();
    }

    /** Home is showing again (you came back yourself): nothing to bring back. */
    static void cancel() {
        armed++;
    }

    private static void check(Context app, int id, int quiet, long startedAt) {
        if (id != armed) return;
        if (SystemClock.uptimeMillis() - startedAt > 45 * 60_000L) {   // long gone into something else: give up
            armed++;
            return;
        }
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        boolean playing = audio != null && audio.isMusicActive();
        int q = playing ? 0 : quiet + 1;
        if (q >= 2) {
            armed++;
            try {
                app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(app.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("trailer home: " + e.getMessage());
            }
            return;
        }
        MAIN.postDelayed(() -> check(app, id, q, startedAt), 3000);
    }

    /** The video's length in seconds from YouTube's own player data, or 0 when it can't be read. */
    private static int length(String videoId) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            String body = "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20241101.00.00\"}},\"videoId\":\""
                    + videoId.replaceAll("[^A-Za-z0-9_-]", "") + "\"}";
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in = c.getInputStream()) {
                JSONObject o = new JSONObject(new String(Tmdb.readBytes(in), StandardCharsets.UTF_8));
                return o.optJSONObject("videoDetails") == null ? 0 : o.getJSONObject("videoDetails").optInt("lengthSeconds");
            }
        } catch (Exception e) {
            AppLog.w("trailer length: " + e.getMessage());
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
