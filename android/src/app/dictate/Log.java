package app.dictate;

/** Только события и ошибки — текст диктовки в лог не пишем (logcat видят другие инструменты с adb) */
final class Log {
    private Log() {}
    static void i(String m) { android.util.Log.i("Dictate", m); }
    static void e(String m) { android.util.Log.e("Dictate", m); }
}
