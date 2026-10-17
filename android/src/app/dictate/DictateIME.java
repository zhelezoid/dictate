package app.dictate;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Голосовая клавиатура: держишь кнопку — говоришь — отпускаешь — текст в поле.
 * EN (по умолчанию): говоришь по-русски, вставляется английский перевод. RU: русский как сказано.
 * Конвейер как на Mac: STT → чистка → [EN: замены → перевод] / [RU: «делаю→делай» → замены] → вставка с пробелом.
 * 13.10.2026: панели «123» и «Последние»; запись переживает поворот экрана; запись без сети сохраняется (Pending).
 */
public class DictateIME extends InputMethodService {
    private final Recorder recorder = new Recorder();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private Ui.P p;
    private TextView status;
    private Ui.Key mic, lang, numKey, recentKey;
    private FrameLayout content;
    /** Что в средней части: микрофон, цифры или список последних */
    private enum Panel { MIC, NUM, RECENT }
    private Panel panel = Panel.MIC;
    private boolean recording, processing;
    /** Идёт запись или распознавание — тихое обновление ждёт (оно убивает процесс) */
    static volatile boolean busy;
    /** Палец «потерялся» посреди записи (поворот экрана пересоздал клавиатуру — ACTION_CANCEL): запись идёт дальше,
     *  стоп — тап по кнопке или 4 с тишины. Раньше такая запись выбрасывалась (жалоба 13.10) */
    private boolean handsFree;
    private boolean inputViewShown;
    /** Растёт на каждой записи: результат старой обработки не вставляется поверх новой */
    private int generation;

    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
        store = new Store(this);
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private Ui.Key key(String label, View.OnClickListener click) {
        Ui.Key b = new Ui.Key(this, label, p.keyFace, p, 12);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private View inputRoot;

    /** Надпись режима («RU→EN», «RU→VI») целиком в одну строку: шрифт сам уменьшается под ширину кнопки
     *  (14.10.2026: правая часть «RU→EN» обрезалась). Без setSingleLine — он сдвигал рисунок кнопки (Ui.Key) */
    private void fitLabel(android.widget.TextView t, int minSp, int maxSp) {
        t.setMaxLines(1);
        t.setPadding(dp(3), 0, dp(3), dp(4));
        t.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    }
    /** Полоска вместо полной клавиатуры: строки состояния нет, сообщения — на кнопке микрофона несколько секунд */
    private boolean compact;
    private String flash;
    private final Runnable flashOff = () -> { flash = null; refresh(); };

    @Override public View onCreateInputView() {
        p = new Ui.P(this);
        compact = store.compact();
        if (compact) return compactView();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);
        root.setPadding(dp(8), dp(6), dp(8), dp(10));

        status = Ui.label(this, "", 14, p.text2, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(4), dp(2), dp(4), dp(8));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        // Долгое нажатие на строку состояния — вставить последнюю диктовку ещё раз (как ⌃⌥V на Mac)
        status.setOnLongClickListener(v -> { reinsertLast(); return true; });
        // Тап — повторить распознавание записи, которая не дошла (сеть; только поле «без обучения» — остальные в Pending)
        status.setOnClickListener(v -> { if (failedPcm != null && !processing && !recording) process(failedPcm, failedTarget); });
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(8));
        root.addView(content, clp);

