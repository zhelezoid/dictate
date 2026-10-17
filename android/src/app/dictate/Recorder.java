package app.dictate;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/** 16 кГц mono PCM16 — формат, который ждёт xAI STT */
final class Recorder {
    static final int RATE = 16000;
    private AudioRecord rec;
    private Thread thread;
    private volatile boolean running;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    /** Куда отдавать каждый кусок 100 мс во время записи (Stream); null — никуда */
    private volatile Stream stream;
    /** Стрим законченной записи — ждёт, пока его заберут на распознавание (takeStream); не забрали — закрывается
     *  при следующем start() или dropStream() */
    private Stream detached;

    /** Перед start(): потоковое распознавание этой записи + прогрев соединения для перевода */
    void openStream(Store store) {
        dropStream();
        Stream old = stream;
        if (old != null) old.cancel();
        stream = Stream.open(store);
        Api.warmUp();
    }

    /** Стрим только что законченной записи — передать в Pipeline.run; null — стрима не было */
    synchronized Stream takeStream() { Stream s = detached; detached = null; return s; }

    /** Запись выброшена — её стрим не нужен */
    synchronized void dropStream() { Stream s = takeStream(); if (s != null) s.cancel(); }

    // Детектор речи (автостоп боковой кнопки, автопрослушка, переводчик): громкость кусков по 100 мс относительно шума комнаты.
    // 14.10: шум считался минимумом с первого куска, а первые куски AudioRecord — цифровые нули (−120 дБ): порог падал
    // до −48 дБ, шум комнаты сходил за голос, тишина не наступала — боковая кнопка писала бесконечно. Теперь нули не в счёт,
    // шум — минимум за последние 3 с, но не выше долгого минимума + 6 дБ (сплошная речь не станет «шумом»)
    private volatile long startedAt, lastVoiceAt;
    private volatile boolean heardVoice;
    private final double[] ring = new double[30];
    private int ringN, ringPos, chunks;
    private double floor = Double.NaN, peak = -120, peakS = -120, smSum;
    private int smCnt;
    private final double[] pw = new double[5];
    private int pwN, pwPos;
    /** Диагностика без звука и текста: сколько кусков сочтено голосом, максимум громкости по секундам (для отчёта на сервер) */
    private int voiced, valid;
    private final StringBuilder perSec = new StringBuilder();
    private double secMax = -120;

    /** Был ли в записи голос (громче шума комнаты) */
    boolean heardVoice() { return heardVoice; }
    /** Сколько мс тишины после последнего голоса; до первого голоса — с начала записи */
    long silenceMs() { return System.currentTimeMillis() - (heardVoice ? lastVoiceAt : startedAt); }
    /** Для лога: шум и пик (дБFS) — без звука и текста */
    String levels() { return String.format(java.util.Locale.US, "noise %.0f dB, peak %.0f dB", Double.isNaN(floor) ? -120 : floor, peak); }
    synchronized String diag() {
        return String.format(java.util.Locale.US, "chunks=%d valid=%d voiced=%d floor=%.0f peak=%.0f peakS=%.0f avgS=%.0f sec=%s", chunks, valid, voiced,
                Double.isNaN(floor) ? -120 : floor, peak, peakS, smCnt == 0 ? -120 : smSum / smCnt, perSec.length() > 300 ? perSec.substring(0, 300) : perSec.toString());
    }

