package app.dictate;

/** Конвейер одной диктовки — общий для клавиатуры и боковой кнопки (VoiceService):
 *  STT → чистка → [EN: замены → перевод → проверка] / [RU: «делаю→делай» → замены] + история. Без сети в главном потоке не звать. */
final class Pipeline {
    static final double MIN_SECONDS = 0.35;

    static final class Result {
        String result;   // что вставить / произнести; null — нечего
        String error;    // что показать; null — всё хорошо
        String fallback; // русский, если перевод не удался (в буфер, не в поле)
        boolean sttFailed; // сбой распознавания — звук стоит повторить
        /** Сбой — именно сеть/сервер (нет связи, таймаут, 5xx, 429): повтор поможет. Иначе (4xx, мусорный ответ) — нет */
        boolean netError;
        String lang;     // язык result: "ru" / "en" / "vi" / "tr" / "fr" — для озвучки
        double secs;
    }

    /** target: "ru" — как сказано, "en"/"vi"/"tr"/"fr" — перевод. talk — разговор (боковая кнопка, поля ввода нет): русская речь →
     *  перевод на target, речь на другом языке (собеседник) → перевод на русский; результат читается вслух */
    /** Сколько конвейеров идёт сейчас — тихое обновление (убивает процесс) ждёт, пока 0 */
    static final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();

    static Result run(Store store, byte[] pcm, String target, boolean incognito, String source, boolean talk) {
        return run(store, pcm, target, incognito, source, talk, null);
    }

    /** stream — потоковое распознавание, шедшее во время записи (Stream), или null — только batch */
    static Result run(Store store, byte[] pcm, String target, boolean incognito, String source, boolean talk, Stream stream) {
        active.incrementAndGet();
        try { return runOnce(store, pcm, target, incognito, source, talk, stream); } finally { active.decrementAndGet(); }
    }

    private static final java.util.concurrent.ExecutorService STT = java.util.concurrent.Executors.newCachedThreadPool();

