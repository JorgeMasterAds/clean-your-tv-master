package io.cleanyourtv.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Window;
import android.widget.TextView;

/**
 * The app's dialogs in its own colors: a deep red gradient with a thin glow edge instead of the system's gray panel.
 * Use it where an AlertDialog.Builder was: `new RedAlert(context)...show()`.
 */
final class RedAlert extends AlertDialog.Builder {
    /** The colors a profile avatar can take. */
    static final int[] PALETTE = {0xFFE50914, 0xFFFF8A00, 0xFFFFC400, 0xFF2ECC71, 0xFF1E90FF, 0xFF9B59B6, 0xFFFF4DA6, 0xFF00BFA5};

    RedAlert(Context c) {
        super(c, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    @Override public AlertDialog create() {
        AlertDialog d = super.create();
        style(d);
        return d;
    }

    static void style(Dialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        float density = d.getContext().getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF4F0813, 0xFF1D050E, 0xFF0C0207});
        g.setCornerRadius(26 * density);
        g.setStroke(Math.max(1, Math.round(1.5f * density)), 0x66FF4D5E);
        w.setBackgroundDrawable(g);
        w.setDimAmount(0.72f);
    }

    /** A wide dialog (the picture pickers): most of the screen, so nothing is cut at the sides. Call after show(). */
    static void wide(Dialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        int width = d.getContext().getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.round(width * 0.94f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
    }

    /** A profile's color: the one it was given, or one from its name. */
    static int colorOf(String name) {
        String saved = Prefs.kidColors();
        for (String part : saved.split(",")) {
            int eq = part.lastIndexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                try {
                    return PALETTE[Math.floorMod(Integer.parseInt(part.substring(eq + 1)), PALETTE.length)];
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return PALETTE[Math.floorMod(name.hashCode(), PALETTE.length)];
    }

    /** The round avatar: the color with the name's first letter (a person for the adult profile). */
    static TextView avatar(Context c, String name, int color, float sizeDp) {
        float density = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(name == null || name.isEmpty() ? "" : name.substring(0, 1).toUpperCase());
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(sizeDp * 0.42f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        t.setBackground(g);
        int px = Math.round(sizeDp * density);
        t.setLayoutParams(new android.widget.LinearLayout.LayoutParams(px, px));
        return t;
    }

    /** A focusable row for a custom dialog: an optional leading view and a label; white pill with dark text when focused. */
    static android.view.View row(Context c, android.view.View leading, String label, Runnable onClick) {
        float density = c.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout r = new android.widget.LinearLayout(c);
        r.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(14 * density);
        r.setPadding(pad, Math.round(10 * density), pad * 2, Math.round(10 * density));
        r.setFocusable(true);
        r.setClickable(true);
        if (leading != null) {
            r.addView(leading);
            android.view.View gap = new android.view.View(c);
            gap.setLayoutParams(new android.widget.LinearLayout.LayoutParams(Math.round(16 * density), 1));
            r.addView(gap);
        }
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(20);
        t.setTextColor(0xFFFFFFFF);
        r.addView(t);
        final GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(40 * density);
        r.setOnFocusChangeListener((v, focus) -> {
            r.setBackground(focus ? on : null);
            t.setTextColor(focus ? 0xFF1A0509 : 0xFFFFFFFF);
        });
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    /** The dialog's content: a column of rows (in a scroll view when there are many). */
    static android.view.View column(Context c, java.util.List<android.view.View> rows) {
        float density = c.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout col = new android.widget.LinearLayout(c);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(Math.round(18 * density), Math.round(6 * density), Math.round(18 * density), Math.round(10 * density));
        for (android.view.View v : rows) col.addView(v);
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.addView(col);
        return sv;
    }

    /** A profile tile, Netflix style: the round avatar on top, the name under it; it grows with the focus and gets a white ring. */
    static android.view.View tile(Context c, String name, int color, String letter, String photo, int sizeDp, Runnable onClick) {
        float density = c.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout t = new android.widget.LinearLayout(c);
        t.setOrientation(android.widget.LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round((sizeDp > 90 ? 18 : 12) * density);
        t.setPadding(pad, pad, pad, pad);
        t.setFocusable(true);
        t.setClickable(true);
        TextView av = avatar(c, letter, color, sizeDp);
        t.addView(av);
        t.setTag(av);
        if (photo != null && !photo.isEmpty()) loadPhoto(av, photo);
        TextView label = new TextView(c);
        label.setText(name);
        label.setTextSize(sizeDp > 90 ? 18 : 13);
        label.setTextColor(0xB3FFFFFF);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Math.round(8 * density), 0, 0);
        // one line, a little wider than the circle, so a two-word name ("Homem de Ferro") is never cut in half
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (sizeDp > 90) {
            label.setMaxWidth(Math.round((sizeDp + 30) * density));
            t.addView(label);
        } else {
            // the photo grid's names arrive after the tile is laid out: a fixed width that fits "Capitão América"
            label.setTextSize(12);
            t.addView(label, new android.widget.LinearLayout.LayoutParams(Math.round((sizeDp + 50) * density),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Math.round(4 * density), 0xFFFFFFFF);
        t.setOnFocusChangeListener((v, focus) -> {
            float grow = sizeDp > 90 ? 1.14f : 1.12f;
            v.animate().scaleX(focus ? grow : 1f).scaleY(focus ? grow : 1f).setDuration(120).start();
            av.setForeground(focus ? ring : null);
            label.setTextColor(focus ? 0xFFFFFFFF : 0xB3FFFFFF);
        });
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    /** The photo (a TMDB image path) loads in the background and replaces the color and letter. */
    static void loadPhoto(final TextView av, final String path) {
        android.graphics.Bitmap have = Avatars.cached(path);
        if (have != null) {
            av.setText("");
            av.setBackground(new android.graphics.drawable.BitmapDrawable(av.getResources(), have));
            return;
        }
        new Thread(() -> {
            final android.graphics.Bitmap b = Avatars.circle(path);
            if (b == null) return;
            av.post(() -> {
                av.setText("");
                av.setBackground(new android.graphics.drawable.BitmapDrawable(av.getResources(), b));
            });
        }).start();
    }

    /** Tiles side by side (scrolls sideways when there are many). */
    static android.view.View strip(Context c, java.util.List<android.view.View> tiles) {
        float density = c.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(Math.round(22 * density), Math.round(18 * density), Math.round(22 * density), Math.round(18 * density));
        for (android.view.View v : tiles) row.addView(v);
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(row);
        return sv;
    }
}