    private void level(byte[] buf, int n) {
        double sum = 0;
        int k = n / 2;
        for (int i = 0; i + 1 < n; i += 2) { int v = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8)); sum += (double) v * v; }
        double db = k == 0 ? -120 : 10 * Math.log10(sum / k / (32768.0 * 32768.0) + 1e-12);
        chunks++;
        secMax = Math.max(secMax, db);
        if (chunks % 10 == 0) { synchronized (this) { perSec.append(Math.round(secMax)).append(','); } secMax = -120; }
        if (db < -90 || chunks <= 2) return; // нули разогрева микрофона и щелчок старта — не шум и не голос
        valid++;
        // Сглаженная громкость за 0.5 с (средняя мощность 5 кусков): одиночные всплески фона не сходят за речь.
        // 14.10, реальные уровни с телефона пользователь: фон в паузах до −35…−42 дБ (пики по секундам), речь −20…−28 —
        // разница ~15 дБ, порог «тише всего + 10 дБ» фон пропускал как голос, тишина не наступала
        pw[pwPos] = Math.pow(10, db / 10); pwPos = (pwPos + 1) % pw.length; pwN = Math.min(pwN + 1, pw.length);
        double avg = 0;
        for (int i = 0; i < pwN; i++) avg += pw[i];
        double sm = 10 * Math.log10(avg / pwN + 1e-12);
        peakS = Math.max(peakS, sm);
        smSum += sm; smCnt++;
        peak = Math.max(peak, db);
        floor = Double.isNaN(floor) ? db : Math.min(db, floor + 0.02);
        ring[ringPos] = db; ringPos = (ringPos + 1) % ring.length; ringN = Math.min(ringN + 1, ring.length);
        double win = 0;
        for (int i = 0; i < ringN; i++) win = i == 0 ? ring[i] : Math.min(win, ring[i]);
        double noise = Math.min(win, floor + 6);
        // Samsung (VOICE_RECOGNITION) в тишине сам поднимает усиление — фон растёт к уровню речи. Поэтому голос ещё и
        // не тише пика минус 22 дБ: после фразы громкостью −20 дБ фон −45 дБ голосом уже не сочтётся
        // Голос — сглаженная громкость не ниже «громкость речи − 10 дБ» и заметно выше фона
        double thr = Math.max(-60, Math.max(noise + 8, peakS - 10));
        if (ringN >= 10 && sm > thr) { heardVoice = true; lastVoiceAt = System.currentTimeMillis(); voiced++; }
    }

    /** false — нет разрешения на микрофон или он занят */
    @SuppressWarnings("MissingPermission")
    synchronized boolean start() {
        // Стрим, открытый openStream() для ЭТОЙ записи, не трогаем, даже если пришлось остановить прежнюю
        Stream keep = stream;
        if (running) stop();
        if (detached != null && detached != keep) detached.cancel();
        detached = null;
        stream = keep;
        pcm.reset();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; cancelStream(); return false; }
            rec.startRecording();
        } catch (Exception e) {
            Log.e("audio: " + e);
            if (rec != null) { rec.release(); rec = null; }
            cancelStream();
            return false;
        }
        running = true;
        heardVoice = false;
        ringN = ringPos = chunks = voiced = valid = pwN = pwPos = smCnt = 0;
        peakS = -120; smSum = 0;
        synchronized (this) { perSec.setLength(0); }
        secMax = -120;
        floor = Double.NaN;
        peak = -120;
        startedAt = System.currentTimeMillis();
        final AudioRecord r = rec;
        thread = new Thread(() -> {
            byte[] buf = new byte[3200]; // 100 мс
            while (running) {
                int n = r.read(buf, 0, buf.length);
                if (n > 0) {
                    synchronized (pcm) { pcm.write(buf, 0, n); }
                    level(buf, n);
                    Stream st = stream;
                    if (st != null) st.send(java.util.Arrays.copyOf(buf, n));
                }
                // Микрофон отняли (ERROR_DEAD_OBJECT и т.п.) — не крутить цикл вхолостую
                else if (n < 0) break;
            }
        }, "dictate-audio");
        thread.start();
        return true;
    }

    /** Останавливает запись и возвращает весь PCM */
    synchronized byte[] stop() {
        running = false;
        if (thread != null) { try { thread.join(500); } catch (InterruptedException ignored) { } thread = null; }
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            rec = null;
        }
        if (detached != null) detached.cancel();
        detached = stream;
        stream = null;
        synchronized (pcm) { return pcm.toByteArray(); }
    }

    private void cancelStream() { Stream s = stream; stream = null; if (s != null) s.cancel(); }

    static double seconds(byte[] pcm) { return pcm.length / 2.0 / RATE; }
}
