package io.cleanyourtv.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/** The Clean Your TV Master space look, shared by Settings: drifting stars, the spinning red ring and the planet logo. */
final class Cosmos {
    private static final int STARS = 110;
    private final float[] x = new float[STARS], y = new float[STARS], r = new float[STARS], ph = new float[STARS],
            tw = new float[STARS], sp = new float[STARS];
    private final int[] col = new int[STARS];
    private static final int[] TINTS = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFD6D0, 0xFFFFB3A8, 0xFFFFE9C9};
    private float w, h, k;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF box = new RectF();
    private final SweepGradient sweep = new SweepGradient(0, 0,
            new int[] {0xFFFF1E2D, 0xFF8A0012, 0xFFFF5A36, 0xFFFFD2C2, 0xFFFF1E2D}, null);
    private RadialGradient planet, planetGlow;
    private LinearGradient planetShade;
    private float planetR;

    Cosmos() {
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
    }

    void setSize(float width, float height, float scale) {
        if (width == w && height == h) return;
        w = width; h = height; k = scale;
        java.util.Random rnd = new java.util.Random(4242);
        for (int i = 0; i < STARS; i++) {
            float depth = rnd.nextFloat();
            x[i] = rnd.nextFloat() * w;
            y[i] = rnd.nextFloat() * h;
            r[i] = (0.7f + 1.7f * depth * depth) * k;
            ph[i] = rnd.nextFloat() * 6.283f;
            tw[i] = 0.6f + rnd.nextFloat() * 2f;
            sp[i] = (2 + 8 * depth) * k;
            col[i] = TINTS[rnd.nextInt(TINTS.length)];
        }
        planetR = 0;
    }

    /** Stars drifting left and twinkling. */
    void drawStars(Canvas c, float t) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        for (int i = 0; i < STARS; i++) {
            float sx = ((x[i] - t * sp[i]) % w + w) % w;
            float s = 0.5f + 0.5f * (float) Math.sin(t * tw[i] + ph[i]);
            float a = 0.2f + 0.7f * s;
            p.setColor(col[i]);
            p.setAlpha(Math.round(255 * a));
            c.drawCircle(sx, y[i], r[i] * (0.8f + 0.3f * s), p);
            if (r[i] > 1.9f * k && s > 0.75f) {
                float len = r[i] * 9 * (s - 0.6f);
                p.setAlpha(Math.round(140 * a));
                p.setStrokeWidth(0.9f * k);
                c.drawLine(sx - len, y[i], sx + len, y[i], p);
                c.drawLine(sx, y[i] - len, sx, y[i] + len, p);
            }
        }
        p.setAlpha(255);
    }

    /** A ring of red light spinning around a rounded box (the focus). */
    void drawAura(Canvas c, RectF rect, float radius, float f, float t) {
        if (f < 0.02f) return;
        float pulse = 0.7f + 0.3f * (float) Math.sin(t * 3.2f);
        m.setRotate((t * 90) % 360);
        m.postTranslate(rect.centerX(), rect.centerY());
        sweep.setLocalMatrix(m);
        ring.setShader(sweep);
        float[] grow = {4, 9, 15}, width = {3.5f, 5, 7};
        int[] alpha = {235, 90, 36};
        for (int j = 0; j < 3; j++) {
            float g = grow[j] * k;
            box.set(rect.left - g, rect.top - g, rect.right + g, rect.bottom + g);
            ring.setStrokeWidth(width[j] * k);
            ring.setAlpha(Math.round(alpha[j] * f * pulse));
            c.drawRoundRect(box, radius + g, radius + g, ring);
        }
        ring.setShader(null);
        ring.setAlpha(255);
    }

    /**
     * The logo: a red planet lit from the top left, a white play sign on it and a tilted ring that turns slowly
     * around it (the back half behind the planet, the front half over it).
     */
    void drawLogo(Canvas c, float cx, float cy, float size, float t) {
        float R = size * 0.30f;
        if (planetR != R) {
            planetR = R;
            planet = new RadialGradient(-R * 0.35f, -R * 0.4f, R * 1.5f,
                    new int[] {0xFFFF6A5A, 0xFFE0142C, 0xFF7A0012, 0xFF2A0006}, new float[] {0, 0.3f, 0.75f, 1}, Shader.TileMode.CLAMP);
            planetGlow = new RadialGradient(0, 0, R * 1.9f, new int[] {0x66FF2D46, 0x22FF2D46, 0x00000000},
                    new float[] {0.45f, 0.7f, 1}, Shader.TileMode.CLAMP);
            planetShade = new LinearGradient(0, -R, 0, R, 0x00000000, 0x55000000, Shader.TileMode.CLAMP);
        }
        c.save();
        c.translate(cx, cy);
        // Glow behind.
        p.setStyle(Paint.Style.FILL);
        p.setShader(planetGlow);
        p.setAlpha(Math.round(255 * (0.8f + 0.2f * (float) Math.sin(t * 1.4f))));
        c.drawCircle(0, 0, R * 1.9f, p);
        p.setAlpha(255);
        // Ring, back half.
        float tilt = -22, rx = R * 1.75f, ry = R * 0.48f;
        drawRing(c, rx, ry, tilt, t, true);
        // Planet.
        p.setShader(planet);
        c.drawCircle(0, 0, R, p);
        p.setShader(planetShade);
        c.drawCircle(0, 0, R, p);
        p.setShader(null);
        // Play sign.
        android.graphics.Path play = new android.graphics.Path();
        float ps = R * 0.42f;
        play.moveTo(-ps * 0.55f, -ps * 0.75f);
        play.lineTo(ps * 0.85f, 0);
        play.lineTo(-ps * 0.55f, ps * 0.75f);
        play.close();
        p.setColor(0xFFFFFFFF);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(play, p);
        // Ring, front half.
        drawRing(c, rx, ry, tilt, t, false);
        // A small moon riding the ring.
        double a = t * 0.9;
        float mx = (float) Math.cos(a) * rx, my = (float) Math.sin(a) * ry;
        double rad = Math.toRadians(tilt);
        float qx = (float) (mx * Math.cos(rad) - my * Math.sin(rad)), qy = (float) (mx * Math.sin(rad) + my * Math.cos(rad));
        boolean front = Math.sin(a) > 0;
        if (front || Math.hypot(qx, qy) > R) {
            p.setColor(0xFFFFE9E2);
            c.drawCircle(qx, qy, R * 0.11f, p);
        }
        c.restore();
    }

    private void drawRing(Canvas c, float rx, float ry, float tilt, float t, boolean back) {
        c.save();
        c.rotate(tilt);
        box.set(-rx, -ry, rx, ry);
        m.setRotate((t * 60) % 360);
        sweep.setLocalMatrix(m);
        ring.setShader(sweep);
        ring.setStrokeWidth(Math.max(2, rx * 0.075f));
        ring.setAlpha(back ? 150 : 255);
        // Back: the top half of the ellipse (angles 180..360); front: the bottom half.
        c.drawArc(box, back ? 180 : 0, 180, false, ring);
        ring.setShader(null);
        ring.setAlpha(255);
        c.restore();
    }
}
