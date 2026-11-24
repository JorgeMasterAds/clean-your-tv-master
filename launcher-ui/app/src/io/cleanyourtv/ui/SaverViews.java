package io.cleanyourtv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The extra screensaver styles: a plain big clock, and a slow mosaic of the picks' covers. Both move a little so no pixel stays lit. */
final class SaverViews {
    private SaverViews() {}

    /** The date and the temperature, under or beside a clock. */
    private static String dateLine(Context c, long now) {
        HomeData.Weather wx = HomeData.get(c).weather;
        String line = Ui.date(now);
        if (!Double.isNaN(wx.temp) && Prefs.weather()) line += "   ·   " + HomeData.degrees(wx.temp);
        return line;
    }

    /** A big, thin white clock on black, drifting to a new place every minute. */
    static final class Clock extends View implements Screensaver.Show {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG), date = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                handler.postDelayed(this, 1000);
            }
        };

        Clock(Context c) {
            super(c);
        }

        @Override public void start() {
            running = true;
            handler.post(tick);
        }

        @Override public void stop() {
            running = false;
            handler.removeCallbacks(tick);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            float k = w / 1920f;
            long now = System.currentTimeMillis(), minute = now / 60_000;
            c.drawColor(0xFF000000);
            clock.set(Ui.numbers(Ui.thin, 380 * k, 0xF2FFFFFF));
            clock.setTextAlign(Paint.Align.CENTER);
            date.set(Ui.text(Ui.medium, 40 * k, 0xB3FFFFFF));
            date.setTextAlign(Paint.Align.CENTER);
            float ox = (float) Math.sin(minute * 1.7) * w * 0.09f, oy = (float) Math.cos(minute * 1.3) * h * 0.09f;
            float cx = w / 2f + ox, cy = h * 0.52f + oy;
            c.drawText(Ui.clock(now), cx, cy, clock);
            c.drawText(dateLine(getContext(), now), cx, cy + 90 * k, date);
        }
    }

    /** A wall of the picks' covers sliding slowly, with the clock in the corner. */
    static final class Mosaic extends View implements Screensaver.Show {
        private static final int COLS = 5, ROWS = 4;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<BitmapShader> shaders = new ArrayList<>();
        private final List<int[]> sizes = new ArrayList<>();
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), plate = new Paint();
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private boolean running, loading;
        private long startedAt;
        private LinearGradient fade;

        Mosaic(Context c) {
            super(c);
        }

        @Override public void start() {
            running = true;
            startedAt = SystemClock.uptimeMillis();
            if (!loading && shaders.isEmpty()) {
                loading = true;
                final Context c = getContext();
                new Thread(() -> {
                    // only what you are watching (the Continue tab, from Stremio); the picks only when that is empty
                    List<Recs.Pick> picks = Recs.get(c).all("cont");
                    if (picks.isEmpty()) picks = Recs.get(c).showcase();
                    final List<Bitmap> found = new ArrayList<>();
                    for (Recs.Pick p : picks) {
                        if (found.size() >= COLS * ROWS) break;
                        File f = Recs.get(c).readyImage(p);
                        if (f == null) continue;
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 3;
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                        if (b != null) found.add(b);
                    }
                    post(() -> {
                        for (Bitmap b : found) {
                            shaders.add(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                            sizes.add(new int[] {b.getWidth(), b.getHeight()});
                        }
                        loading = false;
                    });
                }).start();
            }
            invalidate();
        }

        @Override public void stop() {
            running = false;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            float k = w / 1920f;
            c.drawColor(0xFF000000);
            long now = System.currentTimeMillis();
            if (!shaders.isEmpty()) {
                float t = (SystemClock.uptimeMillis() - startedAt) / 1000f;
                float gap = 14 * k, tw = w * 0.245f, th = tw * 9f / 16f;
                float gridW = COLS * (tw + gap), gridH = ROWS * (th + gap);
                float ox = -(gridW - w) / 2f + (float) Math.sin(t * 0.05f) * (gridW - w) / 2f;
                float oy = -(gridH - h) / 2f + (float) Math.cos(t * 0.04f) * Math.max(0, gridH - h) / 2f;
                for (int r = 0; r < ROWS; r++) {
                    // odd rows are shifted half a tile, like a wall of bricks
                    float shift = (r % 2) * (tw + gap) / 2f - (tw + gap) / 4f;
                    for (int col = 0; col < COLS; col++) {
                        // few titles: each row starts two further on, so the same cover never sits next to itself
                        int i = (col + r * 2) % shaders.size();
                        float x = ox + shift + col * (tw + gap), y = oy + r * (th + gap);
                        if (x > w || x + tw < 0 || y > h || y + th < 0) continue;
                        float s = Math.max(tw / sizes.get(i)[0], th / sizes.get(i)[1]);
                        matrix.setScale(s, s);
                        matrix.postTranslate(x - (sizes.get(i)[0] * s - tw) / 2f, y - (sizes.get(i)[1] * s - th) / 2f);
                        shaders.get(i).setLocalMatrix(matrix);
                        tile.setShader(shaders.get(i));
                        rect.set(x, y, x + tw, y + th);
                        c.drawRoundRect(rect, 18 * k, 18 * k, tile);
                    }
                }
            }
            // A dark veil, darker at the top-right where the clock sits.
            if (fade == null) fade = new LinearGradient(0, 0, w, h, new int[] {0x99000000, 0x33000000, 0x99000000}, null, Shader.TileMode.CLAMP);
            plate.setShader(fade);
            c.drawRect(0, 0, w, h, plate);
            text.set(Ui.numbers(Ui.displayMedium, 150 * k, 0xF2FFFFFF));
            text.setTextAlign(Paint.Align.RIGHT);
            text.setShadowLayer(24 * k, 0, 4 * k, 0xAA000000);
            c.drawText(Ui.clock(now), w - 90 * k, 200 * k, text);
            text.set(Ui.text(Ui.medium, 34 * k, 0xE6FFFFFF));
            text.setTextAlign(Paint.Align.RIGHT);
            text.setShadowLayer(18 * k, 0, 3 * k, 0xAA000000);
            c.drawText(dateLine(getContext(), now), w - 90 * k, 262 * k, text);
            if (running) postInvalidateDelayed(40);
        }
    }
}