    /** Текст записи: стрим (~0.3 с после отпускания) или batch по всему звуку (~1–2 с), как в main.swift. Batch стартует сразу
     *  параллельно и нужен, когда стриму не верим: пусто; ≤ 4 слов латиницей при русской речи (короткое «Иди» стрим слышит
     *  как «ED»); пауза порвала предложение в режиме «как сказано» (пунктуация кусками). В переводе и разговоре разрыв
     *  не важен — модель перевода всё равно связывает обрывки, поэтому там стрим берётся почти всегда */
    /** how[0] — каким путём получен текст (stream, batch-pauses…) — для истории: по ней ищем, где теряется время */
    private static String recognize(String key, byte[] pcm, Store.Config cfg, Store.Memory mem, Stream stream, boolean loosePunctuation, boolean talk, String[] how) throws Exception {
        java.util.List<String> terms = Store.keyterms(cfg, mem);
        if (stream == null) { how[0] = "batch"; return Api.transcribeHedged(key, pcm, cfg, terms).trim(); }
        java.util.concurrent.Future<String> batch = STT.submit(() -> Api.transcribeHedged(key, pcm, cfg, terms).trim());
        long t = System.currentTimeMillis();
        String s = null, why = null;
        // Сервер так и не принял поток (холодная сеть, ошибка) — ждать его бессмысленно, batch уже идёт
        if (!stream.isReady()) { stream.cancel(); why = "batch-stream-not-ready"; }
        else {
            // Стрим ждём до 4 с, но если batch уже готов, а стрим молчит дольше 1 с — берём batch (17.10.2026: зависший
            // стрим держал фразу все 4 с при готовом batch)
            java.util.concurrent.Future<String> sf = STT.submit(() -> stream.finish(4000));
            while (true) {
                try { s = sf.get(100, java.util.concurrent.TimeUnit.MILLISECONDS).trim(); break; }
                catch (java.util.concurrent.TimeoutException te) {
                    if (System.currentTimeMillis() - t >= 1000 && batch.isDone() && doneOk(batch)) { stream.cancel(); why = "batch-stream-slow"; break; }
                } catch (java.util.concurrent.ExecutionException e) { Log.i("stream: " + e.getCause().getClass().getSimpleName()); break; }
            }
        }
        long ms = System.currentTimeMillis() - t;
        if (s == null && why == null) why = "batch-stream-failed";
        else if (why != null) { /* уже решено */ }
        else if (stream.lostAudio()) why = "batch-stream-lost-audio";
        else if (s.isEmpty()) why = "batch-empty-stream";
        else if (!talk && Text.suspiciousLatin(s, cfg, mem)) why = "batch-latin-stream";
        else if (!loosePunctuation && stream.brokenByPause()) why = "batch-pauses";
        else { batch.cancel(true); Log.i("stt: stream " + ms + " ms"); how[0] = stream.brokenByPause() ? "stream-pauses" : "stream"; return s; }
        how[0] = why;
        try {
            // Без своего таймаута: у HttpURLConnection свои (10 с соединение, 30 с чтение) — их SocketTimeoutException
            // считается сетью, и запись уходит в Pending (ревью 14.10: TimeoutException здесь терял звук)
            String b = batch.get();
            Log.i("stt: " + why + " " + (System.currentTimeMillis() - t) + " ms");
            return b;
        } catch (java.util.concurrent.ExecutionException e) {
            // Batch упал, а стрим что-то дал — лучше текст стрима, чем ничего
            if (s != null && !s.isEmpty()) { Log.i("stt: stream-fallback"); how[0] = "stream-fallback"; return s; }
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
    }

    private static boolean doneOk(java.util.concurrent.Future<String> f) {
        try { f.get(); return true; } catch (Exception e) { return false; }
    }

    private static Result runOnce(Store store, byte[] pcm, String target, boolean incognito, String source, boolean talk, Stream stream) {
        Result r = new Result();
        String raw = "";
        int historyDays = 14;
        String mode = target;
        boolean translating = !target.equals("ru");
        boolean llmUsed = false;
        final long t0 = System.currentTimeMillis();
        String[] how = {null};
        double sttSecs = -1;
        try {
            String key = store.apiKey();
            if (key == null) { r.error = "Нет ключа xAI — вставь его в настройках Dictate"; return r; }
            Store.Config cfg = store.config();
            historyDays = cfg.historyDays;
            Store.Memory mem = store.memory();
            try {
                // xAI распознаёт любой язык и при language=ru (проверено 13.10 на вьетнамском и английском)
                raw = recognize(key, pcm, cfg, mem, stream, translating || talk, talk, how);
                sttSecs = (System.currentTimeMillis() - t0) / 1000.0;
            } catch (Exception ex) {
                r.sttFailed = true;
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                r.netError = ex instanceof java.net.UnknownHostException || ex instanceof java.net.SocketTimeoutException
                        || ex instanceof java.net.ConnectException || ex instanceof javax.net.ssl.SSLException
                        || ex instanceof java.net.SocketException || msg.matches("(?s).*HTTP (5\\d\\d|429).*");
                throw ex;
            }
            if (Text.isEmptyOrHallucination(raw)) {
                r.error = "🤷 Ничего не распознано";
            } else if (talk && Text.foreignSpeech(raw)) {
                // Говорил собеседник — на русский
                mode = "talk-ru";
                r.fallback = raw;
                String ru = Api.translateToRussian(key, raw, cfg, mem);
                String problem = Text.llmOutputProblem(raw, ru, cfg);
                if (ru.isEmpty() || !Text.mostlyCyrillic(ru)) r.error = "⚠️ Перевод не удался — исходный текст в буфере";
                else if (problem != null) { Log.e("translate rejected: " + problem); r.error = "⚠️ Подозрительный перевод (" + problem + ")"; }
                else { r.result = ru; r.lang = "ru"; }
            } else {
                if (talk) mode = "talk-" + target;
                String text = Text.fixImperatives(Text.localClean(raw));
                // Русская диктовка от 2 предложений — нейронка оформляет: пунктуация, кавычки, абзацы, списки (как на Mac).
                // Не удалась или переписала слова — вставляем без неё
                if (!translating && !talk && cfg.cleanup && Text.needsLLM(text)) {
                    try {
                        // 16.10.2026 разовый сбой за 0,6 с — 71 с речи ушли без оформления; быстрый сбой повторяем один раз
                        long c0 = System.currentTimeMillis();
                        String c;
                        try { c = Api.cleanup(key, text, cfg, mem).trim(); }
                        catch (java.io.IOException first) {
                            if (System.currentTimeMillis() - c0 > 3000 || errCode(first).startsWith("HTTP 4")) throw first; // 4xx — не сбой сети
                            Log.e("cleanup: " + errCode(first) + " — повтор");
                            c = Api.cleanup(key, text, cfg, mem).trim();
                        }
                        String problem = c.isEmpty() ? "пусто" : Text.llmOutputProblem(text, c, cfg);
                        if (problem == null) problem = Text.cleanupRewriteProblem(text, c, cfg, mem);
                        if (problem == null) { text = c; llmUsed = true; } else Log.e("cleanup отброшена: " + problem);
                    } catch (Exception ex) { Log.e("cleanup: " + errCode(ex)); } // без тела ответа: в нём мог быть текст
                }
                // Замены ДО перевода: в них «John → Джон», после перевода вернули бы кириллицу
                text = Text.insertLinks(Text.applyReplacements(text, cfg), translating, cfg);
                if (translating) {
                    r.fallback = text;
                    // «Да», «Окей», «Спасибо» — таблицей, мгновенно
                    String quick = Text.quickTranslation(text, target);
                    if (quick != null) Log.i("translate: короткая фраза без LLM");
                    String out = quick != null ? quick : target.equals("vi") ? Api.translateVi(key, text, cfg, mem)
                            : target.equals("tr") ? Api.translateTr(key, text, cfg, mem)
                            : target.equals("fr") ? Api.translateFr(key, text, cfg, mem) : Api.translate(key, text, cfg, mem);
                    String problem = Text.llmOutputProblem(text, out, cfg);
                    if (out.isEmpty() || Text.mostlyCyrillic(out)) r.error = "⚠️ Перевод не удался — русский текст в буфере";
                    else if (problem != null) { Log.e("translate rejected: " + problem); r.error = "⚠️ Подозрительный перевод (" + problem + ") — русский в буфере"; }
                    else { r.result = out; r.lang = target; }
                } else {
                    r.result = text;
                    r.lang = "ru";
                }
            }
        } catch (Throwable ex) { // и Error: иначе вызывающий не узнает о конце и состояние залипнет
            Log.e("process: " + ex);
            if (ex.getMessage() != null && (ex.getMessage().contains("HTTP 401") || ex.getMessage().contains("HTTP 403"))) store.resetKey();
            r.error = "⚠️ " + (ex.getMessage() == null ? "ошибка сети" : ex.getMessage());
        }
        r.secs = (System.currentTimeMillis() - t0) / 1000.0;
        Log.i(String.format("done %s %s %.1f s, audio %.1f s", source, mode, r.secs, Recorder.seconds(pcm)));
        boolean llm = r.result != null && (translating || mode.equals("talk-ru") || llmUsed);
        if (!raw.isEmpty() && !incognito)
            store.addHistory(r.result != null || !llm && !translating ? mode : mode + "-failed", raw, translating && !mode.equals("talk-ru") ? r.fallback : null,
                    r.result != null ? r.result : r.fallback, llm, r.secs, Recorder.seconds(pcm), historyDays, how[0], sttSecs);
        return r;
    }

    /** Буфер обмена без превью и без истории буфера (Android 13+) */
    static void toClipboard(android.content.Context ctx, String t) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        android.content.ClipData clip = android.content.ClipData.newPlainText("Dictate", t);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
    }

    private Pipeline() {}

    /** Для лога: «HTTP 503» или имя исключения — тело ответа не пишем, в нём мог быть текст диктовки */
    static String errCode(Exception e) {
        String m = e.getMessage();
        java.util.regex.Matcher h = m == null ? null : java.util.regex.Pattern.compile("^HTTP \\d{3}").matcher(m);
        return h != null && h.find() ? h.group() : e.getClass().getSimpleName();
    }
}
