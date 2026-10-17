package app.dictate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.Vibrator;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Диктовка с боковой кнопки и плавающей кнопки (13.10.2026).
 *  Боковая: долгое нажатие — запись, стоп сам после 4 с тишины (или тап по плашке / ещё одно долгое нажатие): отпускание
 *  кнопки Android приложению не сообщает. Поля ввода нет и режим перевода — открывается экран «Переводчик».
 *  Плавающая (DictateA11y): держишь — запись, отпустил — стоп (HOLD_START / HOLD_END); всегда разговор: перевод на плашке
 *  и вслух (язык — Store.talkTarget, озвучка — talkSound), поле не проверяется, автопрослушки нет — следующую реплику тоже держат.
 *  Запись — в foreground service (тип microphone): окно-помощник AssistActivity сразу закрывается, чтобы вернуть фокус полю. */
public class VoiceService extends Service {
    static final String TOGGLE = "app.dictate.TOGGLE";
    static final String HOLD_START = "app.dictate.HOLD_START", HOLD_END = "app.dictate.HOLD_END",
            HOLD_CANCEL = "app.dictate.HOLD_CANCEL";
    /** Тишина после голоса, после которой запись заканчивается сама: диктовка в поле — 4 с, ответ в разговоре — 3 с (пользователь 13.10) */
    static final int DICTATE_SILENCE_MS = 4000, TALK_SILENCE_MS = 3000;
    private static final String CHANNEL = "dictate";

