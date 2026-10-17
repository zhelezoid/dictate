package app.dictate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Сразу после обновления (MY_PACKAGE_REPLACED): самопроверка Text на телефоне и отчёт на сервер — видно без logcat.
 *  Переключение на Gboard здесь было и убрано (13.10): после установки через PackageInstaller Dictate не «сворачивается»
 *  (это беда только adb install -r, её лечит build.sh), а Android 14+ всё равно не дал приложению сменить клавиатуру */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Store store = new Store(ctx);
        int v = Updater.versionCode(ctx);
        store.prefs().edit().putInt("reportedV", v).apply(); // второй отчёт из checkAsync не нужен
        if (store.prefs().getInt("silentV", 0) == v) Updater.notifySilent(ctx, v);
        String check = SettingsActivity.selfCheck(store.config());
        Log.i("updated to " + Updater.versionCode(ctx));
        // goAsync: без него процесс могут убить сразу после onReceive, и отчёт не уйдёт
        PendingResult pr = goAsync();
        Updater.reportAsync(ctx, check == null ? "ok" : check, pr::finish);
    }
}
