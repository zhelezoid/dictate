package app.dictate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Тап по уведомлению «Распознал запись без сети» — текст в буфер */
public class CopyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String t = i.getStringExtra("text");
        if (t == null) return;
        Pipeline.toClipboard(c, t);
        Toast.makeText(c, "📋 Скопировано", Toast.LENGTH_SHORT).show();
    }
}