    /** Служба поднята — DictateA11y шлёт HOLD_END/HOLD_CANCEL только живой службе (иначе startForeground из фона) */
    static volatile boolean alive;
    private final Recorder recorder = new Recorder();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private boolean recording, processing;
    /** Растёт на каждой обработке: результат, опоздавший после сторожа, не вставляется поверх новой */
    private int generation;
    /** Звук записи, которую не распознали из-за сети — тап по плашке повторяет (надиктованное не теряется) */
    private byte[] failedPcm;
    private String failedTarget = "en";
    private boolean failedTalk;
    /** Озвучка перевода в разговоре — встроенный синтез речи телефона (без сети, бесплатно) */
    private TextToSpeech tts;
    /** 0 — синтез ещё запускается, 1 — готов, -1 — не запустился */
    private volatile int ttsState;
    /** Разговор идёт: после озвучки перевода — сама слушаем следующую реплику (чью угодно), пока все не замолчат на 10 с */
    private boolean talkLoop;
    /** Текущая запись начата автопрослушкой (не кнопкой): стоп — по тишине, и это заведомо разговор */
    private boolean autoListening;
    private int emptyStreak;
    /** Как начата текущая запись: боковая кнопка (стоп по тишине) или плавающая (стоп — отпускание) */
    private boolean sideStart, holdStart;
    /** Перевод с плавающей кнопки: следующую реплику тоже держат кнопкой — автопрослушку после озвучки не включаем */
    private boolean noAutoListen;
    private long sideStartedAt;

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
        alive = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Диктовка", NotificationManager.IMPORTANCE_LOW));
        tts = new TextToSpeech(this, status -> ttsState = status == TextToSpeech.SUCCESS ? 1 : -1);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { main.post(() -> {
                main.removeCallbacks(hideLater);
                if (talkLoop) main.postDelayed(VoiceService.this::autoListen, 250); // хвост голоса не попадёт в запись
                else main.postDelayed(hideLater, 2500);
            }); }
            @Override public void onError(String id) { onDone(id); }
        });
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService обязывает вызвать startForeground — даже если дальше ничего не делаем
        startForeground(1, notification(recording ? "🔴 Запись" : processing ? "⏳ Обработка" : "Dictate"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        String a = intent == null ? null : intent.getAction();
        if (TOGGLE.equals(a)) toggle();
        else if (HOLD_START.equals(a)) holdStart();
        else if (HOLD_END.equals(a)) { if (recording && holdStart) stop(); else if (!recording && !processing) stopSelfSafely(); }
        else if (HOLD_CANCEL.equals(a)) { if (recording && holdStart) cancelHold(); else if (!recording && !processing) stopSelfSafely(); }
        else if (!recording && !processing) stopSelfSafely();
        return START_NOT_STICKY;
    }

    private void toggle() {
        if (processing) { plaque("⏳ Ещё обрабатываю прошлую…", 0xE0484848, null); return; }
        if (recording) { stop(); return; }
        // Плавающую кнопку убрали к краю — боковая возвращает её (обещано в подсказке)
        if (!DictateA11y.floatEnabled(this)) DictateA11y.setFloatEnabled(this, true);
        if (!begin()) return;
        sideStart = true;
        sideStartedAt = System.currentTimeMillis();
        String target = store.target();
        plaque(target.equals("ru") ? "🔴 Говори · замолчишь — закончу" : "🔴 Говори · RU→" + target.toUpperCase() + " · замолчишь — закончу", 0xE0D70015, this::stop);
        updateNotification("🔴 Запись — замолчи на 4 с или тап по плашке — стоп");
        main.postDelayed(vadTick, 200);
        if (!target.equals("ru")) checkTranslator(0);
    }

    /** Плавающая кнопка: держишь — пишет */
    private void holdStart() {
        if (processing) { plaque("⏳ Ещё обрабатываю прошлую…", 0xE0484848, null); return; }
        if (recording) return;
        // Палец уже отпустили, пока поднималась служба (короткое удержание) — не начинать
        if (!DictateA11y.fingerDown || DictateA11y.holdCancelled) { done(null, 0, 0); return; }
        if (!begin()) return;
        holdStart = true;
        main.postDelayed(fingerWatch, 100);
        // Плавающая кнопка — синхронный перевод голоса (пользователь 14.10), а не диктовка в поле: поле не проверяем
        plaque("🔴 Говори · RU ⇄ " + store.talkTarget().toUpperCase() + " · отпусти — переведу", 0xE0D70015, this::stop);
        updateNotification("🔴 Запись");
    }

    /** Отпускание пальца — главный сигнал стопа; намерение HOLD_END может прийти раньше старта или потеряться */
    private final Runnable fingerWatch = new Runnable() {
        @Override public void run() {
            if (!recording || !holdStart) return;
            if (!DictateA11y.fingerDown) { stop(); return; }
            main.postDelayed(this, 100);
        }
    };

    private void cancelHold() {
        main.removeCallbacks(recordTimeout);
        main.removeCallbacks(fingerWatch);
        recording = false;
        holdStart = false;
        recorder.stop();
        recorder.dropStream();
        done(null, 0, 0);
    }

    private boolean begin() {
        autoListening = false;
        sideStart = holdStart = false;
        noAutoListen = false;
        if (tts != null) tts.stop(); // ещё читает прошлый перевод — замолчать, слушать нового говорящего
        main.removeCallbacks(hideLater);
        failedPcm = null;
        recorder.openStream(store);
        if (!recorder.start()) { done("⚠️ Микрофон недоступен", 0xE0B00020); return false; }
        recording = true;
        vibrate(40);
        main.postDelayed(recordTimeout, 180_000);
        return true;
    }

    /** Боковая кнопка в режиме перевода, а поля ввода нет — это разговор: открываем экран «Переводчик», он слушает сам.
     *  Пока на экране наше окно-помощник, поле не узнать — ждём до ~1.2 с */
    private void checkTranslator(int attempt) {
        if (!recording || !sideStart) return;
        DictateA11y a = DictateA11y.instance;
        Boolean field;
        try { field = a == null ? Boolean.TRUE : a.hasInputField(); } catch (Throwable t) { field = Boolean.TRUE; }
        if (field == null && attempt < 4) { main.postDelayed(() -> checkTranslator(attempt + 1), 300); return; }
        Log.i("button: поле " + (field == null ? "не понять" : field ? "есть" : "нет"));
        if (!Boolean.FALSE.equals(field)) return;
        // Записанное за эти доли секунды выбрасываем: переводчик начнёт слушать сам, с начала фразы
        main.removeCallbacks(recordTimeout);
        main.removeCallbacks(vadTick);
        recording = false;
        sideStart = false;
        recorder.stop();
        recorder.dropStream();
        Log.i("button: поля нет — переводчик");
        // Открывает служба специальных возможностей: ей Android разрешает показывать окна из фона, нашей службе — нет
        a.startActivity(new Intent().setClassName(a, getPackageName() + ".TranslatorListen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(TranslatorActivity.LISTEN, true));
        done(null, 0, 0);
    }

    private final Runnable recordTimeout = () -> { if (recording) { Log.e("voice: recording > 180 s"); stop(); } };
    private final Runnable processingTimeout = () -> {
        if (!processing) return;
        Log.e("voice: processing > 75 s");
        processing = false;
        done("⚠️ Нет ответа от сервера", 0xE0B00020);
    };

    /** Автопрослушка ответа: запись без кнопки; стоп — 3 с тишины после голоса, тап по плашке или долгое нажатие.
     *  10 с без голоса — разговор закончен, запись выбрасывается */
    private void autoListen() {
        if (recording || processing || !talkLoop) return;
        recorder.openStream(store);
        if (!recorder.start()) { talkLoop = false; done("⚠️ Микрофон недоступен", 0xE0B00020); return; }
        recording = true;
        autoListening = true;
        vibrate(20);
        plaque("🎧 Слушаю ответ… · тап — стоп", 0xE0305FBF, this::stop);
        updateNotification("🎧 Слушаю ответ");
        main.postDelayed(recordTimeout, 180_000);
        main.postDelayed(vadTick, 200);
    }

    private final Runnable vadTick = new Runnable() {
        @Override public void run() {
            if (!recording || !(autoListening || sideStart)) return;
            if (!recorder.heardVoice() && recorder.silenceMs() > 10_000) {
                // Никто не заговорил — конец разговора, без вибрации и ошибок
                main.removeCallbacks(recordTimeout);
                recording = false;
                autoListening = false;
                talkLoop = false;
                boolean side = sideStart;
                sideStart = false;
                recorder.stop();
                recorder.dropStream();
                Log.i(side ? "button: тишина 10 с — ничего не сказано" : "talk: конец разговора (тишина)");
                done(side ? "🤷 Ничего не услышал" : null, 0xE0303034, side ? 1500 : 0);
                return;
            }
            int limit = sideStart ? DICTATE_SILENCE_MS : TALK_SILENCE_MS;
            if (recorder.heardVoice() && recorder.silenceMs() > limit) {
                Log.i("voice: стоп по тишине (" + recorder.levels() + ")");
                stop(); return;
            }
            // Страховка боковой кнопки: даже если тишина так и не определилась — не дольше 60 с
            if (sideStart && recorder.silenceMs() > 0 && System.currentTimeMillis() - sideStartedAt > 60_000) { Log.e("voice: 60 с без тишины (" + recorder.levels() + ")"); stop(); return; }
            main.postDelayed(this, 200);
        }
    };

    private void stop() {
        if (!recording) return;
        main.removeCallbacks(recordTimeout);
        main.removeCallbacks(vadTick);
        main.removeCallbacks(fingerWatch);
        recording = false;
        vibrate(25);
        byte[] pcm = recorder.stop();
        nextStream = recorder.takeStream();
        if (Recorder.seconds(pcm) < Pipeline.MIN_SECONDS) { dropNextStream(); done(null, 0, 0); return; }
        String target = store.target();
        processing = true; // занят: решаем, разговор ли это
        plaque(target.equals("ru") ? "⏳ Распознаю…" : "⏳ Перевожу…", 0xE0303034, null);
        if (autoListening) { autoListening = false; process(pcm, target, true); return; } // ответ в разговоре — поле не проверяем
        // Боковая: поле есть (иначе уже открылся переводчик) или режим RU — обычная диктовка
        if (sideStart) { sideStart = false; process(pcm, target, false); return; }
        if (holdStart) { holdStart = false; noAutoListen = true; process(pcm, store.talkTarget(), true); return; }
        decideTalk(pcm, target, 0);
    }

    /** Плавающая кнопка: разговор = режим перевода и нет поля с курсором. null (не понять) — ещё раз через 0.3 с, до ~1.2 с */
    private void decideTalk(byte[] pcm, String target, int attempt) {
        if (target.equals("ru")) { process(pcm, target, false); return; } // разговор только в режиме перевода — поле не важно
        DictateA11y a = DictateA11y.instance;
        Boolean field;
        try {
            field = a == null ? Boolean.TRUE : a.hasInputField(); // без спец. возможностей поле не узнать — как раньше, в буфер
        } catch (Throwable t) {
            Log.e("decideTalk: " + t);
            field = Boolean.TRUE; // не понять — не разговор (иначе processing залипнет без process())
        }
        if (field == null && attempt < 4) { main.postDelayed(() -> decideTalk(pcm, target, attempt + 1), 300); return; }
        boolean talk = !target.equals("ru") && Boolean.FALSE.equals(field);
        process(pcm, target, talk);
    }

    /** Стрим записи, которую сейчас распознаём (Recorder.takeStream в stop): уходит в Pipeline.run */
    private Stream nextStream;
    private void dropNextStream() { if (nextStream != null) { nextStream.cancel(); nextStream = null; } }

    private void process(byte[] pcm, String target, boolean talk) {
        final Stream st = nextStream;
        nextStream = null;
        processing = true;
        final int gen = ++generation;
        plaque(talk ? "⏳ Перевожу для разговора…" : target.equals("ru") ? "⏳ Распознаю…" : "⏳ Перевожу…", 0xE0303034, null);
        updateNotification("⏳ Обработка");
        main.postDelayed(processingTimeout, 75_000);
        work.execute(() -> {
            Pipeline.Result r = Pipeline.run(store, pcm, target, false, "button", talk, st);
            main.post(() -> deliver(gen, r, pcm, target, talk));
        });
    }

    private void deliver(int gen, Pipeline.Result r, byte[] pcm, String target, boolean talk) {
        if (gen != generation || !processing) { // сторож уже сбросил — только в буфер, текст не теряем
            if (r.result != null) { store.setLastText(r.result); Pipeline.toClipboard(this, r.result); }
            return;
        }
        main.removeCallbacks(processingTimeout);
        processing = false;
        if (r.sttFailed && r.netError && !talk) {
            // Диктовка без сети — звук на диск, распознается сам, когда сеть появится (Pending, уведомление)
            talkLoop = false;
            Pending.save(this, pcm, target);
            done("⏳ Нет сети — запись сохранена, распознаю сама и пришлю уведомление", 0xE0303034, 4000);
            return;
        }
        if (r.sttFailed) {
            talkLoop = false;
            failedPcm = pcm; failedTarget = target; failedTalk = talk;
            plaque(r.error + " · тап — повторить", 0xE0B00020, this::retry);
            done(null, 0, 8000, true);
            return;
        }
        failedPcm = null;
        if (r.result == null) {
            // Кашель/шум в разговоре: распознавание вернуло пусто — слушаем дальше (не больше 2 раз подряд)
            if (talk && talkLoop && r.fallback == null && emptyStreak < 2 && "🤷 Ничего не распознано".equals(r.error)) {
                emptyStreak++;
                main.postDelayed(this::autoListen, 250);
                return;
            }
            talkLoop = false;
            if (r.fallback != null) Pipeline.toClipboard(this, r.fallback);
            done(r.error != null ? r.error : "🤷 Ничего не распознано", 0xE0B00020);
            return;
        }
        if (!talk) store.setLastText(r.result); // ответ собеседника в «последнюю диктовку» не кладём — его не вставить ⌨-долгим тапом в чужой чат
        if (talk) { talkLoop = !noAutoListen; emptyStreak = 0; speak(r.result, r.lang, 0); return; }
        String text = r.result.endsWith(" ") ? r.result : r.result + " "; // пробел — следующая диктовка не прилипнет
        // Текст всегда в буфере: вставка через ACTION_PASTE берёт его оттуда, а если поля нет — он не потерян
        Pipeline.toClipboard(this, text);
        DictateA11y a = DictateA11y.instance;
        boolean pasted = a != null && a.insert(text);
        Log.i("button deliver: " + (pasted ? "pasted" : a == null ? "clipboard (a11y off)" : "clipboard (no field)"));
        if (pasted) done(String.format("✓ %.1f с", r.secs), 0xE0248A3D);
        else if (a == null) done("📋 В буфере — вставь долгим тапом (включи Dictate в спец. возможностях, чтобы вставлялось само)", 0xE0303034, 4500);
        else done("📋 Поля ввода нет — текст в буфере", 0xE0303034, 3000);
    }

    private void retry() {
        if (failedPcm == null || recording || processing) return;
        main.removeCallbacks(hideLater);
        byte[] pcm = failedPcm;
        failedPcm = null;
        process(pcm, failedTarget, failedTalk);
    }

    private void done(String msg, int color) { done(msg, color, 2000); }
    private void done(String msg, int color, int ms) { done(msg, color, ms, false); }

    private final Runnable hideLater = () -> {
        if (recording || processing) return;
        if (tts != null && tts.isSpeaking()) { main.postDelayed(this.hideLater, 1000); return; }
        DictateA11y a = DictateA11y.instance;
        if (a != null) a.hidePlaque();
        failedPcm = null; // не повторили за 8 с — звук не держим (он есть только в памяти службы)
        stopSelfSafely();
    };

    /** Показать итог и через ms убрать плашку и службу (keepPlaque — плашка уже показана с действием по тапу) */
    private void done(String msg, int color, int ms, boolean keepPlaque) {
        dropNextStream();
        if (msg != null && !keepPlaque) plaque(msg, color, null);
        main.removeCallbacks(hideLater);
        main.postDelayed(hideLater, msg == null && !keepPlaque ? 0 : ms);
    }

    /** Разговор: перевод крупно на плашке и вслух; тап по плашке — замолчать. Текст ещё и в буфере */
    private void speak(String text, String lang, int attempt) {
        // Синтез поднимается асинхронно при старте службы — на первой фразе может быть ещё не готов: ждём до ~3 с
        if (ttsState == 0 && attempt < 10) { main.postDelayed(() -> speak(text, lang, attempt + 1), 300); return; }
        Pipeline.toClipboard(this, text);
        String flag = lang.equals("ru") ? "🇷🇺 " : lang.equals("vi") ? "🇻🇳 " : lang.equals("tr") ? "🇹🇷 " : lang.equals("fr") ? "🇫🇷 " : "🇬🇧 ";
        // Тап, пока говорит, — замолчать и закончить разговор
        plaque(flag + text, 0xF0202024, () -> { talkLoop = false; tts.stop(); main.removeCallbacks(hideLater); main.post(hideLater); });
        main.removeCallbacks(hideLater);
        main.postDelayed(hideLater, 60_000); // страховка, если синтез так и не скажет «закончил»
        // Озвучка выключена (🔇 в переводчике) — только текст на плашке, подольше
        if (noAutoListen && !store.talkSound()) { main.removeCallbacks(hideLater); main.postDelayed(hideLater, 10_000); return; }
        Locale loc = Store.locale(lang);
        int ok = ttsState == 1 ? tts.setLanguage(loc) : TextToSpeech.ERROR;
        if (ok < 0) {
            Log.e("tts: нет голоса " + loc + " (" + ok + ")");
            plaque(flag + text + "\n(нет голоса для " + lang + " — Настройки → Синтез речи)", 0xF0202024, () -> { main.removeCallbacks(hideLater); main.post(hideLater); });
            main.removeCallbacks(hideLater);
            main.postDelayed(hideLater, 12_000);
            return;
        }
        Log.i("tts: " + loc);
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "talk") != TextToSpeech.SUCCESS) {
            Log.e("tts: speak failed");
            main.removeCallbacks(hideLater);
            main.postDelayed(hideLater, 12_000); // onError в этом случае не придёт — показываем текст и убираем
        }
    }

    /** Плашка через специальные возможности (не забирает фокус); без них — всплывающее сообщение */
    private void plaque(String text, int color, Runnable onTap) {
        DictateA11y a = DictateA11y.instance;
        if (a != null) a.showPlaque(text, color, onTap);
        else Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification(String text) {
        // Тап по уведомлению — тоже стоп (если плашки нет: специальные возможности выключены)
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, VoiceService.class).setAction(TOGGLE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Dictate")
                .setContentText(text)
                .setContentIntent(stop)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(1, notification(text));
    }

    private void vibrate(int ms) {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @Override public void onDestroy() {
        alive = false;
        main.removeCallbacksAndMessages(null);
        if (recording) { recorder.stop(); recorder.dropStream(); }
        if (tts != null) tts.shutdown();
        work.shutdown(); // не shutdownNow: идущий запрос дорабатывает, иначе прерывается обработка
        DictateA11y a = DictateA11y.instance;
        if (a != null) a.hidePlaque();
        super.onDestroy();
    }
}
