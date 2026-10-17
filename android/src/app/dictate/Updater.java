package app.dictate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Обновления без провода (13.10.2026): Mac (publish.sh) кладёт на сервер APK, config.json, memory.md, crm.md и manifest.json.
 *  Телефон раз в 30 мин (при показе клавиатуры и открытии настроек) читает manifest: изменившиеся данные — молча
 *  заменяет (как sync.sh), новую версию — скачивает и показывает уведомление «Установить» (ставит Android по тапу).
 *  Сервер — свой сертификат (res/raw/update_cert.pem): доверяем ТОЛЬКО ему, системные CA не участвуют.
 *  Доступ — заголовок X-Dictate-Token (Store.updateToken), подлинность — HMAC manifest (Store.signKey); оба приходят один раз через sync.sh. */
final class Updater {
    private Updater() {}
    static final String BASE = ""; // свой сервер обновлений — см. handoff/AGENTS.md; пусто — обновления выключены
    private static final String CHANNEL = "updates";
    private static final int NOTIFY_ID = 2;
    private static final long INTERVAL_MS = 30 * 60_000L, RETRY_MS = 10 * 60_000L;
    /** Данные, которые сервер может прислать (имя → в files/). Любое другое имя из manifest игнорируется */
    private static final String[] DATA = {"config.json", "memory.md", "crm.md"};
    static final String APK = "update.apk";

    private static final int MAX_DATA = 2_000_000;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static SSLSocketFactory tls;

    /** Проверить в фоне; force — не смотреть на интервал (кнопка «Проверить» в настройках). done зовётся всегда, на главном потоке */
    static void checkAsync(Context c, boolean force, Runnable done) {
        if (BASE.isEmpty()) { post(done); return; } // своего сервера обновлений нет — обновления выключены
        Context ctx = c.getApplicationContext();
        Store store = new Store(ctx);
        reportVersionOnce(ctx, store);
        long now = System.currentTimeMillis();
        // После сбоя (нет сети) — повтор через 10 мин, а не на каждый показ клавиатуры
        boolean due = now - store.prefs().getLong("updCheckedAt", 0) >= INTERVAL_MS && now - store.prefs().getLong("updFailedAt", 0) >= RETRY_MS;
        if ((!force && !due) || !running.compareAndSet(false, true)) { post(done); return; }
        new Thread(() -> {
            try {
                // Токен (расшифровка Keystore) — здесь, не на главном потоке: onStartInputView не должен ждать
                if (store.updateToken() != null) check(ctx, store);
            } catch (Throwable t) {
                Log.e("update check: " + t);
                store.prefs().edit().putString("updError", shortError(t)).putLong("updFailedAt", System.currentTimeMillis()).apply();
            } finally {
                running.set(false);
                post(done);
            }
        }, "dictate-check").start();
    }

    /** Отчёт «встала версия N, самопроверка ok» — один раз на версию. MY_PACKAGE_REPLACED на Samsung с 14.10 не доходил
     *  (три установки без отчёта), поэтому дублируем при первом показе клавиатуры/настроек новой версии */
    static void reportVersionOnce(Context ctx, Store store) {
        int v = versionCode(ctx);
        if (store.prefs().getInt("reportedV", 0) == v) return;
        store.prefs().edit().putInt("reportedV", v).apply();
        if (store.prefs().getInt("silentV", 0) == v) notifySilent(ctx, v);
        String check = SettingsActivity.selfCheck(store.config());
        reportAsync(ctx, check == null ? "ok" : check, null);
    }

    private static void post(Runnable r) { if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }

