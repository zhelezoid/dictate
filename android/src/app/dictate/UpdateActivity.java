package app.dictate;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Окно «Устанавливаю обновление…» — по тапу на уведомление Updater. Держится на экране, пока идёт установка:
 *  системное «Обновить?» Android показывает только приложению на переднем плане (из фона — запрет запуска окон) */
public class UpdateActivity extends Activity {
    static final String INSTALL = "app.dictate.INSTALL";
    private static final String STATUS = "app.dictate.INSTALL_STATUS";
    private TextView msg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        msg = new TextView(this);
        msg.setTextSize(17);
        msg.setGravity(Gravity.CENTER);
        int p = Math.round(24 * getResources().getDisplayMetrics().density);
        msg.setPadding(p, p, p, p);
        msg.setText("Устанавливаю обновление…");
        msg.setOnClickListener(v -> finish());
        setContentView(msg);
        // Пересоздание окна (поворот, смена темы) не должно запускать установку второй раз
        if (b == null) handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handle(i); }

    private void handle(Intent i) {
        if (i == null) return;
        if (INSTALL.equals(i.getAction())) {
            // После установки система заново открывает это окно с тем же intent — версия уже новая
            if (Updater.readyVersion(this) == 0) { msg.setText("✓ Установлена версия " + Updater.versionCode(this) + "\n\nТап — закрыть"); return; }
            // MUTABLE: установщик дописывает в этот intent статус; адресат явный — только это окно
            PendingIntent status = PendingIntent.getActivity(this, 1, new Intent(this, UpdateActivity.class).setAction(STATUS),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            new Thread(() -> {
                try { Updater.install(this, status); }
                catch (Exception e) { Log.e("update install: " + e); runOnUiThread(() -> msg.setText("⚠️ Не удалось установить: " + e.getMessage())); }
            }, "dictate-install").start();
        } else if (STATUS.equals(i.getAction())) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation") // форма с классом — только с API 33, а minSdk 30
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm);
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                finish(); // обычно сюда не доходим: процесс убивается установкой
            } else {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.e("update install status " + st + ": " + m);
                if (st == PackageInstaller.STATUS_FAILURE_ABORTED) finish();
                else if (new Store(this).prefs().getInt("updReady", 0) <= Updater.versionCode(this)) msg.setText("✓ Обновлено до " + Updater.versionCode(this) + "\n\nТап — закрыть");
                else msg.setText("⚠️ Обновление не встало: " + (m == null ? "код " + st : m) + "\n\nТап — закрыть");
            }
        }
    }
}
