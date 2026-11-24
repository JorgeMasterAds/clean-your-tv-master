package io.cleanyourtv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Profile photos of famous characters: five per show. Anime characters come from AniList, the Pokémon from the PokeAPI
 * artwork and the live-action shows from TMDB's cast (the actor in the role, named by the character). The profile keeps
 * the picture's address; the picture is cropped to a circle.
 */
final class Avatars {
    static final int ANIME = 0, POKE = 1, SHOW = 2, WIKI = 3;

    /** A line of the photo picker: a famous show and where its five characters come from. */
    static final class Row {
        final String title;
        final int kind;
        final int id;   // ANIME: MyAnimeList id; SHOW: TMDB tv id
        final String[] pages;   // WIKI: "Wikipedia page|name shown" (more than five: some have no picture)

        Row(String title, int kind, int id, String... pages) {
            this.title = title;
            this.kind = kind;
            this.id = id;
            this.pages = pages;
        }
    }

    /** One character: its name and the picture's address. */
    static final class Face {
        final String name, ref;

        Face(String name, String ref) {
            this.name = name;
            this.ref = ref;
        }
    }

    /** The shows of the picker. Their characters are listed in assets/avatars.json, so nothing waits on the network but the pictures. */
    static final Row[] ROWS = {
            new Row("Pokémon", POKE, 0),
            new Row("Breaking Bad", WIKI, 0),
            new Row("Dragon Ball Z", ANIME, 813),
            new Row("Big Bang: A Teoria", WIKI, 0),
            new Row("Game of Thrones", WIKI, 0),
            new Row("Vingadores", WIKI, 0),
            new Row("Universo DC", WIKI, 0),
            new Row("Naruto", ANIME, 20),
            new Row("One Piece", ANIME, 21),
            new Row("Death Note", ANIME, 1535),
            new Row("Bleach", ANIME, 269),
            new Row("Ursos Sem Curso", WIKI, 0)};

    private static final String[][] POKEMON = {{"Pikachu", "25"}, {"Charmander", "4"}, {"Squirtle", "7"}, {"Bulbasaur", "1"}, {"Eevee", "133"}};
    private static final String POKE_ART = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/";
    private static final String IMAGE = "https://image.tmdb.org/t/p/w780";
    private static final String PROFILE = "https://image.tmdb.org/t/p/h632";
    private static final int SIZE = 192;
    private static final int BACK = 0xFF3A1018;

    private static final Map<String, Bitmap> CIRCLES = new HashMap<>();     // picture address -> round bitmap
    static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    /** Wikimedia and Fandom pictures, one at a time (they refuse bursts): never holds up the other rows. */
    static final ExecutorService SLOW_POOL = Executors.newSingleThreadExecutor();

    static ExecutorService poolFor(Row row) {
        return row.kind == WIKI ? SLOW_POOL : POOL;
    }

    private Avatars() {}

    private static JSONObject bundled;

    /** The characters and picture addresses that ship with the app (assets/avatars.json). */
    private static synchronized JSONObject bundled(Context c) {
        appCtx = c.getApplicationContext();
        if (bundled == null) {
            try (java.io.InputStream in = c.getAssets().open("avatars.json")) {
                bundled = new JSONObject(new String(Tmdb.readBytes(in), "UTF-8"));
            } catch (Exception e) {
                bundled = new JSONObject();
            }
        }
        return bundled;
    }

