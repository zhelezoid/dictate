package app.dictate;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/** Специальные возможности — для диктовки с боковой и плавающей кнопки (VoiceService):
 *  (1) плашка состояния поверх всего, которая НЕ забирает фокус у поля ввода; (2) вставка текста в поле с курсором;
 *  (3) плавающая кнопка 🗣 (13.10.2026) — синхронный перевод голоса (14.10, пользователь): держишь — запись, тап — язык EN/VI/TR/FR, повёл — двигается, к нижнему краю — убрать.
 *  Слушаем только «окна изменились» — чтобы прятать кнопку, когда открыта клавиатура. Содержимое экрана никуда не пишем. */
public class DictateA11y extends AccessibilityService {
    /** Живой экземпляр, пока пользователь держит службу включённой; null — специальные возможности выключены */
    static volatile DictateA11y instance;

    private TextView plaque;
    private Runnable onPlaqueTap;

    /** Погас экран — удобный момент для тихого обновления (Updater.autoInstall): телефоном сейчас не пользуются */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) { Updater.autoInstall(c); }
    };
    private boolean screenOffRegistered;

    @Override public void onCreate() { super.onCreate(); Crash.install(this); }

    @Override protected void onServiceConnected() {
        instance = this; Log.i("a11y connected"); updateFloat();
        // Без падения: служба живёт в одном процессе с клавиатурой — её смерть уносит и клавиатуру
        if (!screenOffRegistered) try {
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, android.content.Context.RECEIVER_EXPORTED);
            else registerReceiver(screenOff, f);
            screenOffRegistered = true;
        } catch (Exception e) { Log.e("a11y screen-off: " + e.getClass().getSimpleName()); }
    }
    private void unregisterScreenOff() { if (screenOffRegistered) { screenOffRegistered = false; try { unregisterReceiver(screenOff); } catch (Exception ignored) {} } }
    @Override public boolean onUnbind(android.content.Intent i) { unregisterScreenOff(); Updater.notifyOnce(this, new Store(this)); hidePlaque(); removeFloat(); instance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { unregisterScreenOff(); hidePlaque(); removeFloat(); instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED || e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) updateFloat();
    }
    @Override public void onInterrupt() {}

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Плашка внизу экрана над панелью навигации; тап — onTap (стоп записи). Только с главного потока */
    void showPlaque(String text, int color, Runnable onTap) {
        onPlaqueTap = onTap;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (plaque == null) {
            plaque = new TextView(this);
            plaque.setTextColor(0xFFFFFFFF);
            plaque.setTextSize(16);
            plaque.setPadding(dp(20), dp(12), dp(20), dp(12));
            // В разговоре на плашке — сам перевод: не шире экрана, до 8 строк
            plaque.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(32));
            plaque.setMaxLines(8);
            plaque.setEllipsize(android.text.TextUtils.TruncateAt.END);
            plaque.setOnClickListener(v -> { if (onPlaqueTap != null) onPlaqueTap.run(); });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // Не фокусируемая: иначе поле ввода под ней потеряет фокус и вставлять будет некуда
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(110);
            try { wm.addView(plaque, lp); } catch (Exception e) { Log.e("plaque: " + e); plaque = null; return; }
        }
        plaque.setText(text);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(24));
        plaque.setBackground(g);
    }

    void hidePlaque() {
        if (plaque == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(plaque); } catch (Exception ignored) {}
        plaque = null;
        onPlaqueTap = null;
    }

    // MARK: плавающая кнопка

    /** Палец на плавающей кнопке: VoiceService сверяется с ним — запись не переживёт отпускание, даже если HOLD_END потерялся */
    static volatile boolean fingerDown;
    /** Удержание превратилось в перетаскивание — HOLD_START, пришедший позже, запись не начинает */
    static volatile boolean holdCancelled;
    private Ui.Key floatKey;
    private WindowManager.LayoutParams floatLp;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    static boolean floatEnabled(android.content.Context c) { return new Store(c).prefs().getBoolean("floatOn", true); }
    static void setFloatEnabled(android.content.Context c, boolean on) {
        new Store(c).prefs().edit().putBoolean("floatOn", on).apply();
        DictateA11y a = instance;
        if (a != null) a.h.post(a::updateFloat);
    }

    /** Показать/спрятать: включена в настройках и не открыта клавиатура (там своя кнопка) */
    void updateFloat() {
        boolean ime = false;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows())
                if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) { ime = true; break; }
        } catch (Throwable ignored) { }
        // На экране блокировки кнопку не показываем: диктовка и перевод на заблокированном телефоне — чужому в руки (аудит 13.10)
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        boolean locked = km != null && km.isKeyguardLocked();
        boolean want = floatEnabled(this) && !ime && !locked;
        if (!want) { if (floatKey != null && !fingerDown) floatKey.setVisibility(android.view.View.GONE); return; }
        if (floatKey == null) addFloat();
        if (floatKey != null) { floatKey.setVisibility(android.view.View.VISIBLE); paintFloat(); }
    }

    private void paintFloat() {
        // Кнопка — голосовой переводчик: язык собеседника EN / VI / TR (общий с экраном «Переводчик»)
        String t = new Store(this).talkTarget();
        Ui.P p = new Ui.P(this);
        floatKey.setText("🗣\n" + t.toUpperCase());
        floatKey.setColors(fingerDown && holding ? Ui.RED : Ui.targetColor(t, p), p);
    }

    private boolean holding, dragging;
    private float downX, downY;
    private int startX, startY;

    private final Runnable holdStart = () -> {
        if (!fingerDown || dragging) return;
        holding = true;
        paintFloat();
        // Через невидимое окно-помощник: только из окна Android разрешает запустить запись с микрофона
        startActivity(new android.content.Intent().setClassName(this, getPackageName() + ".FloatHold")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION));
    };

    private void addFloat() {
        Ui.P p = new Ui.P(this);
        floatKey = new Ui.Key(this, "🎙", Ui.YELLOW, p, 34);
        floatKey.setTextSize(13);
        int size = dp(68);
        floatLp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        floatLp.gravity = Gravity.TOP | Gravity.START;
        android.content.SharedPreferences pr = new Store(this).prefs();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        // В пределах экрана: место запоминалось в другой ориентации — кнопка не должна уехать за край
        floatLp.x = Math.max(0, Math.min(pr.getInt("floatX", dm.widthPixels - size - dp(12)), dm.widthPixels - size));
        floatLp.y = Math.max(0, Math.min(pr.getInt("floatY", dm.heightPixels / 2), dm.heightPixels - size));
        final int slop = dp(10);
        floatKey.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    fingerDown = true; holdCancelled = false; holding = false; dragging = false;
                    downX = e.getRawX(); downY = e.getRawY(); startX = floatLp.x; startY = floatLp.y;
                    v.setPressed(true);
                    h.postDelayed(holdStart, 160);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) {
                        dragging = true;
                        holdCancelled = true;
                        h.removeCallbacks(holdStart);
                        // Повёл пальцем — это перетаскивание: начатая запись отменяется
                        if (holding) { holding = false; sendVoice(VoiceService.HOLD_CANCEL); }
                        v.setPressed(false);
                        paintFloat();
                    }
                    if (dragging) {
                        floatLp.x = startX + (int) (e.getRawX() - downX);
                        floatLp.y = startY + (int) (e.getRawY() - downY);
                        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).updateViewLayout(floatKey, floatLp); } catch (Exception ignored) { }
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    fingerDown = false;
                    h.removeCallbacks(holdStart);
                    v.setPressed(false);
                    if (dragging) {
                        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                        if (e.getRawY() > m.heightPixels - dp(90)) {
                            // Утащил к нижнему краю — убрать; вернуть: настройки или боковая кнопка
                            setFloatEnabled(this, false);
                            floatLp.x = startX; floatLp.y = startY;
                            showPlaque("Плавающая кнопка убрана · вернуть — в настройках Dictate или боковой кнопкой", 0xE0303034, this::hidePlaque);
                            h.postDelayed(this::hidePlaque, 3000);
                        } else new Store(this).prefs().edit().putInt("floatX", floatLp.x).putInt("floatY", floatLp.y).apply();
                    } else if (holding) {
                        sendVoice(VoiceService.HOLD_END);
                    } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        // Короткий тап — другой язык собеседника
                        Store st = new Store(this);
                        st.setTalkTarget(Store.nextTalkTarget(st.talkTarget()));
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    holding = false; dragging = false;
                    paintFloat();
                    return true;
            }
            return false;
        });
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(floatKey, floatLp); }
        catch (Exception ex) { Log.e("float: " + ex); floatKey = null; }
    }

    private void sendVoice(String action) {
        if (!VoiceService.alive) return; // ещё не поднялась — сама сверится с fingerDown / holdCancelled
        try { startService(new android.content.Intent(this, VoiceService.class).setAction(action)); }
        catch (Exception ex) { Log.e("float send: " + ex); } // служба ещё не поднялась — она сама сверится с fingerDown
    }

    private void removeFloat() {
        if (floatKey == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(floatKey); } catch (Exception ignored) { }
        floatKey = null;
    }

    /** Есть ли поле ввода с курсором. null — не понять: сверху ещё наше окно-помощник (AssistActivity) или окна нет */
    Boolean hasInputField() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && !getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            return node != null && node.isEditable();
        }
        // Сверху наше окно-помощник (боковая кнопка его открывает на миг) — смотрим верхнее окно приложения под ним (14.10:
        // раньше ответ был «не понять», и без поля вместо переводчика шла диктовка в буфер)
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || getPackageName().contentEquals(r.getPackageName() == null ? "" : r.getPackageName())) continue;
                AccessibilityNodeInfo node = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                return node != null && node.isEditable();
            }
        } catch (Throwable t) { Log.e("hasInputField: " + t.getClass().getSimpleName()); }
        return null;
    }

    /** Вставить в поле с курсором активного окна — вставкой из буфера (как ⌘V на Mac: в позицию курсора).
     *  ACTION_SET_TEXT как запасной путь НЕ используем: он переписывает поле целиком, а getText() у чужого поля бывает
     *  усечённым, маской пароля или подсказкой — испортили бы чужой текст (ревью 13.10). false — текст остаётся в буфере */
    boolean insert(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) return false;
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }
}
