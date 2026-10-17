package app.dictate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Итог тихой установки (Updater.autoInstall). Успех сюда обычно не доходит — установка убивает процесс */
public class InstallStatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_SUCCESS) return;
        // Система всё же хочет подтверждения — окно из фона не открыть: обычное уведомление «Нажми, чтобы установить»
        String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        // Сессию, ждущую подтверждения, бросаем: по тапу на уведомление создастся новая
        int id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (id >= 0) try { ctx.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Exception ignored) {}
        Updater.autoFailed(ctx, i.getIntExtra("v", 0), st == PackageInstaller.STATUS_PENDING_USER_ACTION ? "needs-user" : "status " + st + " " + (m == null ? "-" : m));
    }
}