    /** Downloads every picture of the picker into the disk cache, so opening the picker finds them ready. Runs in the background. */
    static void prefetch(final Context c) {
        for (final Row row : ROWS) {
            poolFor(row).execute(() -> {
                JSONArray a = bundled(c).optJSONArray(row.title);
                for (int i = 0; a != null && i < a.length(); i++) {
                    try {
                        fetchPolite(a.getJSONArray(i).getString(1));
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    /** The row's five characters ("" list when there is no connection). Network: call off the main thread. */
    static List<Face> faces(Context c, Row row) {
        List<Face> out = new ArrayList<>();
        JSONArray listed = bundled(c).optJSONArray(row.title);
        if (listed != null && listed.length() > 0) {
            try {
                for (int i = 0; i < listed.length() && i < 5; i++) out.add(new Face(listed.getJSONArray(i).getString(0), listed.getJSONArray(i).getString(1)));
                return out;
            } catch (Exception e) {
                out.clear();
            }
        }
        String key = "row10:" + row.title;
        String saved = c.getSharedPreferences("avatars", Context.MODE_PRIVATE).getString(key, null);
        try {
            if (saved != null) {
                JSONArray a = new JSONArray(saved);
                for (int i = 0; i < a.length(); i++) out.add(new Face(a.getJSONObject(i).getString("n"), a.getJSONObject(i).getString("r")));
                if (!out.isEmpty()) return out;
            }
            if (row.kind == POKE) {
                for (String[] p : POKEMON) out.add(new Face(p[0], POKE_ART + p[1] + ".png"));
            } else if (row.kind == ANIME) {
                out.addAll(anime(row.id));
            } else if (row.kind == WIKI) {
                out.addAll(wiki(row.pages));
            } else if (Tmdb.hasKey()) {
                out.addAll(cast(row.id));
            }
        } catch (Exception e) {
            return new ArrayList<>();   // not now: asked again next time
        }
        if (out.size() >= 5) {
            try {
                JSONArray a = new JSONArray();
                for (Face f : out) a.put(new JSONObject().put("n", f.name).put("r", f.ref));
                c.getSharedPreferences("avatars", Context.MODE_PRIVATE).edit().putString(key, a.toString()).apply();
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** The anime's most loved characters (AniList). */
    private static List<Face> anime(int malId) throws Exception {
        String query = "{ Media(idMal: " + malId + ", type: ANIME) { characters(sort: FAVOURITES_DESC, perPage: 9) { nodes { name { userPreferred } image { large } } } } }";
        HttpURLConnection c = (HttpURLConnection) new URL("https://graphql.anilist.co").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        byte[] body = new JSONObject().put("query", query).toString().getBytes("UTF-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        String text;
        try (java.io.InputStream in = c.getInputStream()) {
            text = new String(Tmdb.readBytes(in), "UTF-8");
        } finally {
            c.disconnect();
        }
        JSONArray nodes = new JSONObject(text).getJSONObject("data").getJSONObject("Media").getJSONObject("characters").getJSONArray("nodes");
        List<Face> out = new ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            String image = n.getJSONObject("image").optString("large", "");
            String nm = n.getJSONObject("name").getString("userPreferred");
            if (image.isEmpty() || image.contains("default") || nm.equalsIgnoreCase("Narrator") || out.size() >= 5) continue;
            out.add(new Face(n.getJSONObject("name").getString("userPreferred"), image));
        }
        return out;
    }

    /** The show's main cast by character: the actor's photo, named by the role. */
    private static List<Face> cast(int tvId) throws Exception {
        JSONArray cast = Tmdb.get("/tv/" + tvId + "/aggregate_credits?language=" + Tmdb.lang()).getJSONArray("cast");
        List<Face> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < cast.length() && out.size() < 5; i++) {
            JSONObject p = cast.getJSONObject(i);
            String profile = p.optString("profile_path", "");
            JSONArray roles = p.optJSONArray("roles");
            if (profile.isEmpty() || "null".equals(profile) || roles == null || roles.length() == 0) continue;
            String role = roles.getJSONObject(0).optString("character", "").split("[/(]")[0].trim();
            if (role.isEmpty() || role.toLowerCase().contains("self") || !seen.add(role)) continue;
            out.add(new Face(role, PROFILE + profile));
        }
        return out;
    }

    private static final Object WIKI_LOCK = new Object();

    /** Cartoon and hero characters: the picture of their Wikipedia page, one request at a time (the site limits bursts). */
    private static List<Face> wiki(String[] pages) throws Exception {
        List<Face> out = new ArrayList<>();
        synchronized (WIKI_LOCK) {
            for (String page : pages) {
                if (out.size() >= 5) break;
                String[] parts = page.split("[|]");
                String thumb = wikiThumb(parts[0]);
                if (!thumb.isEmpty()) out.add(new Face(parts.length > 1 ? parts[1] : parts[0], thumb));
                Thread.sleep(150);
            }
        }
        return out;
    }

    private static String wikiThumb(String title) throws Exception {
        String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20");
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "CleanYourTVMaster/1.0 (Android TV launcher)");
            try {
                int code = c.getResponseCode();
                if (code == 429) {
                    Thread.sleep(2500);
                    continue;
                }
                if (code != 200) return "";
                String text = new String(Tmdb.readBytes(c.getInputStream()), "UTF-8");
                return new JSONObject(text).optJSONObject("thumbnail") == null ? "" : new JSONObject(text).getJSONObject("thumbnail").optString("source", "");
            } finally {
                c.disconnect();
            }
        }
        return "";
    }

    private static final Object SLOW = new Object();
    private static Context appCtx;

    /** Wikimedia asks for a descriptive User-Agent (the default one is refused), so these pictures are downloaded here and kept in the cache folder. */
    private static Bitmap politeDownload(String url) {
        if (appCtx == null) return null;
        java.io.File dir = new java.io.File(appCtx.getCacheDir(), "avatars");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, Integer.toHexString(url.hashCode()) + ".img");
        if (f.isFile() && f.length() > 0) {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "CleanYourTVMaster/1.0 (Android TV launcher)");
            byte[] bytes;
            try (java.io.InputStream in = c.getInputStream()) {
                bytes = Tmdb.readBytes(in);
            } finally {
                c.disconnect();
            }
            Bitmap b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b != null) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(bytes);
                }
            }
            return b;
        } catch (Exception e) {
            AppLog.w("avatar picture failed: " + e);
            return null;   // refused or offline: tried again shortly
        }
    }

    /**
     * A picture from disk or the network. Wikimedia and Fandom refuse bursts (HTTP 429), so their pictures come one at a time,
     * with a pause and a retry; each one is kept on disk, so the wait happens once.
     */
    private static Bitmap fetchPolite(String ref) {
        if (ref.startsWith("asset:")) {   // a picture that ships inside the app
            try (java.io.InputStream in = appCtx.getAssets().open(ref.substring(6))) {
                return android.graphics.BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                return null;
            }
        }
        String url = urlOf(ref);
        if (!url.contains("wikimedia.org") && !url.contains("wikia.nocookie.net")) return Images.fetch(url);
        synchronized (SLOW) {
            Bitmap b = null;
            for (int attempt = 0; attempt < 4 && b == null; attempt++) {
                b = politeDownload(url);
                if (b == null) {
                    try {
                        Thread.sleep(2500L * (attempt + 1));
                    } catch (InterruptedException e) {
                        return null;
                    }
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            return b;
        }
    }

    private static String urlOf(String ref) {
        return ref.startsWith("http") ? ref : IMAGE + ref;
    }

    /** The round photo for a picture address (null when it can't be loaded). Network: call off the main thread. */
    static Bitmap circle(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        synchronized (CIRCLES) {
            Bitmap have = CIRCLES.get(ref);
            if (have != null) return have;
        }
        Bitmap src = fetchPolite(ref);
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        Bitmap square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas sq = new Canvas(square);
        sq.drawColor(RedAlert.PALETTE[Math.floorMod(ref.hashCode(), RedAlert.PALETTE.length)]);   // see-through art gets a solid color
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean artSquare = Math.abs((float) w / h - 1f) < 0.12f && src.hasAlpha();
        if (artSquare) {
            int m = SIZE * 8 / 100;   // see-through artwork (the Pokémon): the whole picture on its color, with a margin
            sq.drawBitmap(src, new Rect(0, 0, w, h), new Rect(m, m, SIZE - m, SIZE - m), p);
        } else {
            // The circle is filled with the picture: a square taken from it (a portrait keeps the head, a little below the top)
            int side = Math.min(w, h);
            int left = (w - side) / 2, top = h > w ? Math.round((h - side) * 0.15f) : (h - side) / 2;
            sq.drawBitmap(src, new Rect(left, top, left + side, top + side), new Rect(0, 0, SIZE, SIZE), p);
        }
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(square, 0, 0, p);
        square.recycle();
        synchronized (CIRCLES) {
            CIRCLES.put(ref, out);
        }
        return out;
    }

    /** The round photo only if it is already in memory (for drawing). */
    static Bitmap cached(String ref) {
        synchronized (CIRCLES) {
            return ref == null ? null : CIRCLES.get(ref);
        }
    }

    /** Frees the pictures of the picker, keeping the ones the profiles use. */
    static void trim() {
        Set<String> keep = new HashSet<>();
        for (String part : Prefs.avatars().split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) keep.add(part.substring(eq + 1));
        }
        synchronized (CIRCLES) {
            CIRCLES.keySet().retainAll(keep);
        }
    }

    /** The profile's photo address ("" = none: its color and letter), kept per name; "@adult" is the adult profile. */
    static String of(String name) {
        for (String part : Prefs.avatars().split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) return part.substring(eq + 1);
        }
        return "";
    }
}
