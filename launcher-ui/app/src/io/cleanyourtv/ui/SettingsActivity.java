package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's settings: the
 * big symbol and title on the left, the list on the right with the focused row in white. Switching the home screen
 * and turning apps off is done from the computer (tools/), so nothing here needs ADB.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private View preview;   // "Screensaver": the screensaver right here, in the style picked, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        TvConfig.apply(this);
        view.resume();
        // From ADB: am start -n io.cleanyourtv.ui/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = Screensaver.make(this);
        setContentView(preview);
        ((Screensaver.Show) preview).start();
    }

    private void closePreview() {
        if (preview == null) return;
        ((Screensaver.Show) preview).stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, MASTER = 6;
        private static final float LIST_ROW_H = 60, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 96, LIST_R = 1000, LIST_TOP = 250, ROW_H = 62, ROW_GAP = 6;
        // The info panel on the right: what the focused option does and how it is now.
        private static final float PANEL_X = 1080, PANEL_R = 1824, PANEL_TOP = 250, PANEL_BOTTOM = 980;
        private static final int AMBER = 0xFFFFC266, INK = 0xFFFFFFFF;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll, keysScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown;
        private final Spoken spoken;
        private long startedAt, lastNanos, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                invalidate();
                handler.postDelayed(this, 5000);   // the TMDB status changes slowly: no disk read every 2 s
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("Clean Your TV Master");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    String[] names = {Ui.tr("Protetor de tela, ver agora", "Screensaver, preview") + ", " + styleName(Prefs.screensaverStyle()),
                            Ui.tr("Aparência e clima", "Home Settings"),
                            Ui.tr("Apps do dock", "Shelf"), Ui.tr("Atalhos do controle", "Remote Buttons"), Ui.tr("Meus streamings", "Services"),
                            Ui.tr("Conta TMDB, ", "TMDB account, ") + account(), Ui.tr("Conta Stremio, ", "Stremio account, ") + stremioState(),
                            Ui.tr("Categoria inicial, ", "Home category, ") + Recs.categoryLabel(), Ui.tr("Abas e reprodução", "Tabs and Stremio"),
                            Ui.tr("Configurações do Android", "TV Settings")};
                    return row < names.length ? names[row] : "";
                }
                case MASTER:
                case HOME_SETTINGS: {
                    String[] labels = settingLabels(), values = settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case REMOTE_KEYS: {
                    List<RemoteKeys.Button> keys = RemoteKeys.buttons(getContext());
                    int at = keysScroll + row;
                    return at < keys.size() ? keys.get(at).name + ", " + describe(RemoteKeys.action(keys.get(at))) : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = LIST_TOP + row * (ROW_H + ROW_GAP);
                height = ROW_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = false;
            for (Spring s : pop) moving |= !s.settled();
            invalidate();   // the animated red background moves on every frame
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFD3122C);   // the focused row: Master red
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 10;
                case MASTER:
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Math.min(PICK_VISIBLE, RemoteKeys.buttons(getContext()).size());
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            drawMasterBackground(c, seconds);
            cosmos.setSize(w, getHeight(), k);
            cosmos.drawStars(c, seconds);
            auraT = seconds;
            drawHeader(c);
            drawPanel(c);

            if (screen == MAIN) drawMain(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else drawList(c);
        }

        private void drawMain(Canvas c) {
            String[] labels = {Ui.tr("Protetor de tela", "Screensaver"), Ui.tr("Aparência e clima", "Home Settings"),
                    Ui.tr("Apps do dock", "Shelf"), Ui.tr("Atalhos do controle", "Remote Buttons"), Ui.tr("Meus streamings", "Services"),
                    Ui.tr("Conta TMDB", "TMDB account"), Ui.tr("Conta Stremio", "Stremio account"),
                    Ui.tr("Categoria inicial", "Home category"), Ui.tr("Abas e reprodução", "Tabs and Stremio"),
                    Ui.tr("Configurações do Android", "TV Settings")};
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                Symbols.draw(getContext(), c, MAIN_ICONS[i], rowRect.left + 44 * k, rowRect.centerY(), 30 * k, blend(0xFFFF4D5E, 0xFFFFFFFF, f));
                c.drawText(labels[i], rowRect.left + 84 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i == 0 || i == 5 || i == 6 || i == 7) {
                    rowValue.setColor(blend(0x8CFFFFFF, 0xD9FFFFFF, f));
                    // The screensaver's style: ← → change it (shown while focused), OK shows it.
                    String style = styleName(Prefs.screensaverStyle());
                    String value = i == 5 ? account() : i == 6 ? stremioState() : i == 7 ? Recs.categoryLabel()
                            : f > 0.5f ? "‹  " + style + "  ›" : style;
                    c.drawText(value, right, base, rowValue);
                } else {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0xE6FFFFFF, f));
                }
            }
        }

        private final Cosmos cosmos = new Cosmos();
        private float auraT;
        private Paint glowPaint, lineTitle, panelTitle;
        private TextPaint panelText;
        private android.graphics.RadialGradient[] glows;
        private final android.graphics.Matrix glowMatrix = new android.graphics.Matrix();
        private static final String[] MAIN_ICONS = {"bedtime", "home", "apps", "tune", "live_tv", "cloud", "sync", "search", "play", "tv_settings"};
        private static final int GREET_ROW = 7;

        /** Black with slow red lights drifting behind everything (about one full turn a minute). */
        private void drawMasterBackground(Canvas c, float t) {
            float W = w, H = getHeight();
            c.drawColor(0xFF070708);
            if (glows == null) {
                glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                glows = new android.graphics.RadialGradient[] {
                        new android.graphics.RadialGradient(0, 0, 1, new int[] {0xB0C8102E, 0x40800018, 0x00000000}, new float[] {0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP),
                        new android.graphics.RadialGradient(0, 0, 1, new int[] {0x90FF2D46, 0x30600010, 0x00000000}, new float[] {0, 0.5f, 1}, android.graphics.Shader.TileMode.CLAMP),
                        new android.graphics.RadialGradient(0, 0, 1, new int[] {0x707A0010, 0x20300008, 0x00000000}, new float[] {0, 0.6f, 1}, android.graphics.Shader.TileMode.CLAMP)};
            }
            float[][] orbit = {{0.18f, 0.25f, 0.10f, 0.08f, 0.11f, 0.62f}, {0.82f, 0.78f, 0.12f, 0.10f, 0.07f, 0.48f}, {0.55f, 0.10f, 0.20f, 0.06f, 0.05f, 0.75f}};
            for (int i = 0; i < glows.length; i++) {
                float[] o = orbit[i];
                float cx = W * (o[0] + o[2] * (float) Math.sin(t * o[4] + i * 2.1f));
                float cy = H * (o[1] + o[3] * (float) Math.cos(t * o[4] * 1.3f + i));
                float radius = W * o[5] * (1 + 0.08f * (float) Math.sin(t * 0.4f + i));
                glowMatrix.setScale(radius, radius);
                glowMatrix.postTranslate(cx, cy);
                glows[i].setLocalMatrix(glowMatrix);
                glowPaint.setShader(glows[i]);
                c.drawRect(0, 0, W, H, glowPaint);
            }
        }

        /** The logo, the name and the credit, across the top. */
        private void drawHeader(Canvas c) {
            float x = LIST_X * k, top = 52 * k, size = 104 * k;
            cosmos.drawLogo(c, x + size / 2, top + size / 2, size * 1.25f, auraT);
            if (lineTitle == null) {
                lineTitle = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
                lineTitle.setTextAlign(Paint.Align.LEFT);
            }
            float tx = x + size + 30 * k;
            c.drawText("Clean Your TV Master", tx, top + 58 * k, lineTitle);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Criado por Jorge Master", "Made by Jorge Master") + "  ·  " + versionName(getContext()).replace("Clean Your TV Master ", Ui.tr("versão ", "version "))
                    + "  ·  github.com/JorgeMasterAds/clean-your-tv-master", tx, top + 98 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
        }

        private String[] mainInfo(int i) {
            switch (i) {
                case 0: return new String[] {Ui.tr("Protetor de tela", "Screensaver"), Ui.tr("O que aparece quando a TV fica parada. As setas trocam o estilo, OK mostra agora.", "What shows when the TV sits idle. The arrows change the style, OK shows it now."), styleName(Prefs.screensaverStyle())};
                case 1: return new String[] {Ui.tr("Aparência e clima", "Home Settings"), Ui.tr("Idioma, temperatura, relógio, clima e cidade.", "Language, temperature, clock, weather and city."), ""};
                case 2: return new String[] {Ui.tr("Apps do dock", "Shelf"), Ui.tr("Quais apps aparecem na fileira da Início e em que ordem.", "Which apps sit on the Home row, and in what order."), ""};
                case 3: return new String[] {Ui.tr("Atalhos do controle", "Remote Buttons"), Ui.tr("Troque o que cada botão do controle abre: um app, uma entrada HDMI ou uma função. Vale de dentro de qualquer app.", "Change what each remote button opens: an app, an HDMI input or a function, from inside any app."), RemoteKeys.remoteName(getContext())};
                case 4: return new String[] {Ui.tr("Meus streamings", "Services"), Ui.tr("Os streamings que você assina e o app que toca os trailers.", "The streaming services you pay for, and the app that plays trailers."), ""};
                case 5: return new String[] {Ui.tr("Conta TMDB", "TMDB account"), Ui.tr("A chave grátis do TMDB traz as fotos, as sugestões, as categorias e os trailers.", "The free TMDB key brings the photos, picks, categories and trailers."), account()};
                case 6: return new String[] {Ui.tr("Conta Stremio", "Stremio account"), Ui.tr("Liga a aba Continuar: o que você estava vendo no Stremio, com a barra de onde parou. Sem senha na TV: aparece um código para abrir no celular.", "Turns on the Continue tab: what you were watching in Stremio, with where you stopped. No password on the TV: a code to open on your phone."), stremioState()};
                case 7: return new String[] {Ui.tr("Categoria inicial", "Home category"), Ui.tr("A lista que a fileira de categorias mostra primeiro. Na Início, é só andar pela fileira abaixo das abas.", "The list the category row shows first. On Home, just walk along the row under the tabs."), Recs.categoryLabel()};
                case 8: return new String[] {Ui.tr("Abas e reprodução", "Tabs and Stremio"), Ui.tr("Ligue ou desligue as abas, escolha onde os títulos abrem, o app dos trailers, a troca do destaque, quantos títulos por aba, infantil e a saudação.", "Turn tabs on or off, pick where titles open, the trailer app, the hero timing, titles per tab, kids titles and the greeting."), ""};
                default: return new String[] {Ui.tr("Configurações do Android", "TV Settings"), Ui.tr("As configurações do Android TV: rede, som, imagem e apps.", "Android TV's own settings: network, sound, picture and apps."), ""};
            }
        }

        private static final String[][] MASTER_INFO = {
                {"Mostra até 5 títulos que você estava vendo no Stremio. Desligue se não usa o Stremio.", "Shows up to 5 titles you were watching in Stremio. Turn it off if you don't use Stremio."},
                {"A fileira abaixo das abas: trailers, Top 10, em alta em cada streaming, nota alta, Marvel, DC e Anime. No fim de uma lista, passa para a próxima.", "The row under the tabs: trailers, Top 10, trending on each service, top rated, Marvel, DC and Anime. At the end of a list it moves on to the next."},
                {"OK num título abre no app de streaming onde ele está (HBO Max, Netflix...), no Stremio ou na página do título. O Continuar sempre abre no Stremio.", "OK on a title opens it in the streaming app it is on (HBO Max, Netflix...), in Stremio or on its page. Continue always opens in Stremio."},
                {"Onde tocam os trailers: SmartTube, YouTube ou o primeiro que a TV tiver.", "Where trailers play: SmartTube, YouTube or the first one the TV has."},
                {"De quanto em quanto tempo o destaque passa para o próximo título sozinho.", "How often the hero moves on to the next title by itself."},
                {"Quantos títulos cada aba mostra (o Continuar mostra até 5).", "How many titles each tab shows (Continue shows up to 5)."},
                {"Filmes e séries infantis ficam fora das sugestões e das categorias.", "Kids movies and shows stay out of the picks and the categories."},
                {"A saudação muda com a hora (bom dia, boa tarde, boa noite, boa madrugada) e chama você pelo nome. OK para mudar o nome.", "The name after \"Welcome\" at the top of Home. OK to change it."},
                {"Mostra os jogos do computador na aba Jogos e no dock, e abre o jogo pelo controle. No computador, abra o Jogos do PC (pasta pc-games); aqui, OK procura o computador na rede e você clica em Sim na tela dele. Com ele conectado, OK desconecta.", "Shows the computer's games in the Games tab and the dock, and opens them with the remote. Start PC Games on the computer (pc-games folder); here, OK finds it on the network and you click Yes on its screen. When connected, OK disconnects."},
                {"A entrada HDMI onde o computador está ligado: a TV muda para ela quando o jogo abre. Em \"Nenhuma\" (projetor, TV sem entradas) a imagem do PC chega sozinha. As setas trocam.", "The HDMI input the computer is plugged into: the TV switches to it when a game opens. \"None\" (projector, TV without inputs): the PC's picture arrives by itself. The arrows change it."},
                {"Qual tela do computador mostra o jogo aberto por esta TV: ela vira a única tela enquanto o jogo roda e tudo volta ao fechar. OK busca as telas do PC.", "Which of the computer's screens shows a game opened from this TV: it becomes the only screen while the game runs, and everything comes back when it closes. OK lists the PC's screens."},
                {"Gêneros que a tela inicial nunca sugere, nas abas e nas categorias (terror, romance...). OK marca e desmarca. O Explorar e a busca continuam achando tudo.", "Genres Home never suggests, in its tabs and categories (horror, romance...). OK ticks and unticks them. Explore and search still find everything."},
        };

        /**
         * The remote's buttons work inside other apps only with the app's accessibility service on, which only the
         * person can switch on: say so and open Accessibility, once per visit while it is off.
         */
        private void askKeysService() {
            String on = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on != null && on.contains(getContext().getPackageName() + "/")) return;
            Toast.makeText(activity, Ui.tr("Para os botões funcionarem dentro dos apps, ligue \"Clean Your TV Master\" em Acessibilidade e volte aqui.",
                    "For the buttons to work inside apps, turn on \"Clean Your TV Master\" in Accessibility and come back here."), Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }

        /** What the focused option does, and how it is now; the TMDB status and credit at the bottom. */
        private void drawPanel(Canvas c) {
            if (panelTitle == null) {
                panelTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
                panelTitle.setTextAlign(Paint.Align.LEFT);
                panelText = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xCCFFFFFF));
            }
            float x = PANEL_X * k, r = PANEL_R * k, top = PANEL_TOP * k, bottom = PANEL_BOTTOM * k;
            tmp.set(x, top, r, bottom);
            Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
            glass.setColor(0x66000000);
            c.drawRoundRect(tmp, 28 * k, 28 * k, glass);
            glass.setStyle(Paint.Style.STROKE);
            glass.setStrokeWidth(2 * k);
            glass.setColor(0x55FF2D46);
            c.drawRoundRect(tmp, 28 * k, 28 * k, glass);
            String name, about, now = "";
            String icon = "sparkles";
            if (screen == MAIN) {
                String[] info = mainInfo(Math.min(focus, 9));
                name = info[0];
                about = info[1];
                now = info[2];
                icon = MAIN_ICONS[Math.min(focus, 9)];
            } else if (screen == MASTER) {
                String[] labels = masterLabels(), values = masterValues();
                int i = Math.min(focus, labels.length - 1);
                name = labels[i];
                about = Ui.en ? MASTER_INFO[i][1] : MASTER_INFO[i][0];
                now = values[i];
                icon = "play";
            } else if (screen == HOME_SETTINGS) {
                name = Ui.tr("Aparência e clima", "Home Settings");
                about = Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value.");
                icon = "home";
            } else if (screen == REMOTE_KEYS || screen == PICKER) {
                name = Ui.tr("Atalhos do controle", "Remote Buttons");
                about = Ui.tr("Escolha o botão e o que ele abre. Os outros continuam como sempre.", "Pick a button and what it opens. The rest work as always.");
                icon = "tune";
            } else {
                name = Ui.tr("Apps do dock", "Shelf");
                about = Ui.tr("OK põe ou tira o app; as setas mudam a posição.", "OK adds or removes the app; the arrows move it.");
                icon = "apps";
            }
            float px = x + 48 * k, py = top + 70 * k;
            Symbols.draw(getContext(), c, icon, px + 34 * k, py + 10 * k, 64 * k, 0xFFFF4D5E);
            c.drawText(name, px, py + 120 * k, panelTitle);
            StaticLayout text = StaticLayout.Builder.obtain(about, 0, about.length(), panelText, Math.round(r - px - 48 * k))
                    .setLineSpacing(6 * k, 1).build();
            c.save();
            c.translate(px, py + 150 * k);
            text.draw(c);
            c.restore();
            if (now != null && !now.isEmpty()) {
                float ny = py + 190 * k + text.getHeight();
                c.drawText(Ui.tr("AGORA", "NOW"), px, ny, header);
                Paint value = Ui.text(Ui.semibold, 30 * k, 0xFFFF4D5E);
                value.setTextAlign(Paint.Align.LEFT);
                c.drawText(now, px, ny + 44 * k, value);
            }
            drawRecs(c, bottom - 130 * k, px);
        }

        private String[] masterLabels() {
            return new String[] {Ui.tr("Aba Continuar (Stremio)", "Continue tab (Stremio)"), Ui.tr("Fileira de categorias", "Category row"),
                    Ui.tr("Abrir filmes e séries em", "Open movies and shows in"), Ui.tr("Trailers tocam em", "Trailers play in"),
                    Ui.tr("Trocar o destaque a cada", "Next hero title every"), Ui.tr("Títulos por aba", "Titles per tab"),
                    Ui.tr("Infantil nas sugestões", "Kids titles in picks"), Ui.tr("Nome na saudação", "Name in the greeting"),
                    Ui.tr("Jogos do PC", "PC games"), Ui.tr("Entrada do computador", "Computer's input"),
                    Ui.tr("Tela do computador", "Computer's screen"), Ui.tr("Gêneros bloqueados", "Blocked genres")};
        }

        private static final String[] TRAILER_APPS = {"", DeepLinks.SMARTTUBE, "com.google.android.youtube.tv"};
        private static final int[] HERO_EVERY = {8, 15, 30, 0}, PER_TAB = {10, 15, 20};

        private String[] masterValues() {
            String tp = Prefs.trailerPlayer();
            int every = Prefs.heroEvery();
            String name = Prefs.greetName();
            return new String[] {
                    Prefs.tabCont() ? Ui.tr("Ligada", "On") : Ui.tr("Desligada", "Off"),
                    Prefs.tabCat() ? Ui.tr("Ligada", "On") : Ui.tr("Desligada", "Off"),
                    "page".equals(Prefs.openIn()) ? Ui.tr("Página do título", "Title page")
                            : "stremio".equals(Prefs.openIn()) ? "Stremio" : Ui.tr("App de streaming", "Streaming app"),
                    tp.equals(DeepLinks.SMARTTUBE) ? "SmartTube" : tp.equals("com.google.android.youtube.tv") ? "YouTube" : Ui.tr("Automático", "Automatic"),
                    every <= 0 ? Ui.tr("Não trocar", "Never") : every + " s",
                    String.valueOf(Prefs.perTab()),
                    Prefs.noKids() ? Ui.tr("Esconder", "Hide") : Ui.tr("Mostrar", "Show"),
                    HomeView.greeting(name),
                    Games.configured() ? (Prefs.gamesName().isEmpty() ? Ui.tr("Conectado", "Connected") : Prefs.gamesName())
                            : Ui.tr("Não conectado", "Not connected"),
                    Prefs.gamesInput().isEmpty() ? Ui.tr("Nenhuma", "None") : Prefs.gamesInput(),
                    Prefs.gamesScreen().isEmpty() ? Ui.tr("Padrão do PC", "PC's default") : Prefs.getString("gamesScreenName", Prefs.gamesScreen()),
                    blockedSummary()};
        }

        /** "Nenhum", "Terror" or "Terror, Romance e mais 2". */
        private String blockedSummary() {
            List<String> names = new ArrayList<>();
            String keys = "," + Prefs.blockedGenres() + ",";
            for (String[] g : Recs.blockableGenres()) if (keys.contains("," + g[0] + ",")) names.add(Ui.tr(g[1], g[2]));
            if (names.isEmpty()) return Ui.tr("Nenhum", "None");
            if (names.size() <= 2) return android.text.TextUtils.join(", ", names);
            return names.get(0) + ", " + names.get(1) + Ui.tr(" e mais ", " and ") + (names.size() - 2) + Ui.tr("", " more");
        }

        /** Settings › Gêneros bloqueados: tick the genres Home should never suggest (Explore and search still find them). */
        private void pickBlockedGenres() {
            String[][] all = Recs.blockableGenres();
            String[] names = new String[all.length];
            boolean[] on = new boolean[all.length];
            String keys = "," + Prefs.blockedGenres() + ",";
            for (int i = 0; i < all.length; i++) {
                names[i] = Ui.tr(all[i][1], all[i][2]);
                on[i] = keys.contains("," + all[i][0] + ",");
            }
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Não sugerir estes gêneros", "Never suggest these genres"))
                    .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                    .setPositiveButton(Ui.tr("Salvar", "Save"), (d, w) -> {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < all.length; i++) if (on[i]) sb.append(sb.length() == 0 ? "" : ",").append(all[i][0]);
                        Prefs.setBlockedGenres(sb.toString());
                        Recs.get(getContext()).clearCategories();
                        HomeData.get(getContext()).settingsChanged();
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .show();
        }

        private static int next(int[] all, int now, int dir) {
            int at = 0;
            for (int i = 0; i < all.length; i++) if (all[i] == now) at = i;
            return all[(at + dir + all.length) % all.length];
        }

        private void adjustMaster(int dir) {
            Context ctx = getContext();
            switch (focus) {
                case 0: Prefs.setTabCont(!Prefs.tabCont()); break;
                case 1: Prefs.setTabCat(!Prefs.tabCat()); break;
                case 2: {
                    String[] modes = {"app", "stremio", "page"};
                    int at = Math.max(0, java.util.Arrays.asList(modes).indexOf(Prefs.openIn()));
                    Prefs.setOpenIn(modes[(at + dir + modes.length) % modes.length]);
                    break;
                }
                case 3: {
                    int at = java.util.Arrays.asList(TRAILER_APPS).indexOf(Prefs.trailerPlayer());
                    Prefs.setTrailerPlayer(TRAILER_APPS[(Math.max(0, at) + dir + TRAILER_APPS.length) % TRAILER_APPS.length]);
                    break;
                }
                case 4: Prefs.setHeroEvery(next(HERO_EVERY, Prefs.heroEvery(), dir)); break;
                case 5: Prefs.setPerTab(next(PER_TAB, Prefs.perTab(), dir)); Recs.get(ctx).clearCategories(); break;
                case 6: Prefs.setNoKids(!Prefs.noKids()); Recs.get(ctx).clearCategories(); break;
                case 9: {
                    List<String> names = new ArrayList<>();
                    names.add("");   // none: projector or TV without inputs
                    for (HomeApps.Item it : HomeApps.allInputs(ctx)) if (it.inputId != null) names.add(it.label);
                    int at = names.indexOf(Prefs.gamesInput());
                    Prefs.setGamesInput(names.get(at < 0 ? 0 : (at + dir + names.size()) % names.size()));
                    break;
                }
                default: return;
            }
            HomeData.get(ctx).settingsChanged();
            spoken.focusMoved();
        }

        /** Settings › Tela do computador: the PC lists its screens; the one picked is sent with every launch. */
        private void pickPcScreen() {
            if (!Games.configured()) {
                Toast.makeText(activity, Ui.tr("Conecte o computador primeiro (Jogos do PC).", "Connect the computer first (PC games)."), Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                List<String[]> list = Games.screens();
                post(() -> {
                    if (list == null) {
                        Toast.makeText(activity, Ui.tr("O computador não respondeu.", "The computer didn't answer."), Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] names = new String[list.size() + 2];
                    names[0] = Ui.tr("Padrão do PC", "PC's default");
                    names[1] = Ui.tr("Não trocar as telas", "Don't switch screens");
                    for (int i = 0; i < list.size(); i++) names[i + 2] = list.get(i)[1];
                    new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(Ui.tr("Tela do computador", "Computer's screen"))
                            .setItems(names, (d, which) -> {
                                Prefs.setGamesScreen(which == 0 ? "" : which == 1 ? "none" : list.get(which - 2)[0]);
                                Prefs.putString("gamesScreenName", which < 2 ? names[which] : list.get(which - 2)[1]);
                                invalidate();
                            })
                            .show();
                });
            }).start();
        }

        private void askGreeting() {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setText(Prefs.greetName());
            field.setHint(Ui.tr("Seu nome (vazio = só \"Bom dia\")", "Your name (empty = just \"Good morning\")"));
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Nome na saudação", "Name in the greeting"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Salvar", "Save"), (d, which) -> {
                        String v = field.getText().toString().trim();
                        Prefs.setGreetName(v.length() > 24 ? v.substring(0, 24) : v);
                        HomeData.get(getContext()).settingsChanged();
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .show();
        }

        private String stremioState() {
            String auth = getContext().getSharedPreferences("stremio", Context.MODE_PRIVATE).getString("auth", "");
            return auth.isEmpty() ? Ui.tr("Não conectada", "Not connected") : Ui.tr("Conectada", "Connected");
        }

        /** The category the Home's last tab shows (the same list as the tab's own selector). */
        private void chooseHomeCategory() {
            String[][] all = Recs.CATEGORIES;
            String[] names = new String[all.length];
            int at = 0;
            for (int i = 0; i < all.length; i++) {
                names[i] = all[i][1];
                if (all[i][0].equals(Recs.category())) at = i;
            }
            new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Categoria inicial", "Home category"))
                    .setSingleChoiceItems(names, at, (d, which) -> {
                        d.dismiss();
                        Recs.get(getContext()).setCategory(all[which][0]);
                        invalidate();
                    })
                    .show();
        }

        /**
         * Links the Stremio account without typing a password on the TV: Stremio gives a short code, opened on the
         * phone (link.stremio.com/CODE) while signed in; the TV checks every 3 s and keeps the key it gets.
         */
        private void linkStremio() {
            android.content.SharedPreferences sp = getContext().getSharedPreferences("stremio", Context.MODE_PRIVATE);
            if (!sp.getString("auth", "").isEmpty()) {
                new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(Ui.tr("Conta Stremio", "Stremio account"))
                        .setMessage(Ui.tr("Conectada. O Continuar assistindo vem dela.", "Connected. Continue watching comes from it."))
                        .setPositiveButton(Ui.tr("Desconectar", "Disconnect"), (d, w2) -> {
                            sp.edit().remove("auth").apply();
                            invalidate();
                        })
                        .setNegativeButton(Ui.tr("Fechar", "Close"), null)
                        .show();
                return;
            }
            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Conta Stremio", "Stremio account"))
                    .setMessage(Ui.tr("Pedindo um código…", "Getting a code…"))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .show();
            new Thread(() -> {
                try {
                    org.json.JSONObject r = new org.json.JSONObject(Tmdb.readAll(stremioLink("https://link.stremio.com/api/create?type=Create")))
                            .getJSONObject("result");
                    String code = r.getString("code");
                    post(() -> dialog.setMessage(Ui.tr("No celular, entre no Stremio e abra:\n\nlink.stremio.com/" + code + "\n\nEsta tela fecha sozinha quando ligar.",
                            "On your phone, sign in to Stremio and open:\n\nlink.stremio.com/" + code + "\n\nThis closes by itself once linked.")));
                    for (int i = 0; i < 200 && dialog.isShowing(); i++) {   // up to 10 minutes
                        Thread.sleep(3000);
                        org.json.JSONObject res = new org.json.JSONObject(Tmdb.readAll(
                                stremioLink("https://link.stremio.com/api/read?type=Read&code=" + code))).optJSONObject("result");
                        String key = res == null ? "" : res.optString("authKey", "");
                        if (!key.isEmpty()) {
                            sp.edit().putString("auth", key).apply();
                            post(() -> {
                                dialog.dismiss();
                                Toast.makeText(activity, Ui.tr("Stremio conectado.", "Stremio linked."), Toast.LENGTH_LONG).show();
                                invalidate();
                            });
                            return;
                        }
                    }
                } catch (Exception e) {
                    AppLog.w("stremio link: " + e.getMessage());
                    post(() -> dialog.setMessage(Ui.tr("Não deu para ligar agora: ", "Couldn't link now: ") + e.getMessage()));
                }
            }).start();
        }

        private String account() {
            if (!Tmdb.hasKey()) return Ui.tr("Sem chave", "No key");
            return Tmdb.signedIn(getContext()) ? Tmdb.accountName(getContext()) : Ui.tr("Não conectada", "Not connected");
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y, float x) {
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: digite a sua em Conta TMDB.", "No TMDB key: type yours under TMDB account.");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, (PANEL_R - PANEL_X - 96) * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                cosmos.drawAura(c, tmp, r, f, auraT);
                white.setShader(new android.graphics.LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, 0xFFE0142C, 0xFF6E0010, android.graphics.Shader.TileMode.CLAMP));
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String versionName(Context c) {
            try {
                return "Clean Your TV Master " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Clean Your TV Master";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && screen == REMOTE_KEYS && keysScroll > 0) keysScroll--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && screen == REMOTE_KEYS && keysScroll + rowCount() < RemoteKeys.buttons(getContext()).size()) keysScroll++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == MAIN && focus == 0) {
                        // "google" only where Google TV's screensaver is installed and on.
                        String[] styles = Prefs.googleScreensaver() ? STYLES : java.util.Arrays.copyOf(STYLES, STYLES.length - 1);
                        int at = java.util.Arrays.asList(styles).indexOf(Prefs.screensaverStyle());
                        int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                        Prefs.setScreensaverStyle(styles[(at + dir + styles.length) % styles.length]);
                        invalidate();
                        spoken.focusMoved();
                    } else if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == MASTER)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (screen != SHELF_EDIT) return false;
                    if (!down && !shelfRows.isEmpty()) customizeTile(shelfRows.get(pickScroll + focus));
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && pickFor == PICK_CITY) {
                                focus = CITY_ROW;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1 - keysScroll;
                                screen = REMOTE_KEYS;
                            } else {
                                focus = screen == HOME_SETTINGS ? 1 : screen == MASTER ? 8 : screen == SHELF_EDIT ? 2 : screen == REMOTE_KEYS ? 3 : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "pt", "en", "es", "de", "ru", "fr", "tr", "he"}, UNITS = {"auto", "C", "F"};
        private static final String[] CLOCKS = {"auto", "24h", "12h"}, STYLES = {"photos", "aurora", "google"};
        /** Home Settings: language, temperature, theme, clock, weather, city, quotes, then one row per coin. */
        private static final int CLOCK_ROW = 2, WEATHER_ROW = 3, CITY_ROW = 4, QUOTES_ROW = 5, COIN_ROWS = 6;

        private static String styleName(String style) {
            return style.equals("aurora") ? Ui.tr("Relógio e aurora", "Clock and aurora")
                    : style.equals("google") ? Ui.tr("Fotos do Google", "Google Photos") : Ui.tr("Destaques", "Picks");
        }

        private boolean dollar() {
            return "BRL".equals(Prefs.currency());
        }

        private String[] settingLabels() {
            if (screen == MASTER) return masterLabels();
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Formato do relógio", "Clock format"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add(Ui.tr("Cidade", "City"));
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            if (screen == MASTER) return masterValues();
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español"
                    : lang.equals("de") ? "Deutsch" : lang.equals("ru") ? "Русский" : lang.equals("fr") ? "Français" : lang.equals("tr") ? "Türkçe" : lang.equals("he") ? "עברית"
                    : Ui.tr("Automático", "Automatic");
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String clock = Prefs.clockFormat();
            v[CLOCK_ROW] = clock.equals("24h") ? Ui.tr("24 horas", "24-hour") : clock.equals("12h") ? Ui.tr("12 horas", "12-hour")
                    : Ui.tr("Automático", "Automatic");
            v[WEATHER_ROW] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        /** What an action reads as in the list: an app's name, an input's, or one of the special ones. */
        private String describe(String action) {
            int at = appPkgs.indexOf(action);
            if (at >= 0) return appLabels.get(at);
            return action.startsWith("input:") ? "HDMI" : Ui.tr("Não instalado", "Not installed");
        }

        /** Everything a remote button can do, after "the TV's own": special actions, the HDMI inputs, then the apps. */
        private void loadApps() {
            Context c = getContext();
            appPkgs.clear();
            appLabels.clear();
            String[][] special = {
                {"", Ui.tr("Padrão da TV", "The TV's own")},
                {"home", Ui.tr("Início", "Home screen")},
                {"tmdb:explore", Ui.tr("Explorar filmes e séries", "Explore movies and shows")},
                {"tmdb:watchlist", Ui.tr("Minha lista (TMDB)", "My watchlist (TMDB)")},
                {"live", Ui.tr("TV ao vivo", "Live TV")},
                {RemoteKeys.NEXT_INPUT, Ui.tr("Próxima entrada HDMI", "Next HDMI input")},
                {"settings", Ui.tr("Configurações da TV", "TV settings")},
            };
            for (String[] sp : special) {
                appPkgs.add(sp[0]);
                appLabels.add(sp[1]);
            }
            for (HomeApps.Item in : HomeApps.allInputs(c)) {
                if (in.inputId == null) continue;
                appPkgs.add("input:" + in.inputId);
                appLabels.add("HDMI " + in.number + (in.label.equals("HDMI " + in.number) ? "" : " · " + in.label));
            }
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(c)) {
                if (!app[0].equals(c.getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == MASTER) {
                adjustMaster(dir);
                return;
            }
            if (screen == REMOTE_KEYS) {
                RemoteKeys.Button b = RemoteKeys.buttons(getContext()).get(keysScroll + focus);
                int n = appPkgs.size();
                int at = appPkgs.indexOf(RemoteKeys.action(b));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setButton(b.id, appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case CLOCK_ROW: {
                    int at = java.util.Arrays.asList(CLOCKS).indexOf(Prefs.clockFormat());
                    Prefs.setClockFormat(CLOCKS[(at + dir + CLOCKS.length) % CLOCKS.length]);
                    break;
                }
                case WEATHER_ROW: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case QUOTES_ROW: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Procurando…", "Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome da cidade", "Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Cidade", "City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar", "Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNeutralButton(Ui.tr("Usar minha localização", "Use my location"), (d, which) -> locateCity())
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        /** The city from the connection's location (the TV has no GPS), in one step. */
        private void locateCity() {
            cityStatus = Ui.tr("Procurando…", "Searching…");
            invalidate();
            new Thread(() -> {
                String message;
                try {
                    String[] at = HomeData.locateByIp();
                    Prefs.setCity(at[0], at[1], at[2]);
                    message = "";
                } catch (Exception e) {
                    message = Ui.tr("Não deu para achar a localização: ", "Couldn't find the location: ") + e.getMessage();
                    AppLog.w("locate city: " + e.getMessage());
                }
                String m = message;
                handler.post(() -> {
                    cityStatus = m;
                    HomeData.get(getContext()).settingsChanged();
                    invalidate();
                    if (!m.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private static final String[] LANGUAGE_NAMES = {"Automático (idioma da TV)", "Português", "English", "Español", "Deutsch", "Русский", "Français", "Türkçe", "עברית"};

        /** The language as a list to pick from, instead of stepping through it with the arrows. */
        private void chooseLanguage() {
            int at = Math.max(0, java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language()));
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Idioma", "Language"))
                    .setSingleChoiceItems(LANGUAGE_NAMES, at, (d, which) -> {
                        d.dismiss();
                        Prefs.setLanguage(LANGUAGES[which]);
                        HomeData.get(getContext()).settingsChanged();
                        spoken.focusMoved();
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .show();
        }

        /** The TMDB key (themoviedb.org > Settings > API, free): the picks and Explore need it, the QR sign-in too. */
        private void askTmdbKey() {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Chave da API ou token de leitura", "API key or read access token"));
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Chave do TMDB", "TMDB key"))
                    .setMessage(Ui.tr("Grátis em themoviedb.org › Configurações › API.", "Free at themoviedb.org › Settings › API."))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Salvar", "Save"), (d, which) -> saveTmdbKey(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                saveTmdbKey(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void saveTmdbKey(String key) {
            key = key.trim();
            if (!TvConfig.validTmdbKey(key)) {
                Toast.makeText(activity, Ui.tr("Essa chave não parece do TMDB.", "That doesn't look like a TMDB key."), Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.setTmdbKey(key);
            hasKey = true;
            HomeData.get(getContext()).settingsChanged();
            invalidate();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Procurando…", "Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora: ", "Couldn't search now: ") + e.getMessage();
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        /** Menu on an app in the shelf editor: its logo (a PNG's URL, or empty for the app's icon) and background color. */
        private void customizeTile(String[] app) {
            CustomTiles.edit(activity, app[0], app[1], null);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Apps do dock", "Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0xCCFFFFFF, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off"), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK fixa ou tira do dock (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(RemoteKeys.action(RemoteKeys.buttons(getContext()).get(which - 1)));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            String title = pickFor == PICK_CITY ? Ui.tr("Cidade", "City") : RemoteKeys.buttons(getContext()).get(pickFor - 1).name;
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            if (screen == REMOTE_KEYS) {
                drawKeys(c);
                return;
            }
            String[] labels = settingLabels();
            String[] values = settingValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(screen == MASTER ? Ui.tr("Abas e reprodução", "Tabs and Stremio") : Ui.tr("Aparência e clima", "Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = screen == HOME_SETTINGS && i >= COIN_ROWS && !Prefs.quotes();
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x80FFFFFF : 0xE6FFFFFF, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x99FFFFFF, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
        }

        /** The remote's configurable buttons, nine at a time, each with what it does now. */
        private void drawKeys(Canvas c) {
            List<RemoteKeys.Button> keys = RemoteKeys.buttons(getContext());
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            String remote = RemoteKeys.remoteName(getContext());
            c.drawText(TextUtils.ellipsize(Ui.tr("Atalhos do controle", "Remote Buttons") + (remote.isEmpty() ? "" : "  ·  " + remote),
                    new TextPaint(heading), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                RemoteKeys.Button b = keys.get(keysScroll + i);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                String value = TextUtils.ellipsize(describe(RemoteKeys.action(b)), new TextPaint(rowValue), 380 * k, TextUtils.TruncateAt.END).toString();
                // The button's name never runs into what it opens.
                float room = rowRect.width() - 90 * k - rowValue.measureText(value);
                c.drawText(TextUtils.ellipsize(b.name, new TextPaint(rowLabel), Math.max(80 * k, room), TextUtils.TruncateAt.END).toString(),
                        rowRect.left + 30 * k, base, rowLabel);
                c.drawText(value, rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            String line = keys.isEmpty()
                    ? Ui.tr("Nenhum botão configurável neste controle.",
                            "No configurable buttons: push your model's remote.json (launcher-ui/config.sh --remote).")
                    : (keysScroll + focus + 1) + " / " + keys.size() + "  ·  "
                            + Ui.tr("OK escolhe o que o botão abre.",
                            "OK picks what the button does. The rest work as always.");
            c.drawText(line, LIST_X * k + 30 * k, hy, hint);
        }

        private void activate() {
            Context c = getContext();
            if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setButton(RemoteKeys.buttons(c).get(pickFor - 1).id, appPkgs.get(at));
                focus = pickFor - 1 - keysScroll;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(keysScroll + focus + 1);
            } else if (screen == MASTER && focus == GREET_ROW) {
                askGreeting();
                return;
            } else if (screen == MASTER && focus == GREET_ROW + 3) {
                pickPcScreen();
                return;
            } else if (screen == MASTER && focus == GREET_ROW + 4) {
                pickBlockedGenres();
                return;
            } else if (screen == MASTER && focus == GREET_ROW + 1) {
                if (Games.configured()) {
                    new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(Ui.tr("Desconectar o computador?", "Disconnect the computer?"))
                            .setMessage(Ui.tr("A aba Jogos e o dock de jogos somem até você conectar de novo.", "The Games tab and dock go away until you connect again."))
                            .setPositiveButton(Ui.tr("Desconectar", "Disconnect"), (d, which) -> {
                                Games.forget();
                                HomeData.get(getContext()).settingsChanged();
                                invalidate();
                            })
                            .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                            .show();
                } else {
                    Games.pair(activity, this::invalidate);
                }
                return;
            } else if (screen == MASTER) {
                adjust(1);
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS && focus == 0) {
                chooseLanguage();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == 0 && Prefs.screensaverStyle().equals("google")) {
                // Google's screensaver: OK opens its own settings, where the Google Photos album is picked.
                try {
                    getContext().startActivity(new android.content.Intent("dreamx.two.panel.SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ex) {
                    AppLog.w("Google screensaver settings: " + ex.getMessage());
                }
                return;
            } else if (focus == 0) {
                pause();
                activity.showPreview();
            } else if (focus == 1) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == 2) {
                openShelfEditor();
            } else if (focus == 3) {
                loadApps();
                askKeysService();
                screen = REMOTE_KEYS;
                focus = 0;
                keysScroll = 0;
            } else if (focus == 4) {
                activity.startActivity(new Intent(c, ServicesActivity.class));
            } else if (focus == 5) {
                // A release APK comes without a key: it is typed here once (or pushed in config.json "tmdb_key").
                if (Tmdb.hasKey()) activity.startActivity(new Intent(c, TmdbLoginActivity.class)); else askTmdbKey();
            } else if (focus == 6) {
                linkStremio();
                return;
            } else if (focus == 7) {
                chooseHomeCategory();
                return;
            } else if (focus == 8) {
                screen = MASTER;
                focus = 0;
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }

    /** The Stremio login link's API with time limits: a stuck network no longer holds the login screen forever. */
    static java.io.InputStream stremioLink(String url) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        return c.getInputStream();
    }
}
