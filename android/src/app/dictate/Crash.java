package app.dictate;

import android.content.Context;

/** Падение → на сервер обновлений класс ошибки и строки нашего кода (без message: в нём мог быть текст). 14.10.2026:
 *  клавиатура пропала после обновления, а телефон без провода — иначе причину не увидеть */
final class Crash {
    private static volatile boolean installed;

    static void install(Context c) {
        if (installed) return;
        installed = true;
        Context ctx = c.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringBuilder b = new StringBuilder("crash ").append(e.getClass().getSimpleName());
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                if (root != e) b.append(" <- ").append(root.getClass().getSimpleName());
                // SecurityException — текст от системы (какое разрешение/вызов), пользовательского текста в нём нет
                if (e instanceof SecurityException && e.getMessage() != null)
                    b.append(" «").append(e.getMessage().replaceAll("\\s+", " "), 0, Math.min(e.getMessage().length(), 160)).append("»");
                // Стек — внешнего исключения: у RemoteException из системы он пустой. Первые 4 кадра любые, дальше — наши
                int n = 0, any = 0;
                for (StackTraceElement f : e.getStackTrace()) {
                    if (n >= 10) break;
                    if (any++ < 4 || f.getClassName().startsWith("app.dictate")) {
                        b.append(" ").append(f.getClassName().replace("app.dictate.", "")).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
                        n++;
                    }
                }
                Log.e(b.toString());
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                Updater.reportAsync(ctx, b.toString(), done::countDown);
                done.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
