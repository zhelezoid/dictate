package app.dictate;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Потоковое распознавание xAI (wss://api.x.ai/v1/stt), как StreamingSTT в main.swift (14.10.2026): звук уходит во время
 *  речи, после отпускания — только finalize, текст через ~0.3–0.5 с вместо batch по всей записи (~1–2 с на 10 с речи).
 *  WebSocket — свой, минимальный (RFC 6455): в Android его нет, зависимостей не добавляем. TLS — системный, имя хоста
 *  сверяется дважды (в рукопожатии и верификатором Android), ключ уходит только после этого и только в Authorization.
 *  Сеть никогда не блокирует запись звука: send() кладёт кусок в очередь, пишет отдельный поток; закрытие — без локов
 *  (ревью 14.10). В лог — только события, без текста речи. Протокол xAI: transcript.created → можно слать аудио;
 *  transcript.partial (is_final / speech_final); transcript.done; error */
final class Stream {
    private static final String HOST = "api.x.ai";
    private static final int MAX_MSG = 1 << 20;
    private static final SecureRandom RND = new SecureRandom();

    private volatile Socket raw;
    private volatile boolean closed;
    /** Готовые (замаскированные) кадры на отправку; 600 × 100 мс = минута речи, при переполнении кусок выбрасывается */
    private final BlockingQueue<byte[]> outq = new ArrayBlockingQueue<>(600);

    // Состояние разбора — под локом this, его держат только короткие операции (без сети)
    private boolean ready, doneRequested, finalizeSent;
    private final List<byte[]> pending = new ArrayList<>();
    private final List<String> utterances = new ArrayList<>(), chunks = new ArrayList<>(), pieces = new ArrayList<>();
    private String result;
    private Exception error;

    /** Открыть в фоне. null — нет ключа, ключ странный или поток выключен в config.json ("streaming": false) */
    static Stream open(Store store) {
        String key = store.apiKey();
        if (key == null || !key.matches("[A-Za-z0-9_.-]{10,300}") || !store.config().streaming) return null;
        Stream s = new Stream();
        new Thread(() -> s.run(store, key), "dictate-stream").start();
        return s;
    }

    /** Кусок PCM 16 кГц mono с потока записи. Не блокируется никогда */
    synchronized void send(byte[] chunk) {
        if (closed) return;
        if (!ready) pending.add(chunk); else enqueue(0x2, chunk);
    }

    /** Сервер принял соединение (transcript.created) — без этого ждать стрим бессмысленно, лучше batch */
    synchronized boolean isReady() { return ready && error == null; }

    /** Конец записи: finalize + audio.done, ждать текст не дольше timeoutMs. Пусто — сервер ничего не услышал */
    String finish(long timeoutMs) throws Exception {
        try {
            synchronized (this) {
                doneRequested = true;
                if (ready && !finalizeSent) sendDone();
                long until = System.currentTimeMillis() + timeoutMs;
                while (result == null && error == null) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) { error = new IOException("stream timeout"); break; }
                    wait(left);
                }
                if (result != null) return result;
                throw error;
            }
        } finally {
            close();
        }
    }

    /** Пауза порвала предложение: кусок речи, кроме последнего, кончился «. ? ! …» — пунктуация стрима ненадёжна */
    synchronized boolean brokenByPause() {
        for (int i = 0; i < pieces.size() - 1; i++) {
            String p = pieces.get(i).trim();
            if (!p.isEmpty() && ".?!…".indexOf(p.charAt(p.length() - 1)) >= 0) return true;
        }
        return false;
    }

    /** Без лока: закрытый сокет разблокирует и чтение, и зависшую запись */
    void cancel() {
        close();
        // finish() ждёт result/error — без отметки он досидел бы до своего таймаута (4 с) уже после отмены (ревью 17.10)
        synchronized (this) { if (result == null && error == null) error = new IOException("cancelled"); notifyAll(); }
    }

    // MARK: соединение

    private void run(Store store, String key) {
        try {
            Store.Config cfg = store.config();
            StringBuilder q = new StringBuilder("/v1/stt?model=").append(enc(cfg.sttModel))
                    .append("&sample_rate=16000&encoding=pcm&endpointing=5000");
            if (cfg.language != null && !cfg.language.isEmpty()) q.append("&language=").append(enc(cfg.language));
            // Длинный словарь кириллицей раздувает адрес (6 байт на букву) — сервер ответил бы 414; лишнее не шлём
            for (String t : Store.keyterms(cfg, store.memory())) {
                String e = "&keyterm=" + enc(t);
                if (q.length() + e.length() > 6000) break;
                q.append(e);
            }

            Socket r = new Socket();
            raw = r;
            if (closed) { r.close(); return; }
            r.connect(new InetSocketAddress(HOST, 443), 10000);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(r, HOST, 443, true);
            raw = s;
            s.setSoTimeout(15000); // рукопожатие; после него — без таймаута чтения (тишина в речи — не обрыв)
            SSLParameters sp = s.getSSLParameters();
            sp.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(sp);
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, s.getSession())) throw new IOException("stream: чужой сертификат");

            byte[] nonce = new byte[16];
            RND.nextBytes(nonce);
            String wsKey = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP);
            OutputStream o = s.getOutputStream();
            o.write(("GET " + q + " HTTP/1.1\r\nHost: " + HOST + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + wsKey + "\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer " + key + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            o.flush();
            InputStream in = s.getInputStream();
            String status = line(in);
            String accept = null;
            int headers = 0;
            for (String h; !(h = line(in)).isEmpty(); ) {
                if (++headers > 64) throw new IOException("stream: много заголовков");
                if (h.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-accept:")) accept = h.substring(21).trim();
            }
            if (!status.startsWith("HTTP/1.1 101")) throw new IOException("stream: " + (status.length() > 40 ? status.substring(0, 40) : status));
            String want = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), android.util.Base64.NO_WRAP);
            if (!want.equals(accept)) throw new IOException("stream: неверный ответ рукопожатия");
            s.setSoTimeout(0);
            Thread w = new Thread(() -> write(o), "dictate-stream-write");
            w.start();
            read(in);
        } catch (Exception e) {
            synchronized (this) { fail(e); }
        }
    }

    /** Поток записи: всё, что положили в очередь, — в сокет. Зависла сеть — висит он, а не запись звука */
    private void write(OutputStream o) {
        try {
            while (!closed) {
                byte[] f = outq.poll(200, TimeUnit.MILLISECONDS);
                if (f != null) { o.write(f); o.flush(); }
            }
        } catch (Exception e) {
            synchronized (this) { fail(e); }
        }
    }

    /** Кадры сервера: текст — события JSON, ping → pong, close → конец. Сервер не маскирует, может дробить сообщение */
    private void read(InputStream in) throws Exception {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (!closed) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new IOException("stream: соединение закрыто");
            int op = b0 & 0x0F;
            boolean fin = (b0 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = (byte1(in) << 8) | byte1(in);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | byte1(in); }
            if (len < 0 || len > MAX_MSG) throw new IOException("stream: неверная длина кадра");
            byte[] p = new byte[(int) len];
            for (int off = 0; off < len; ) { int n = in.read(p, off, (int) len - off); if (n < 0) throw new IOException("stream: обрыв"); off += n; }
            if (op >= 0x8) { // управляющие кадры: ≤ 125 байт и целиком (RFC 6455 §5.5)
                if (len > 125 || !fin) throw new IOException("stream: неверный управляющий кадр");
                if (op == 0x8) { synchronized (this) { if (result == null) fail(new IOException("stream: сервер закрыл")); } return; }
                if (op == 0x9) synchronized (this) { enqueue(0xA, p); }
                continue;
            }
            msg.write(p);
            if (msg.size() > MAX_MSG) throw new IOException("stream: слишком большое сообщение");
            if (!fin) continue;
            String text = new String(msg.toByteArray(), StandardCharsets.UTF_8);
            msg.reset();
            JSONObject ev;
            // Сообщение исключения org.json содержит весь вход — а там текст речи: в лог его не пускаем (аудит 14.10)
            try { ev = new JSONObject(text); } catch (org.json.JSONException e) { throw new IOException("stream: не JSON"); }
            synchronized (this) {
                handle(ev);
                if (result != null || error != null) return;
            }
        }
    }

    private static int byte1(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("stream: обрыв");
        return b;
    }

    private void handle(JSONObject ev) {
        switch (ev.optString("type")) {
            case "transcript.created":
                ready = true;
                for (byte[] c : pending) enqueue(0x2, c);
                pending.clear();
                if (doneRequested) sendDone();
                break;
            case "transcript.partial":
                if (!ev.optBoolean("is_final")) break;
                String t = ev.optString("text", "");
                if (ev.optBoolean("speech_final")) {
                    // speech_final — склеенная целая фраза, заменяет свои куски
                    if (chunks.isEmpty()) pieces.add(t);
                    utterances.add(t);
                    chunks.clear();
                    if (finalizeSent) complete(full());
                } else {
                    chunks.add(t);
                    pieces.add(t);
                }
                break;
            case "transcript.done":
                String d = ev.optString("text", "");
                complete(d.isEmpty() ? full() : d);
                break;
            case "error":
                String m = ev.optString("message", "error");
                fail(new IOException("stream: " + (m.length() > 100 ? m.substring(0, 100) : m)));
                break;
        }
    }

    private String full() {
        List<String> all = new ArrayList<>(utterances);
        all.addAll(chunks);
        return String.join(" ", all).trim();
    }

    private void sendDone() {
        finalizeSent = true;
        enqueue(0x1, "{\"type\":\"finalize\"}".getBytes(StandardCharsets.UTF_8));
        enqueue(0x1, "{\"type\":\"audio.done\"}".getBytes(StandardCharsets.UTF_8));
    }

    private void complete(String text) { if (result == null && error == null) { result = text; notifyAll(); } }

    private void fail(Exception e) { if (result == null && error == null) error = e; close(); notifyAll(); }

    /** Кадр клиента — с маской (RFC 6455 §5.3) — в очередь потока записи. Очередь полна — кусок пропадает: стриму
     *  тогда всё равно не верить, а batch по всему звуку идёт параллельно */
    private void enqueue(int op, byte[] payload) {
        if (closed) return;
        int n = payload.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(n + 14);
        f.write(0x80 | op);
        if (n < 126) f.write(0x80 | n);
        else if (n < 65536) { f.write(0x80 | 126); f.write(n >> 8); f.write(n & 0xFF); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (n >> (8 * i)) & 0xFF); }
        byte[] mask = new byte[4];
        RND.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i & 3]);
        if (!outq.offer(f.toByteArray())) { dropped = true; Log.i("stream: очередь отправки полна"); }
    }

    /** Куски звука пропадали (сеть не успевала) — тексту стрима не верить */
    private volatile boolean dropped;
    boolean lostAudio() { return dropped; }

    private void close() {
        closed = true;
        Socket r = raw;
        if (r != null) try { r.close(); } catch (Exception ignored) { }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0 && c != '\n'; ) {
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new IOException("stream: длинный заголовок");
        }
        return b.toString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }
}
