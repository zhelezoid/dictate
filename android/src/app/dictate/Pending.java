package app.dictate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Записи, которые не распознались без сети (13.10.2026, пользователь).
 *  Звук — файлом в files/pending (до 5 штук, сутки, доступ только у Dictate), JobScheduler поднимает PendingJob, как только
 *  есть сеть, — даже если клавиатуру закрыли и процесс убит. Результат — в историю («Последние») + уведомление.
 *  Поля «без обучения» (инкогнито) на диск не пишутся — там только повтор тапом, пока клавиатура открыта. */
final class Pending {
    static final int MAX = 5;
    static final long TTL = 86_400_000L;
    private static final int JOB_ID = 7;
    static final String CHANNEL = "pending";

    static final class Item {
        final File file; final long ts; final String target;
        Item(File f, long ts, String target) { this.file = f; this.ts = ts; this.target = target; }
    }

    private static File dir(Context c) { File d = new File(c.getFilesDir(), "pending"); d.mkdirs(); return d; }

    /** Сохранить и запланировать распознавание при появлении сети. Самая старая выпадает, если их уже MAX */
    static synchronized void save(Context c, byte[] pcm, String target) {
        try {
            List<Item> all = list(c);
            while (all.size() >= MAX) { //noinspection ResultOfMethodCallIgnored
                all.remove(0).file.delete(); }
            File tmp = new File(dir(c), ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(pcm); }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(dir(c), System.currentTimeMillis() + "_" + target + ".pcm"));
            Log.i("pending: сохранена запись " + String.format("%.1f", Recorder.seconds(pcm)) + " с");
            schedule(c);
        } catch (Exception e) {
            Log.e("pending save: " + e);
        }
    }

    /** От старых к новым; просроченные (> суток) и битые имена удаляются */
    static synchronized List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        long now = System.currentTimeMillis();
        for (File f : fs) {
            String n = f.getName();
            String[] p = n.replace(".pcm", "").split("_");
            long ts;
            try { ts = Long.parseLong(p[0]); } catch (Exception e) { ts = 0; }
            if (!n.endsWith(".pcm") || p.length != 2 || !p[1].matches("ru|en|vi|tr|fr") || now - ts > TTL) {
                //noinspection ResultOfMethodCallIgnored
                if (!n.equals(".tmp")) f.delete();
                continue;
            }
            out.add(new Item(f, ts, p[1]));
        }
        return out;
    }

    static int count(Context c) { return list(c).size(); }

    /** Одна обработка за раз: повторное schedule отменяет идущее задание, а его поток доработал бы тот же файл (ревью 13.10) */
    private static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || running.get()) return;
        // Зовётся из onStartInputView: любой отказ системы здесь ронял клавиатуру целиком (14.10.2026) — только в лог
        try {
            if (js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, PendingJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        } catch (RuntimeException e) { Log.e("pending schedule: " + e.getClass().getSimpleName()); }
    }

    /** Распознать всё, что ждёт. Вызывается из PendingJob (фоновый поток). false — сеть опять подвела, повторить позже */
    static boolean processAll(Context c) {
        if (!running.compareAndSet(false, true)) return true;
        try { return processAllOnce(c); } finally { running.set(false); }
    }

    private static boolean processAllOnce(Context c) {
        Store store = new Store(c);
        boolean ok = true;
        for (Item it : list(c)) {
            byte[] pcm;
            try { pcm = Files.readAllBytes(it.file.toPath()); } catch (Exception e) { //noinspection ResultOfMethodCallIgnored
                it.file.delete(); continue; }
            Pipeline.Result r = Pipeline.run(store, pcm, it.target, false, "pending", false);
            if (r.sttFailed && r.netError) { ok = false; break; } // сети снова нет — остальные тоже подождут
            // Сервер отверг именно эту запись (4xx, мусорный ответ) — повтор не поможет, а очередь за ней стояла бы сутки
            if (r.sttFailed) Log.e("pending: запись отвергнута — " + r.error);
            //noinspection ResultOfMethodCallIgnored
            it.file.delete();
            String text = r.result != null ? r.result : r.fallback;
            if (text != null) { store.setLastText(text); notifyDone(c, text); }
            else Log.i("pending: пусто (" + r.error + ")");
        }
        return ok;
    }

    private static void notifyDone(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Записи без сети", NotificationManager.IMPORTANCE_DEFAULT));
        // Тап — текст в буфер (CopyReceiver). Самого текста в уведомлении нет: на Samsung по умолчанию содержимое видно
        // на экране блокировки, и уведомления читают часы и любые приложения с доступом к ним (аудит 13.10)
        PendingIntent copy = PendingIntent.getBroadcast(c, (int) (System.currentTimeMillis() & 0xFFFF),
                new Intent(c, CopyReceiver.class).putExtra("text", text), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Распознал запись без сети")
                .setContentText("Тап — скопировать · текст в 🕘 «Последних» на клавиатуре")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(copy)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() & 0xFFFFFF), n);
    }
}