    private static String shortError(Throwable t) {
        if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException || t instanceof java.net.SocketTimeoutException) return "нет связи с сервером";
        return t.getClass().getSimpleName();
    }

    private static void check(Context ctx, Store store) throws Exception {
        String token = store.updateToken();
        int installed = versionCode(ctx);
        // Версия в адресе — сервер её не читает, но они видны в его логе: «телефон жив, стоит версия N»
        JSONObject m = verified(store, get(ctx, token, "/manifest.json?v=" + installed));
        int data = 0;
        // Что пришло — для экрана настроек: «Проверить сейчас» без этого выглядит так, будто ничего не произошло
        java.util.List<String> got = new java.util.ArrayList<>();
        JSONObject files = m.optJSONObject("files");
        if (files != null) for (String name : DATA) {
            File local = new File(ctx.getFilesDir(), name);
            if (!files.has(name) || files.isNull(name)) {
                // Файла на Mac больше нет (crm.md) — убрать и тут, чтобы не жил устаревший
                if (local.exists() && local.delete()) data++;
                continue;
            }
            String sha = files.getString(name);
            if (local.exists() && sha.equals(sha256(local))) continue;
            byte[] body = get(ctx, token, "/" + name);
            if (body.length > MAX_DATA) throw new IOException(name + ": слишком большой");
            if (!sha.equals(hex(MessageDigest.getInstance("SHA-256").digest(body)))) throw new IOException(name + ": sha не совпал");
            writeAtomic(local, body);
            data++;
        }
        if (data > 0) { Log.i("update: данные обновлены (" + data + ")"); got.add("словарь/память/CRM"); }

        // Ключ xAI (решение 13.10): новый в хранилище ключей Mac → publish.sh → сюда → сразу в Keystore (Store.putApiKey).
        // Какой ключ уже взят — помним по sha
        String keySha = m.optString("xaiKey", "");
        if (keySha.matches("[0-9a-f]{64}") && !keySha.equals(store.prefs().getString("xaiKeySha", ""))) {
            byte[] body = get(ctx, token, "/xai.key");
            if (body.length > 1000) throw new IOException("xai.key: слишком большой");
            if (!keySha.equals(hex(MessageDigest.getInstance("SHA-256").digest(body)))) throw new IOException("xai.key: sha не совпал");
            String plain = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (plain.isEmpty()) throw new IOException("xai.key: пустой");
            store.putApiKey(plain);
            store.prefs().edit().putString("xaiKeySha", keySha).apply();
            Log.i("update: ключ xAI обновлён");
            got.add("ключ xAI");
        }

        int latest = m.optInt("versionCode", 0);
        File apk = new File(ctx.getCacheDir(), APK);
        if (latest > installed) {
            String sha = m.getString("apkSha256");
            if (!(apk.exists() && sha.equals(sha256(apk)))) {
                String name = m.getString("apk");
                if (!name.matches("[A-Za-z0-9._-]+\\.apk")) throw new IOException("apk: странное имя");
                byte[] body = get(ctx, token, "/" + name);
                if (!sha.equals(hex(MessageDigest.getInstance("SHA-256").digest(body)))) throw new IOException("apk: sha не совпал");
                writeAtomic(apk, body);
            }
            // Проверка до уведомления: тот же пакет, правда новее и наша подпись (Android её всё равно сверит при установке)
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getPath(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi == null || !ctx.getPackageName().equals(pi.packageName) || pi.getLongVersionCode() <= installed || !sameSigner(ctx, pi)
                    || pi.versionName == null || !pi.versionName.matches("[0-9A-Za-z.+-]{1,32}")) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                throw new IOException("apk: не тот пакет, не новее или чужая подпись");
            }
            store.prefs().edit().putInt("updReady", (int) pi.getLongVersionCode()).putString("updReadyName", pi.versionName).putString("updReadySha", sha).apply();
            // Android 12+: ставим сами, когда погаснет экран (DictateA11y → autoInstall). Уведомление — если сами не можем
            // (нет службы, ловящей экран) или тихо не вышло; один раз на версию: смахнул — не звеним каждые 30 мин
            if (Build.VERSION.SDK_INT < 31 || DictateA11y.instance == null || store.prefs().getInt("autoFailed", 0) == (int) pi.getLongVersionCode())
                notifyOnce(ctx, store);
            Log.i("update: готова версия " + pi.getLongVersionCode());
            got.add("версия " + pi.versionName);
        } else {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            store.prefs().edit().remove("updReady").apply();
            ctx.getSystemService(NotificationManager.class).cancel(NOTIFY_ID);
        }
        store.prefs().edit().putLong("updCheckedAt", System.currentTimeMillis())
                .putString("updResult", got.isEmpty() ? "нового нет" : "получено: " + String.join(", ", got)).remove("updError").remove("updFailedAt").apply();
    }

    /** manifest.json = «hex HMAC-SHA256\n» + JSON. Ключ (Store.signKey) знают только Mac и телефон — сервер подделать не может.
     *  seq не меньше уже виденного: старый подписанный manifest (откат на прошлую версию данных) не примем */
    private static JSONObject verified(Store store, byte[] raw) throws Exception {
        String key = store.signKey();
        if (key == null) throw new IOException("нет ключа подписи — sync.sh по проводу");
        int nl = -1;
        for (int i = 0; i < raw.length && i < 100; i++) if (raw[i] == '\n') { nl = i; break; }
        if (nl != 64) throw new IOException("manifest без подписи");
        byte[] body = java.util.Arrays.copyOfRange(raw, nl + 1, raw.length);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] want = mac.doFinal(body);
        byte[] got = unhex(new String(raw, 0, nl, java.nio.charset.StandardCharsets.US_ASCII));
        if (got == null || !MessageDigest.isEqual(want, got)) throw new IOException("manifest: подпись не сходится");
        JSONObject m = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        long seq = m.optLong("seq", 0), seen = store.prefs().getLong("updSeq", 0);
        if (seq < seen) throw new IOException("manifest старше уже виденного");
        store.prefs().edit().putLong("updSeq", seq).apply();
        return m;
    }

    private static byte[] unhex(String h) {
        if (h.length() % 2 != 0) return null;
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16), lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) (hi * 16 + lo);
        }
        return b;
    }

    /** Подпись архива = подпись установленной Dictate (Android и сам не поставит чужую, но уведомление-приманку не покажем) */
    private static boolean sameSigner(Context ctx, PackageInfo archive) {
        try {
            PackageInfo me = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive.signingInfo == null || me.signingInfo == null) return false;
            android.content.pm.Signature[] a = archive.signingInfo.getApkContentsSigners(), b = me.signingInfo.getApkContentsSigners();
            return a != null && b != null && java.util.Arrays.equals(a, b);
        } catch (Exception e) {
            return false;
        }
    }

    /** Готовая к установке версия (скачана и проверена); 0 — нет */
    static int readyVersion(Context ctx) {
        int v = new Store(ctx).prefs().getInt("updReady", 0);
        return v > versionCode(ctx) && new File(ctx.getCacheDir(), APK).exists() ? v : 0;
    }

    private static void notifyReady(Context ctx, String name) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, new Intent(ctx, UpdateActivity.class).setAction(UpdateActivity.INSTALL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIFY_ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Обновление Dictate " + name)
                .setContentText("Нажми, чтобы установить")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    /** Установка скачанного APK — из UpdateActivity (окно на экране: только так Android покажет своё «Обновить?») */
    /** Тихое обновление (14.10.2026, пользователь). Зовётся при погасшем экране:
     *  сначала проверка (если пора), потом установка без вопроса. Установка убивает процесс — поэтому не во время записи
     *  и распознавания. Не вышло (система попросила подтверждения или ошибка) — для этой версии обычное уведомление.
     *  Безопасность: ставится только APK из manifest с HMAC (ключ — Mac и телефон) и с нашей подписью — как и раньше */
    static void autoInstall(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        Context ctx = c.getApplicationContext();
        checkAsync(ctx, false, () -> {
            int v = readyVersion(ctx);
            Store store = new Store(ctx);
            if (v == 0 || store.prefs().getInt("autoFailed", 0) == v || !idle(ctx)) return;
            // Экран быстро гасили-включали — вторая сессия установки не нужна
            if (!installing.compareAndSet(false, true)) return;
            new Thread(() -> {
                try {
                    Log.i("update: тихая установка " + v);
                    PendingIntent st = PendingIntent.getBroadcast(ctx, 2, new Intent(ctx, InstallStatusReceiver.class).putExtra("v", v),
                            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    install(ctx, st, true);
                } catch (Exception e) { autoFailed(ctx, v, "exception " + e.getClass().getSimpleName()); }
                finally { installing.set(false); }
            }, "dictate-autoinstall").start();
        });
    }

    /** Тихо не вышло — больше не пробуем эту версию, показываем обычное уведомление */
    static void autoFailed(Context ctx, int v, String why) {
        Store store = new Store(ctx);
        store.prefs().edit().putInt("autoFailed", v).apply();
        Log.e("update: тихая установка не вышла — " + why);
        reportAsync(ctx, "auto-install-failed " + shorten(why), null);
        notifyOnce(ctx, store);
    }

    private static String shorten(String s) { s = s.replaceAll("\\s+", " "); return s.length() > 120 ? s.substring(0, 120) : s; }

    static void notifySilent(Context ctx, int v) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_DEFAULT));
        String when = new java.text.SimpleDateFormat("d MMM HH:mm", new java.util.Locale("ru")).format(new java.util.Date());
        nm.notify(NOTIFY_ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Dictate обновился сам — " + v)
                .setContentText(when + " · не публиковал? Скажи Claude")
                .setAutoCancel(true)
                .build());
    }

    /** Уведомление «Нажми, чтобы установить» — один раз на версию, если она ещё не стоит */
    static void notifyOnce(Context ctx, Store store) {
        int v = readyVersion(ctx);
        if (v == 0 || store.prefs().getInt("notifiedV", 0) == v) return;
        store.prefs().edit().putInt("notifiedV", v).apply();
        notifyReady(ctx, store.prefs().getString("updReadyName", String.valueOf(v)));
    }

    private static final AtomicBoolean installing = new AtomicBoolean();

    /** Телефоном сейчас не пользуются и ничего не пишется/не распознаётся */
    static boolean idle(Context ctx) {
        return !DictateIME.busy && !VoiceService.alive && Pipeline.active.get() == 0
                && !((android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE)).isInteractive();
    }

    static void install(Context ctx, PendingIntent status) throws IOException { install(ctx, status, false); }

    static void install(Context ctx, PendingIntent status, boolean silent) throws IOException {
        File apk = new File(ctx.getCacheDir(), APK);
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        // Тапом из уведомления — с системным «Обновить?»; при погасшем экране — молча (решение 14.10; раньше всегда
        // спрашивали — ревью 13.10). Молча Android ставит, только если прошлую версию поставила сама Dictate
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(silent ? PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                : PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = pi.createSession(p);
        boolean committed = false;
        try (PackageInstaller.Session s = pi.openSession(id)) {
            MessageDigest md;
            try { md = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("dictate.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); }
                s.fsync(out);
            }
            // Ставится ровно то, что проверили в check(): файл в кеше мог смениться за часы ожидания (аудит 14.10)
            String want = new Store(ctx).prefs().getString("updReadySha", "");
            if (!want.equals(hex(md.digest()))) throw new IOException("apk: sha изменился после проверки");
            // Пока копировали APK (секунды), экран могли включить или начать диктовку — тогда в другой раз
            if (silent && !idle(ctx)) { Log.i("update: тихая установка отложена — телефоном пользуются"); return; }
            // Отметка до commit: после установки новая версия скажет «обновился сам» уведомлением (аудит 14.10 — тихая
            // установка не должна проходить незаметно: так видно и обновление, которого пользователь не публиковал)
            if (silent) new Store(ctx).prefs().edit().putInt("silentV", new Store(ctx).prefs().getInt("updReady", 0)).commit();
            s.commit(status.getIntentSender());
            committed = true;
        } finally {
            if (!committed) try { pi.abandonSession(id); } catch (Exception ignored) {}
        }
    }

    /** Отчёт после обновления: версия + результат самопроверки, без текста. Сервер отвечает 204, отчёт — строка его лога */
    static void reportAsync(Context c, String result, Runnable done) {
        if (BASE.isEmpty()) { if (done != null) done.run(); return; }
        Context ctx = c.getApplicationContext();
        new Thread(() -> {
            try {
                String token = new Store(ctx).updateToken();
                if (token != null) get(ctx, token, "/report?v=" + versionCode(ctx) + "&s=" + java.net.URLEncoder.encode(result, "UTF-8"));
            } catch (Throwable t) { Log.e("update report: " + t); }
            finally { if (done != null) done.run(); }
        }, "dictate-report").start();
    }

    static int versionCode(Context ctx) {
        try { return (int) ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; }
    }

    // MARK: сеть

    private static synchronized SSLSocketFactory tls(Context ctx) throws Exception {
        if (tls != null) return tls;
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null);
        try (InputStream in = ctx.getResources().openRawResource(R.raw.update_cert)) {
            ks.setCertificateEntry("dictate", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tmf.getTrustManagers(), null);
        tls = sc.getSocketFactory();
        return tls;
    }

    private static byte[] get(Context ctx, String token, String path) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(BASE + path).openConnection();
        c.setSSLSocketFactory(tls(ctx));
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("X-Dictate-Token", token);
        try {
            int code = c.getResponseCode();
            if (code == 204) return new byte[0];
            if (code != 200) throw new IOException("HTTP " + code + " " + path.replaceAll("\\?.*", ""));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 50_000_000) throw new IOException("слишком большой ответ");
                }
                return b.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    // MARK: файлы

    private static void writeAtomic(File f, byte[] body) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(body); out.getFD().sync(); }
        if (!tmp.renameTo(f)) throw new IOException(f.getName() + ": не удалось записать");
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
