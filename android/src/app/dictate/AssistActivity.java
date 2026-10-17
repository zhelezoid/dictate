package app.dictate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Вход «цифрового помощника» — долгое нажатие боковой кнопки Samsung (вместо Gemini, выбор 13.10.2026).
 *  Невидимое окно: переключает запись в VoiceService и сразу закрывается, чтобы фокус вернулся в поле ввода. */
public class AssistActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Плавающая кнопка — через закрытый вход FloatHold. Открытый вход — только «цифровой помощник» и только пока помощник —
        // Dictate (16.10.2026 кнопку забрал хаб): иначе любое приложение могло бы включить микрофон, открыв это окно
        boolean fromFloat = getIntent().getComponent() != null && getIntent().getComponent().getClassName().endsWith(".FloatHold");
        android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
        boolean assistant = rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
        if (!fromFloat && !(Intent.ACTION_ASSIST.equals(getIntent().getAction()) && assistant)) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            // Пока окно на экране, приложение «на переднем плане» — только в этот момент Android разрешает
            // запустить службу с микрофоном
            // Плавающая кнопка (DictateA11y) приходит сюда же с HOLD_START: из фона службу с микрофоном не запустить
            String action = fromFloat ? VoiceService.HOLD_START : VoiceService.TOGGLE;
            startForegroundService(new Intent(this, VoiceService.class).setAction(action));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