        mic = new Ui.Key(this, "", Ui.YELLOW, p, 22);
        mic.setTextSize(20);
        mic.setOnTouchListener(this::onMicTouch);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false); // по краям, а не по линии текста: у кнопки режима шрифт мельче — её опускало (14.10)
        lang = key("", v -> { store.setTarget(Store.nextTarget(store.target())); refresh(); });
        // «RU→EN» не влезало — кнопка режима шире и шрифт мельче
        lang.setLayoutParams(new LinearLayout.LayoutParams(0, dp(54), 1.5f) {{ setMargins(dp(3), 0, dp(3), 0); }});
        fitLabel(lang, 9, 14);
        row.addView(lang);
        numKey = key("123", v -> show(panel == Panel.NUM ? Panel.MIC : Panel.NUM));
        numKey.setTextSize(15);
        row.addView(numKey);
        recentKey = key("🕘", v -> show(panel == Panel.RECENT ? Panel.MIC : Panel.RECENT));
        row.addView(recentKey);
        Ui.Key del = key("⌫", null);
        del.setOnTouchListener(this::onDeleteTouch);
        row.addView(del);
        row.addView(key("⏎", v -> enter()));
        Ui.Key globe = key("🌐", v -> nextKeyboard());
        globe.setOnLongClickListener(v -> { picker(); return true; });
        row.addView(globe);
        Ui.Key small = key("⌄", v -> setLayout(true));
        row.addView(small);
        root.addView(row);

        padForNavBar(root, dp(10));
        inputRoot = root;

        show(panel);
        return root;
    }

    /** Одна строка ~44 dp: режим · держи-и-говори · ⌫ · ⏎ · 🌐 · ⤢ развернуть. «123» и «Последние» — в полной */
    private View compactView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBaselineAligned(false); // см. выше: иначе «RU→EN» с уменьшенным шрифтом ниже соседей
        root.setBackgroundColor(p.bg);
        root.setPadding(dp(5), dp(4), dp(5), dp(4));
        status = null; content = null; numKey = null; recentKey = null;
        panel = Panel.MIC;
        lang = key("", v -> { store.setTarget(Store.nextTarget(store.target())); refresh(); });
        lang.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1.6f) {{ setMargins(dp(2), 0, dp(2), 0); }});
        fitLabel(lang, 8, 13);
        root.addView(lang);
        mic = new Ui.Key(this, "", Ui.YELLOW, p, 12);
        mic.setTextSize(13);
        mic.setMaxLines(2);
        mic.setEllipsize(TextUtils.TruncateAt.END);
        mic.setPadding(dp(6), 0, dp(6), dp(4)); // снизу — под «губу» кнопки, как у Ui.Key
        mic.setOnTouchListener(this::onMicTouch);
        // Долгое нажатие на микрофон занято записью — «вставить ещё раз» в полоске по долгому тапу на режиме
        lang.setOnLongClickListener(v -> { if (reinsertLast()) setStatus("✓ Вставил ещё раз"); return true; });
        mic.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 2.9f) {{ setMargins(dp(2), 0, dp(2), 0); }});
        root.addView(mic);
        for (Ui.Key k : new Ui.Key[]{key("⌫", null), key("⏎", v -> enter()), key("🌐", v -> nextKeyboard()), key("⤢", v -> setLayout(false))}) {
            k.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f) {{ setMargins(dp(2), 0, dp(2), 0); }});
            k.setTextSize(16);
            root.addView(k);
        }
        Ui.Key del = (Ui.Key) root.getChildAt(2);
        del.setOnTouchListener(this::onDeleteTouch);
        Ui.Key globe = (Ui.Key) root.getChildAt(4);
        globe.setOnLongClickListener(v -> { picker(); return true; });
        padForNavBar(root, dp(4));
        inputRoot = root;
        refresh();
        return root;
    }

    private String waitingLabel() { int n = Pending.count(this); return n > 0 ? "🎙 Держи · ⏳" + n : "🎙  Держи"; }

    /** Свернуть в полоску / развернуть. Во время записи не перестраиваем: новая кнопка не знает про палец на старой */
    private void setLayout(boolean small) {
        if (recording || processing) return;
        store.setCompact(small);
        main.removeCallbacks(flashOff);
        flash = null;
        setInputView(onCreateInputView());
        inputRoot.requestApplyInsets();
    }

    // Android 15+ рисует окно клавиатуры под панелью навигации — нижний ряд оказывался под её кнопками.
    // Отступ снизу = высота панели. Берём её и когда панель сейчас скрыта (полноэкранное приложение сообщало 0,
    // а кнопки «назад/домой/недавние» над клавиатурой всё равно появлялись — 11.10.2026 )
    private void padForNavBar(View root, int basePad) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int types = WindowInsets.Type.navigationBars() | WindowInsets.Type.tappableElement();
            int nav = Math.max(insets.getInsets(types | WindowInsets.Type.systemGestures()).bottom,
                    insets.getInsetsIgnoringVisibility(types).bottom);
            if (nav == 0) nav = navBarHeight(); // система не сообщила ничего — высота панели из ресурсов
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), basePad + nav);
            return insets;
        });
    }

    // MARK: панели

    /** Экран заблокирован или поле пароля: прошлые диктовки не показываем (на экране блокировки с буквенным паролем
     *  клавиатура видна любому, у кого телефон в руках — аудит 13.10) */
    private boolean privateMode() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return true;
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei == null) return false;
        int v = ei.inputType & android.text.InputType.TYPE_MASK_VARIATION;
        return v == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD || v == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || v == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private void show(Panel to) {
        if (to == Panel.RECENT && privateMode()) { to = Panel.MIC; setStatus("🔒 Последние диктовки скрыты на экране блокировки и в поле пароля"); }
        panel = recording || processing ? Panel.MIC : to; // во время записи микрофон не прячем
        if (content == null) { refresh(); return; }
        content.removeAllViews();
        if (panel == Panel.MIC) content.addView(mic, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150)));
        else if (panel == Panel.NUM) content.addView(numpad());
        else content.addView(recents(), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(210)));
        numKey.setText(panel == Panel.NUM ? "🎙" : "123");
        recentKey.setText(panel == Panel.RECENT ? "🎙" : "🕘");
        refresh();
    }

    /** Цифры прямо в Dictate (Gboard на цифрах открыть нельзя: раскладку он выбирает сам по типу поля) */
    private View numpad() {
        String[][] rows = {{"1", "2", "3", "+"}, {"4", "5", "6", "−"}, {"7", "8", "9", "%"}, {",", "0", ".", "␣"}};
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (String[] r : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String s : r) {
                // «−» на кнопке для глаз, в поле — обычный дефис-минус: его понимают калькуляторы и таблицы
                String out = s.equals("−") ? "-" : s.equals("␣") ? " " : s;
                boolean digit = Character.isDigit(s.charAt(0));
                Ui.Key k = key(s, v -> type(out));
                if (!digit) k.setColors(p.surface, p);
                k.setTextSize(digit ? 22 : 20);
                line.addView(k);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(6));
            grid.addView(line, lp);
        }
        return grid;
    }

    private void type(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    /** Последние диктовки: сверху — записи, которые ждут сети; тап — вставить, долгий тап — в буфер */
    private View recents() {
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        for (Pending.Item it : Pending.list(this)) {
            String when = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(it.ts));
            View row = recentRow("⏳ " + when + " · ждёт сети", "Запись сохранена — распознаю сама, когда появится интернет. Тап — попробовать сейчас", true);
            row.setOnClickListener(v -> { Pending.schedule(this); setStatus("⏳ Пробую распознать…"); });
            list.addView(row);
        }
        List<String[]> items = store.recent(10);
        for (String[] it : items) {
            final String text = it[1];
            View row = recentRow(it[0], text, false);
            row.setOnClickListener(v -> {
                if (commit(text)) setStatus("✓ Вставлено"); else { toClipboard(text); setStatus("📋 Поля нет — текст в буфере"); }
                show(Panel.MIC);
            });
            row.setOnLongClickListener(v -> { toClipboard(text); setStatus("📋 Скопировано"); return true; });
            list.addView(row);
        }
        if (items.isEmpty() && list.getChildCount() == 0) {
            TextView empty = Ui.label(this, "Диктовок пока нет", 15, p.text2, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            list.addView(empty);
        }
        return sv;
    }

    private View recentRow(String meta, String text, boolean pending) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        android.graphics.drawable.GradientDrawable bgd = Ui.card(this, p);
        if (pending) bgd.setStroke(dp(2), Ui.YELLOW);
        row.setBackground(bgd);
        row.setClickable(true);
        row.addView(Ui.label(this, meta, 12, p.text2, false));
        TextView t = Ui.label(this, text, 15, p.text, false);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(lp);
        return row;
    }

    // MARK: жизненный цикл окна

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        // Переключился в другое поле/чат, пока шёл перевод — результат не должен уйти туда
        if (processing && !restarting) generation++;
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; } // в альбомной ориентации не закрывать поле

    @Override public void onWindowHidden() {
        main.removeCallbacks(deleteRepeat);
        super.onWindowHidden();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(deleteRepeat);
        main.removeCallbacks(recordTimeout);
        main.removeCallbacks(processingTimeout);
        main.removeCallbacks(vadTick);
        main.removeCallbacks(hiddenCheck);
        main.removeCallbacks(flashOff);
        // Клавиатуру выключили посреди записи — звук не выбрасываем: распознается, как запись без сети
        if (recording) {
            recording = false;
            byte[] pcm = recorder.stop();
            recorder.dropStream();
            if (Recorder.seconds(pcm) >= Pipeline.MIN_SECONDS && !incognito()) Pending.save(this, pcm, store.target());
        }
        work.shutdownNow();
        busy = false; // экземпляр умирает, запись уже в Pending; иначе тихое обновление ждало бы следующего показа клавиатуры
        super.onDestroy();
    }

    /** Высота панели навигации из ресурсов системы — запасной вариант, если отступы окна пришли нулевыми */
    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(48);
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputViewShown = true;
        main.removeCallbacks(hiddenCheck);
        // Окно клавиатуры переиспользуется между полями — пересчитать отступ под панель навигации при каждом показе
        if (inputRoot != null) inputRoot.requestApplyInsets();
        // Окно пересоздано (поворот): новая кнопка не нажата — пальца на ней нет, пишем дальше без него.
        // Перезапуск поля без пересоздания — старая кнопка ещё нажата, палец держит: ничего не меняем
        if (recording) { if (mic == null || !mic.isPressed()) { handsFree = true; startVad(); refresh(); } }
        else if (!processing) show(Panel.MIC);
        else refresh();
        // Обновления и данные с Mac без провода — не чаще раза в 30 мин, в фоне
        Updater.checkAsync(this, false, null);
        // Задание JobScheduler не переживает перезагрузку — записи, ждущие сети, планируем заново
        if (Pending.count(this) > 0) Pending.schedule(this);
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        main.removeCallbacks(deleteRepeat);
        inputViewShown = false;
        // Клавиатуру спрятали посреди записи. Поворот экрана делает то же самое и тут же показывает её снова —
        // поэтому не рвём сразу: через 0.7 с, если так и не показалась, — заканчиваем запись и распознаём (текст в буфер)
        if (recording) { main.removeCallbacks(hiddenCheck); main.postDelayed(hiddenCheck, 700); }
        super.onFinishInputView(finishingInput);
    }

    private final Runnable hiddenCheck = () -> {
        if (recording && !inputViewShown) { Log.i("ime: спрятали во время записи — распознаю"); release(false); }
    };

    private void refresh() {
        busy = recording || processing;
        if (lang == null) return;
        String target = store.target();
        boolean en = !target.equals("ru");
        lang.setText(Store.label(target));
        lang.setColors(Ui.targetColor(target, p), p);
        if (compact) mic.setText(recording ? (handsFree ? "🔴 Тап — стоп" : "🔴 Говори…")
                : processing ? (en ? "⏳ Перевожу…" : "⏳ Распознаю…")
                : flash != null ? flash
                : !micAllowed() ? "Нет микрофона — тап" : store.apiKey() == null ? "Нет ключа xAI"
                : waitingLabel());
        else mic.setText(recording ? (handsFree ? "🔴 Пишу дальше · тап — стоп" : "🔴 Говори… отпусти — вставлю")
                : processing ? (en ? "⏳ Перевожу…" : "⏳ Распознаю…") : "🎙  Держи и говори");
        mic.setColors(recording ? Ui.RED : processing || flash != null && compact ? p.keyFace : Ui.YELLOW, p);
        if (compact) return;
        if (!recording && !processing) {
            int waiting = Pending.count(this);
            if (!micAllowed()) setStatus("Нет доступа к микрофону — открой приложение Dictate");
            else if (store.apiKey() == null) setStatus("Нет ключа xAI — вставь его в настройках Dictate");
            else if (waiting > 0) setStatus("⏳ Ждут сети: " + waiting + " — распознаю сама, они появятся в 🕘");
            else if (panel == Panel.RECENT) setStatus("Тап — вставить · долгий тап — скопировать");
            else setStatus(target.equals("en") ? "Говори по-русски — вставлю по-английски"
                    : target.equals("vi") ? "Говори по-русски — вставлю по-вьетнамски"
                    : target.equals("tr") ? "Говори по-русски — вставлю по-турецки"
                    : target.equals("fr") ? "Говори по-русски — вставлю по-французски" : "Говори — вставлю как сказано");
        }
    }

    private void setStatus(String s) {
        if (status != null) { status.setText(s); return; }
        if (!compact || mic == null || s == null) return;
        // Полоска: сообщение на кнопке микрофона 5 с (ошибки — 8 с), потом снова «Держи»
        flash = shortFlash(s);
        main.removeCallbacks(flashOff);
        main.postDelayed(flashOff, s.startsWith("⚠") || s.startsWith("📋") ? 8000 : 5000);
        refresh();
    }

    /** Кнопка в полоске вмещает ~30 символов в две строки: главное — куда делся текст */
    private String shortFlash(String s) {
        if (s.startsWith("⏳ Нет сети")) return "⏳ Нет сети — распознаю позже (⤢ → 🕘)";
        if (s.contains("Подозрительный перевод") || s.contains("Перевод не удался")) return "⚠️ Перевод не вышел · RU в буфере";
        if (s.endsWith(" — тап здесь, повторить")) return "⚠️ Не распознал · тап — повтор";
        if (s.startsWith("📋")) return s.contains("Скопировано") ? s : "📋 Текст в буфере";
        return s.replace(" · долгое нажатие здесь — вставить ещё раз", "");
    }

    private boolean micAllowed() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    private boolean incognito() {
        EditorInfo ei = getCurrentInputEditorInfo();
        return ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
    }

    // MARK: запись

    private boolean onMicTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                if (handsFree && recording) return true; // стоп — по отпусканию этого тапа
                if (processing) return true;
                // Полоска: строки состояния нет — повтор несостоявшегося распознавания тапом по кнопке, пока висит сообщение
                if (compact && flash != null && failedPcm != null && !recording) {
                    flash = null; main.removeCallbacks(flashOff); process(failedPcm, failedTarget); return true;
                }
                if (!micAllowed()) { openSettings(); return true; }
                if (store.apiKey() == null) { setStatus("Нет ключа xAI — вставь его в настройках Dictate"); return true; }
                // Соединения с xAI — заранее, пока говоришь: стрим распознаёт во время речи, прогрев — для перевода
                recorder.openStream(store);
                if (!recorder.start()) { setStatus("⚠️ Микрофон недоступен"); return true; }
                recording = true;
                handsFree = false;
                flash = null;
                main.removeCallbacks(flashOff);
                generation++;
                main.postDelayed(recordTimeout, 180_000);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                refresh();
                return true;
            case MotionEvent.ACTION_UP:
                v.setPressed(false);
                if (!recording) return true;
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                release(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                // Палец не отпускали — окно клавиатуры пересоздали (поворот). Запись не выбрасываем: пишем дальше без пальца
                if (recording && !handsFree) { handsFree = true; startVad(); refresh(); }
                return true;
        }
        return false;
    }

    /** Без пальца — стоп после 4 с тишины (как у боковой кнопки при диктовке в поле) */
    private void startVad() { main.removeCallbacks(vadTick); main.postDelayed(vadTick, 200); }

    private final Runnable vadTick = new Runnable() {
        @Override public void run() {
            if (!recording || !handsFree) return;
            if (recorder.silenceMs() > 4000) { Log.i("ime: стоп по тишине (без пальца)"); release(false); return; }
            main.postDelayed(this, 200);
        }
    };

    private void release(boolean cancelled) {
        main.removeCallbacks(recordTimeout);
        main.removeCallbacks(vadTick);
        main.removeCallbacks(hiddenCheck);
        recording = false;
        handsFree = false;
        byte[] pcm = recorder.stop();
        Stream s = recorder.takeStream();
        if (cancelled || Recorder.seconds(pcm) < Pipeline.MIN_SECONDS) { if (s != null) s.cancel(); refresh(); return; }
        process(pcm, store.target(), s);
    }

    /** ⌫ как в обычной клавиатуре: нажал — стёр символ, держишь — стирает дальше (через 0.4 с, каждые 50 мс),
     *  после ~2 с удержания — словами */
    private final Runnable deleteRepeat = new Runnable() {
        @Override public void run() {
            if (getCurrentInputConnection() == null) return;
            deleteTicks++;
            if (deleteTicks > 32) deleteWord(); else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            main.postDelayed(this, deleteTicks > 32 ? 120 : 50);
        }
    };
    private int deleteTicks;

    private boolean onDeleteTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                deleteTicks = 0;
                main.postDelayed(deleteRepeat, 400);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                main.removeCallbacks(deleteRepeat);
                return true;
        }
        return false;
    }

    /** Стереть слово перед курсором (вместе с пробелами за ним) */
    private void deleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) return;
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
        ic.deleteSurroundingText(before.length() - i, 0);
    }

    /** Звук записи из поля «без обучения», которую не удалось распознать (сеть): на диск не пишем — тап повторяет */
    private byte[] failedPcm;
    private String failedTarget = "en";

    private final Runnable processingTimeout = () -> {
        if (!processing) return;
        Log.e("watchdog: processing > 75 s");
        processing = false;
        generation++; // опоздавший результат — только в буфер
        refresh();
        setStatus("⚠️ Нет ответа от сервера");
    };

    private final Runnable recordTimeout = () -> {
        // Отпускание кнопки потерялось — микрофон не держим вечно
        if (recording) { Log.e("watchdog: recording > 180 s"); release(false); }
    };

    private void process(byte[] pcm, String target) { process(pcm, target, null); }

    private void process(byte[] pcm, String target, Stream s) {
        processing = true;
        refresh();
        main.postDelayed(processingTimeout, 75_000);
        final int gen = generation;
        // Поле «без обучения» (инкогнито, пароли) — в историю не пишем и звук на диск не сохраняем
        final boolean incognito = incognito();
        work.execute(() -> {
            Pipeline.Result r = Pipeline.run(store, pcm, target, incognito, "ime", false, s);
            final boolean retryable = r.sttFailed;
            // В очередь — только когда дело в сети: запись, которую сервер отверг, повтор не исправит
            final boolean queued = retryable && r.netError && !incognito;
            if (queued) Pending.save(this, pcm, target);
            main.post(() -> {
                failedPcm = retryable && !queued ? pcm : null;
                failedTarget = target;
                String err = queued ? "⏳ Нет сети — запись сохранена, распознаю сама, когда появится (она будет в 🕘)"
                        : retryable ? r.error + " — тап здесь, повторить" : r.error;
                finish(gen, r.result, err, r.fallback, r.secs);
            });
        });
    }

    private void finish(int gen, String result, String error, String fallback, double secs) {
        main.removeCallbacks(processingTimeout);
        if (gen != generation && !processing) {
            // Сторож уже сбросил эту обработку или сменилось поле — ничего не вставляем
            if (result != null) { store.setLastText(result); toClipboard(result); setStatus("📋 Текст в буфере (поле сменилось)"); }
            else if (fallback != null) toClipboard(fallback);
            return;
        }
        processing = false;
        // Перевод не удался — НЕ вставляем, но текст не теряем: русский в буфер (как «Последние диктовки» на Mac)
        if (fallback != null && result == null) toClipboard(fallback);
        if (result != null) {
            store.setLastText(result);
            if (gen == generation && inputViewShown) {
                if (!commit(result)) { toClipboard(result); error = "📋 Поле потеряно — текст в буфере"; }
            } else {
                toClipboard(result);
                error = "📋 Текст в буфере — поле сменилось, пока шла обработка";
            }
        }
        refresh();
        if (error != null) setStatus(error);
        else setStatus(String.format("✓ %.1f с · долгое нажатие здесь — вставить ещё раз", secs));
    }

    /** Пробел в конце — иначе следующая диктовка прилипнет к точке */
    private boolean commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        return ic.commitText(text.endsWith(" ") || text.endsWith("\n") ? text : text + " ", 1);
    }

    private boolean reinsertLast() {
        String t = store.lastText();
        if (t.isEmpty()) { setStatus("Нет диктовок"); return false; }
        if (!commit(t)) toClipboard(t);
        return true;
    }

    private void toClipboard(String t) { Pipeline.toClipboard(this, t); }

    private void enter() {
        EditorInfo ei = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = ei == null || (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED;
        if (noAction) sendKeyChar('\n'); else ic.performEditorAction(action);
    }

    private void nextKeyboard() {
        if (!switchToPreviousInputMethod()) picker();
    }

    private void picker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    private void openSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
