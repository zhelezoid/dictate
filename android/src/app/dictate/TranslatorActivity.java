package app.dictate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Экран «Переводчик» (13.10.2026, пользователь): показать телефон собеседнику. Держишь большую кнопку — говоришь по-русски —
 *  крупно перевод (EN/VI/TR/FR), под ним мелко твой русский, и вслух. Собеседник жмёт ту же кнопку и говорит на своём —
 *  крупно русский. Сверху последняя фраза (только для чтения: 17.10.2026 курсор в ней открывал клавиатуру, и та закрывала кнопку), ниже лента. 🔊/🔇 — озвучка, выбор запоминается.
 *  С боковой кнопки без поля ввода открывается сам и сразу слушает: стоп — 3 с тишины или тап по кнопке. */
public class TranslatorActivity extends Activity {
    static final String LISTEN = "listen";
    private static final int SILENCE_MS = VoiceService.TALK_SILENCE_MS;

    private final Recorder recorder = new Recorder();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private Ui.P p;
    private TextView status, flag, original, en, vi, tr, fr;
    private View divider;
    private TextView big;
    private ScrollView sv;
    private LinearLayout feed;
    private Ui.Key mic, sound;
    private boolean recording, processing, handsFree, lastPartner;
    private TextToSpeech tts;
    private volatile int ttsState;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        store = new Store(this);
        p = new Ui.P(this);
        getWindow().getDecorView().setBackgroundColor(p.bg);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // разговор — экран не гаснет
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(p.dark ? 0 : light, light);
        }
        tts = new TextToSpeech(this, s -> ttsState = s == TextToSpeech.SUCCESS ? 1 : -1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets s = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(16), dp(12) + s.top, dp(16), dp(16) + s.bottom);
            return insets;
        });

        // Шапка: заголовок и озвучка в одну строку, языки — отдельной строкой во всю ширину
        // (17.10.2026: заголовок рядом с четырьмя кнопками не влезал — «Переводчи / к»)
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.label(this, "Переводчик", 28, p.text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sound = new Ui.Key(this, "", p.keyFace, p, 24);
        sound.setTextSize(18);
        sound.setOnClickListener(v -> { store.prefs().edit().putBoolean("trSound", !soundOn()).apply(); if (!soundOn()) tts.stop(); paintTop(); });
        top.addView(sound, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(top);

        // Язык собеседника — сегменты в одной «таблетке»
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Ui.card(this, p, 26));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        en = segment("🇬🇧 EN", "en");
        vi = segment("🇻🇳 VI", "vi");
        tr = segment("🇹🇷 TR", "tr");
        fr = segment("🇫🇷 FR", "fr");
        seg.addView(en); seg.addView(vi); seg.addView(tr); seg.addView(fr);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        sl.setMargins(0, dp(12), 0, 0);
        root.addView(seg, sl);

        // Последняя фраза — крупно, на всю ширину карточки
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, p));
        card.setPadding(dp(20), dp(16), dp(20), dp(20));
        flag = Ui.label(this, "", 13, Ui.INK, false);
        flag.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        flag.setPadding(dp(10), dp(4), dp(10), dp(4));
        flag.setVisibility(View.GONE);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fl.setMargins(0, 0, 0, dp(6));
        card.addView(flag, fl);
        big = new TextView(this);
        big.setTextSize(30);
        big.setTextColor(p.text);
        big.setHintTextColor(p.text2);
        big.setHint("Здесь появится перевод");
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        big.setBackground(null);
        big.setPadding(0, dp(6), 0, dp(6));
        card.addView(big);
        divider = new View(this);
        divider.setBackgroundColor(p.line);
        divider.setVisibility(View.GONE);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dl.setMargins(0, dp(10), 0, dp(10));
        card.addView(divider, dl);
        original = Ui.label(this, "", 16, p.text2, false);
        original.setVisibility(View.GONE);
        card.addView(original);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.setMargins(0, dp(14), 0, dp(10));
        // Карточка и лента прокручиваются вместе: длинная фраза не выдавливает кнопку за экран
        sv = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(card, cl);
        feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        column.addView(feed);
        sv.addView(column);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = Ui.label(this, "", 13, p.text2, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(status);

        mic = new Ui.Key(this, "", Ui.YELLOW, p, 36);
        mic.setTextSize(22);
        mic.setOnTouchListener(this::onMicTouch);
        root.addView(mic, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)));

        setContentView(root);
        paintTop();
        paintMic();
        if (wantsListen(getIntent())) { setIntent(new Intent()); main.postDelayed(this::listenHandsFree, 150); } // пересоздание окна не начнёт запись снова
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (wantsListen(i) && !recording && !processing) listenHandsFree();
    }

    /** «Сразу слушай» — только через закрытый вход TranslatorListen (exported=false): сама Activity открыта для значка
     *  на рабочем столе, и extra от чужого приложения микрофон не включает */
    private boolean wantsListen(Intent i) {
        return i.getBooleanExtra(LISTEN, false) && i.getComponent() != null
                && i.getComponent().getClassName().endsWith(".TranslatorListen");
    }

    private TextView segment(String s, String lang) {
        TextView t = Ui.label(this, s, 16, p.text, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); setTarget(lang); });
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return t;
    }

    private void paintSegment(TextView t, boolean on, int color) {
        t.setBackground(on ? Ui.round(color, dp(22)) : null);
        t.setTextColor(on ? p.onColor(color) : p.text2);
    }

    /** Плашка языка над крупной фразой: цвет языка, у собеседника (русский) — нейтральная */
    private void paintFlag(String lang) {
        int c = lang.equals("ru") ? p.line : Ui.targetColor(lang, p);
        flag.setBackground(Ui.round(c, dp(12)));
        flag.setTextColor(lang.equals("ru") ? p.text : p.onColor(c));
        flag.setVisibility(View.VISIBLE);
    }

    /** Язык собеседника — свой у переводчика, режим клавиатуры не трогаем */
    private String target() { return store.talkTarget(); }
    private void setTarget(String t) { store.setTalkTarget(t); paintTop(); }
    private boolean soundOn() { return store.talkSound(); }

    private void paintTop() {
        String t = target();
        paintSegment(en, t.equals("en"), Ui.YELLOW);
        paintSegment(vi, t.equals("vi"), Ui.ORANGE);
        paintSegment(tr, t.equals("tr"), Ui.TEAL);
        paintSegment(fr, t.equals("fr"), Ui.BLUE);
        sound.setText(soundOn() ? "🔊" : "🔇");
        if (!recording && !processing) status.setText("Ты — по-русски, собеседник — на своём: язык узнаю сама");
    }

    private void paintMic() {
        mic.setText(recording ? (handsFree ? "🔴 Слушаю… · тап — стоп" : "🔴 Говори… отпусти — переведу") : processing ? "⏳ Перевожу…" : "🎙  Держи и говори");
        mic.setColors(recording ? Ui.RED : processing ? p.keyFace : Ui.YELLOW, p);
    }

    private boolean onMicTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                if (recording && handsFree) return true; // стоп — по отпусканию
                if (processing) return true;
                start(false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                if (recording) finishRecording();
                return true;
        }
        return false;
    }

    private void listenHandsFree() { if (!recording && !processing) start(true); }

    private void start(boolean free) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        tts.stop(); // ещё читает прошлый перевод — замолчать, слушать
        recorder.openStream(store);
        if (!recorder.start()) { status.setText("⚠️ Микрофон недоступен"); return; }
        recording = true;
        handsFree = free;
        paintMic();
        status.setText(free ? "Говори — замолчишь на 3 с, переведу" : "Говори — отпустишь, переведу");
        main.postDelayed(timeout, 180_000);
        if (free) main.postDelayed(vadTick, 200);
    }

    private final Runnable timeout = () -> { if (recording) finishRecording(); };

    private final Runnable vadTick = new Runnable() {
        @Override public void run() {
            if (!recording || !handsFree) return;
            if (!recorder.heardVoice() && recorder.silenceMs() > 10_000) { cancelRecording("Никто не говорит — нажми кнопку, когда будет нужно"); return; }
            if (recorder.heardVoice() && recorder.silenceMs() > SILENCE_MS) { finishRecording(); return; }
            main.postDelayed(this, 200);
        }
    };

    private void cancelRecording(String msg) {
        main.removeCallbacks(vadTick);
        main.removeCallbacks(timeout);
        recording = false;
        handsFree = false;
        recorder.stop();
        recorder.dropStream();
        paintMic();
        status.setText(msg);
    }

    private void finishRecording() {
        main.removeCallbacks(vadTick);
        main.removeCallbacks(timeout);
        recording = false;
        handsFree = false;
        byte[] pcm = recorder.stop();
        final Stream st = recorder.takeStream();
        if (Recorder.seconds(pcm) < Pipeline.MIN_SECONDS) { if (st != null) st.cancel(); paintMic(); paintTop(); return; }
        processing = true;
        paintMic();
        status.setText("⏳ Перевожу…");
        final String t = target();
        work.execute(() -> {
            Pipeline.Result r = Pipeline.run(store, pcm, t, false, "translator", true, st);
            // Нет сети — фразу не теряем: распознается позже и придёт уведомлением (как у клавиатуры)
            if (r.sttFailed && r.netError) { Pending.save(this, pcm, t); r.error = "⏳ Нет сети — фраза сохранена, распознаю позже и пришлю уведомление"; }
            main.post(() -> show(r));
        });
    }

    private void show(Pipeline.Result r) {
        if (isDestroyed()) return;
        processing = false;
        paintMic();
        if (r.result == null) {
            if (r.fallback != null) Pipeline.toClipboard(this, r.fallback); // перевод не удался — исходный текст не теряем
            status.setText(r.error != null ? r.error : "🤷 Ничего не распознано");
            return;
        }
        // Прошлая «последняя» фраза уезжает в ленту
        if (big.getText().length() > 0) addToFeed(flag.getText().toString(), big.getText().toString(), original.getText().toString(), lastPartner);
        String lang = r.lang == null ? "en" : r.lang;
        boolean partner = lang.equals("ru");
        lastPartner = partner;
        flag.setText(partner ? "🇷🇺 Собеседник сказал" : Store.langName(lang));
        paintFlag(lang);
        big.setText(r.result);
        big.setTextSize(r.result.length() > 220 ? 22 : r.result.length() > 120 ? 26 : 30);
        sv.scrollTo(0, 0);
        String orig = r.fallback == null ? "" : r.fallback;
        original.setText(orig);
        original.setVisibility(orig.isEmpty() ? View.GONE : View.VISIBLE);
        divider.setVisibility(orig.isEmpty() ? View.GONE : View.VISIBLE);
        status.setText(String.format(Locale.US, "✓ %.1f с · держи кнопку для следующей фразы", r.secs));
        if (soundOn()) speak(r.result, lang, 0);
    }

    /** Лента как переписка: свои фразы (перевод с русского) — справа, собеседника — слева другим фоном */
    private void addToFeed(String head, String text, String orig, boolean partner) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        if (partner) row.setBackground(Ui.round(p.line, dp(18)));
        else row.setBackground(Ui.card(this, p, 18));
        row.setPadding(dp(14), dp(10), dp(14), dp(12));
        row.addView(Ui.label(this, head, 12, p.text2, false));
        TextView body = Ui.label(this, text, 18, p.text, false);
        body.setPadding(0, dp(2), 0, 0);
        row.addView(body);
        if (!orig.isEmpty()) {
            TextView o = Ui.label(this, orig, 14, p.text2, false);
            o.setPadding(0, dp(4), 0, 0);
            row.addView(o);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(partner ? 0 : dp(28), 0, partner ? dp(28) : 0, dp(8));
        feed.addView(row, 0, lp);
    }

    private void speak(String text, String lang, int attempt) {
        if (ttsState == 0 && attempt < 10) { main.postDelayed(() -> speak(text, lang, attempt + 1), 300); return; }
        Locale loc = Store.locale(lang);
        if (ttsState != 1 || tts.setLanguage(loc) < 0) { status.setText("🔇 Нет голоса для " + lang + " — Настройки → Синтез речи"); return; }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tr");
    }

    @Override protected void onPause() {
        super.onPause();
        if (recording) cancelRecording("Запись остановлена");
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (recording) { recorder.stop(); recorder.dropStream(); }
        if (tts != null) tts.shutdown();
        work.shutdown();
        super.onDestroy();
    }
}
