package io.cleanyourtv.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "Watch on X" straight to the title, not just the app's home. TMDB doesn't know the services' own ids, Wikidata
 * does: its item for the TMDB id carries the Netflix, Disney+, Apple TV, Max and Prime Video ids, which make each
 * app's links. Answers are kept for the session; no link means the app opens on its home as before.
 */
final class DeepLinks {
    private DeepLinks() {}

    private static final String UA = "CleanYourTV-UI/1.0 (https://github.com/matheus-caldeira/cleanyourtv-ui)";
    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();

    /** App package → link to the title in it ("tv" or "movie", TMDB id); empty when Wikidata has none. */
    static Map<String, String> find(Context c, String kind, int id, String name) {
        String key = kind + ":" + id;
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) return CACHE.get(key);
        }
        android.content.SharedPreferences sp = c.getSharedPreferences("deeplinks", Context.MODE_PRIVATE);
        String kept = sp.getString(key, null);
        if (kept != null) {   // "time|json": a month, then asked again (Wikidata gains ids over time)
            int bar = kept.indexOf('|');
            try {
                if (System.currentTimeMillis() - Long.parseLong(kept.substring(0, bar)) < 30L * 24 * 3600_000) {
                    Map<String, String> out = new HashMap<>();
                    JSONObject o = new JSONObject(kept.substring(bar + 1));
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String app = it.next();
                        out.put(app, o.getString(app));
                    }
                    synchronized (CACHE) { CACHE.put(key, out); }
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, String> out = new HashMap<>();
        try {
            String prop = kind.equals("movie") ? "P4947" : "P4983";
            JSONArray hits = new JSONObject(get("https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srsearch=haswbstatement:"
                    + prop + "=" + id)).getJSONObject("query").getJSONArray("search");
            if (hits.length() > 0) {
                String q = hits.getJSONObject(0).getString("title");
                JSONObject claims = new JSONObject(get("https://www.wikidata.org/wiki/Special:EntityData/" + q + ".json"))
                        .getJSONObject("entities").getJSONObject(q).getJSONObject("claims");
                String slug = slug(name);
                boolean movie = kind.equals("movie");
                String v;
                if ((v = value(claims, "P1874")) != null) out.put("com.netflix.ninja", "https://www.netflix.com/title/" + v);
                if ((v = value(claims, movie ? "P7595" : "P7596")) != null) {
                    out.put("com.disney.disneyplus", "https://www.disneyplus.com/" + (movie ? "movies/" : "series/") + slug + "/" + v);
                }
                if ((v = value(claims, movie ? "P9586" : "P9751")) != null) {
                    out.put("com.apple.atve.androidtv.appletv", "https://tv.apple.com/" + (movie ? "movie/" : "show/") + slug + "/" + v);
                }
                if ((v = value(claims, "P8298")) != null) {
                    out.put("com.wbd.stream", "https://play.max.com/" + v);
                    out.put("com.hbo.hbonow", "https://play.hbomax.com/" + v);
                }
                if ((v = value(claims, "P8055")) != null) out.put("com.amazon.amazonvideo.livingroom", "https://app.primevideo.com/detail?gti=" + v);
                if ((v = value(claims, "P345")) != null && v.startsWith("tt")) {   // IMDb id: Stremio opens the title page
                    out.put("com.stremio.one", "stremio:///detail/" + (movie ? "movie" : "series") + "/" + v + "/" + v);
                }
            }
        } catch (Exception e) {
            AppLog.w("deep link " + key + ": " + e.getMessage());
            return out;    // not cached: the next try may reach Wikidata
        }
        synchronized (CACHE) { CACHE.put(key, out); }
        sp.edit().putString(key, System.currentTimeMillis() + "|" + new JSONObject(out)).apply();
        return out;
    }

    static final String STREMIO = "com.stremio.one";
    static final String SMARTTUBE = "org.smarttube.stable";

    /**
     * A poster or banner picked on Home or in Explore: straight to the title's page in Stremio (IMDb id from TMDB),
     * or the title's own page here when Stremio is missing or TMDB has no IMDb id.
     */
    static void openTitle(Context c, String kind, int id) {
        // "Streaming" from Explore: the title's page lists the services it is on, with a button for each.
        if (!"stremio".equals(Prefs.openIn())) {
            DetailActivity.open(c, kind, id);
            return;
        }
        openInStremio(c, kind, id);
    }

    /** The title's page in Stremio (IMDb id from TMDB), or its page here when Stremio is missing. */
    static void openInStremio(Context c, String kind, int id) {
        if (!Providers.installed(c, STREMIO)) {
            DetailActivity.open(c, kind, id);
            return;
        }
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String imdb = null;
            try {
                imdb = Tmdb.get("/" + kind + "/" + id + "/external_ids").optString("imdb_id", null);
            } catch (Exception e) {
                AppLog.w("imdb id " + kind + ":" + id + ": " + e.getMessage());
            }
            String url = imdb != null && imdb.startsWith("tt")
                    ? "stremio:///detail/" + (kind.equals("movie") ? "movie" : "series") + "/" + imdb + "/" + imdb : null;
            main.post(() -> { if (!open(c, STREMIO, url)) DetailActivity.open(c, kind, id); });
        }).start();
    }

    /** The Home hero's "Trailer" button: finds the trailer (dubbed first) and plays it in SmartTube or YouTube. */
    static void openTrailer(Context c, Recs.Pick p) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String key = Recs.trailerFor(c, p);
            main.post(() -> playYouTube(c, key));
        }).start();
    }

    /** Plays a YouTube video (a trailer) in the player chosen in Settings, SmartTube, or YouTube. */
    static void playYouTube(Context c, String key) {
        if (key == null) {
            android.widget.Toast.makeText(c, Ui.tr("Trailer não encontrado", "No trailer found"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String url = "https://www.youtube.com/watch?v=" + key;
        String player = Prefs.trailerPlayer();
        if ((!player.isEmpty() && open(c, player, url)) || open(c, SMARTTUBE, url) || open(c, "com.google.android.youtube.tv", url)) return;
        android.widget.Toast.makeText(c, Ui.tr("Instale o SmartTube ou o YouTube para ver trailers", "Install SmartTube or YouTube to watch trailers"), android.widget.Toast.LENGTH_LONG).show();
    }

    /** A Home pick: one from the Stremio library opens right where it stopped (the episode, for a series). */
    static void openPick(Context c, Recs.Pick p) {
        if (p.trailer != null) {   // a trailer: SmartTube, else any YouTube app
            String url = "https://www.youtube.com/watch?v=" + p.trailer;
            String player = Prefs.trailerPlayer();
            if ((!player.isEmpty() && open(c, player, url)) || open(c, SMARTTUBE, url) || open(c, "com.google.android.youtube.tv", url)) return;
        }
        // Settings › Tabs and Stremio › Open movies and shows in: the streaming app (default), Stremio or the page.
        String mode = Prefs.openIn();
        if (!p.cont && "page".equals(mode)) {
            DetailActivity.open(c, p.kind, p.id);
            return;
        }
        if (!p.cont && "app".equals(mode)) {
            // The service's own app, even when Settings › Services sends that service to Stremio.
            String app = p.app == null ? null : Providers.officialApp(c, p.app);
            if (app == null && p.pkg != null && !p.pkg.equals(STREMIO) && !p.pkg.equals(SMARTTUBE)) app = p.pkg;
            if (app != null && Providers.installed(c, app)) {
                openInApp(c, p, app);
                return;
            }
        }
        if (p.cont && p.imdb != null && p.imdb.startsWith("tt")) {
            boolean movie = "movie".equals(p.kind);
            String video = p.video != null && !p.video.isEmpty() ? p.video : p.imdb;
            if (open(c, STREMIO, "stremio:///detail/" + (movie ? "movie" : "series") + "/" + p.imdb + "/" + video)) return;
        }
        // Not on any of the TV's services (or Stremio chosen): its page in Stremio.
        openInStremio(c, p.kind, p.id);
    }

    /** The title right in its streaming app (Wikidata's id for it), else the app's own home. */
    private static void openInApp(Context c, Recs.Pick p, String pkg) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String url = null;
            try {
                Map<String, String> links = find(c, p.kind, p.id, p.title);
                url = links == null ? null : links.get(pkg);
            } catch (Exception e) {
                AppLog.w("deep link " + pkg + ": " + e.getMessage());
            }
            String link = url;
            main.post(() -> {
                if (open(c, pkg, link)) return;
                Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {
                    openTitle(c, p.kind, p.id);
                }
            });
        }).start();
    }

    /** Opens the title in the app when there's a link it takes; otherwise false (the caller opens the app). */
    static boolean open(Context c, String pkg, String url) {
        if (url == null) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg.equals("com.netflix.ninja")) i.putExtra("source", "30");   // Netflix for TV wants to know it's a deep link
        if (i.resolveActivity(c.getPackageManager()) == null) return false;
        try {
            c.startActivity(i);
            AppLog.w("deep link -> " + url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String value(JSONObject claims, String prop) {
        JSONArray list = claims.optJSONArray(prop);
        if (list == null || list.length() == 0) return null;
        Object v = list.optJSONObject(0).optJSONObject("mainsnak").optJSONObject("datavalue") == null ? null
                : list.optJSONObject(0).optJSONObject("mainsnak").optJSONObject("datavalue").opt("value");
        return v instanceof String ? (String) v : null;
    }

    private static String slug(String name) {
        String s = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "x" : s;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        try {
            return Tmdb.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
