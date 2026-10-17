package app.dictate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Настройка и состояние: микрофон, включить и выбрать клавиатуру, ключ, память и справочник с Mac, проба.
 *  Вёрстка кодом, как и клавиатура (без XML-разметки и AndroidX); тема — светлая/тёмная по системе. */
public class SettingsActivity extends Activity {
    // Палитра — общая с клавиатурой (Ui.P, 13.10.2026: чёрный/жёлтый). accent — для текста-ссылок (жёлтый на светлом не читается)
    private int bg, card, text, text2, accent, green, red, line;
    private Ui.P p;
    private LinearLayout dynamic;
    private Store store;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(this);
        if (getActionBar() != null) getActionBar().hide();
        store = new Store(this);
        p = new Ui.P(this);
        boolean night = p.dark;
        bg = p.bg; card = p.surface; text = p.text; text2 = p.text2; line = p.line;
        accent = night ? Ui.YELLOW : Ui.INK; green = Ui.GREEN; red = Ui.RED;
        getWindow().getDecorView().setBackgroundColor(bg);
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(night ? 0 : light, light);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));

        TextView title = label("Dictate", 34, text, true);
        root.addView(title);
        TextView sub = label("Голосовая клавиатура: говоришь по-русски — вставляется по-русски, по-английски, по-вьетнамски, по-турецки или по-французски", 15, text2, false);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        Ui.Key tr = new Ui.Key(this, "🗣  Переводчик", Ui.YELLOW, p, 18);
        tr.setTextSize(19);
        tr.setOnClickListener(v -> startActivity(new Intent(this, TranslatorActivity.class)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        tlp.bottomMargin = dp(16);
        root.addView(tr, tlp);

        // Плашка, шаги настройки и состояние — перестраиваются в refresh(), поле «Проба» ниже — нет (не терять набранное)
        dynamic = new LinearLayout(this);
        dynamic.setOrientation(LinearLayout.VERTICAL);
        root.addView(dynamic);

        root.addView(header("Как пользоваться"));
        LinearLayout how = card();
        String[][] tips = {
                {"🎙", "Держи большую кнопку и говори. Отпустил — текст вставлен. Повернул телефон посреди фразы — запись идёт дальше, тап — стоп"},
                {"EN", "Кнопка режима по кругу: RU — как сказано, RU→EN — на английский, RU→VI — на вьетнамский, RU→TR — на турецкий, RU→FR — на французский"},
                {"123", "Цифры и знаки + − % прямо в Dictate"},
                {"🕘", "Последние 10 диктовок: тап — вставить, долгий тап — скопировать"},
                {"⏳", "Нет интернета — запись сохраняется и распознаётся сама, когда сеть появится; придёт уведомление"},
                {"◉", "Плавающая кнопка — голосовой переводчик: держи и говори — перевод вслух; собеседник держит её же и говорит на своём. Тап — EN/VI/TR/FR, повёл — двигается, к нижнему краю — убрать"},
                {"🗣", "Переводчик: держи кнопку и говори; собеседник жмёт ту же кнопку и говорит на своём. Боковая кнопка без поля ввода открывает его сама"},
                {"⌫", "Держи, чтобы стирать подряд"},
                {"🌐", "Назад к обычной клавиатуре; долгое нажатие — выбор клавиатуры"},
                {"↺", "Долгое нажатие на строку состояния — вставить последнюю диктовку ещё раз"}};
        for (int i = 0; i < tips.length; i++) {
            if (i > 0) how.addView(divider());
            how.addView(tip(tips[i][0], tips[i][1]));
        }
        root.addView(how);

        root.addView(header("Проба"));
        LinearLayout tryCard = card();
        tryCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        EditText test = new EditText(this);
        test.setHint("Тапни сюда, выбери Dictate и надиктуй что-нибудь");
        test.setHintTextColor(text2);
        test.setTextColor(text);
        test.setTextSize(16);
        test.setMinLines(3);
        test.setGravity(Gravity.TOP | Gravity.START);
        test.setBackground(round(bg, 14));
        test.setPadding(dp(12), dp(10), dp(12), dp(10));
        tryCard.addView(test);
        root.addView(tryCard);

        TextView clear = label("Очистить историю диктовок", 16, red, false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(0, dp(14), 0, dp(14));
        clear.setBackground(Ui.card(this, p));
        clear.setOnClickListener(v -> confirmClear());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(24);
        root.addView(clear, clp);

        TextView foot = label("Dictate " + version() + (Updater.BASE.isEmpty() ? " · словарь и память — файлы config.json и memory.md (handoff/push-data.sh)"
                : " · словарь, память, ключ и обновления приходят с Mac через сервер; история — на Mac по проводу (sync.sh)"), 13, text2, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(16), 0, 0);
        root.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        // targetSdk 36 — окно от края до края: отступы под строку состояния, панель навигации и клавиатуру (поле «Проба»)
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        Updater.checkAsync(this, false, () -> { if (!isDestroyed()) refresh(); });
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refresh();
    }

    // Окно выбора клавиатуры не вызывает onResume — смена выбора видна при возврате фокуса
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh();
    }

    private void refresh() {
        if (dynamic == null) return;
        dynamic.removeAllViews();
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled = false;
        String currentId = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String currentName = null;
        if (imm != null) for (InputMethodInfo m : imm.getEnabledInputMethodList()) {
            if (m.getPackageName().equals(getPackageName())) enabled = true;
            if (m.getId().equals(currentId)) currentName = m.loadLabel(getPackageManager()).toString();
        }
        boolean selected = currentId != null && currentId.startsWith(getPackageName() + "/");
        boolean key = store.apiKey() != null;
        Store.Memory mem = store.memory();
        Store.Config cfg = store.config();
        String check = selfCheck(cfg);

        // Готовность — без «выбрана сейчас»: пользователь сам переключает Gboard ↔ Dictate кнопкой на панели навигации
        boolean ready = mic && enabled && key && check == null;
        TextView banner = label(ready ? "✓  Dictate готова к работе"
                : "Осталось: " + TextUtils.join(", ", missing(mic, enabled, key, check)), 16, ready ? green : Ui.INK, true);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackground(round(ready ? (green & 0x00FFFFFF) | 0x26000000 : Ui.YELLOW, 16));
        dynamic.addView(banner);

        dynamic.addView(header("Настройка"));
        LinearLayout steps = card();
        steps.addView(step(1, mic, "Микрофон", mic ? "Разрешён" : "Нужен, чтобы слышать речь", "Разрешить",
                () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1)));
        steps.addView(divider());
        steps.addView(step(2, enabled, "Клавиатура включена", enabled ? "Dictate есть в списке клавиатур" : "Включи Dictate в списке клавиатур", "Открыть",
                () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        steps.addView(divider());
        steps.addView(step(3, selected, "Выбрать Dictate", selected ? "Выбрана сейчас"
                        : "Сейчас: " + (currentName != null ? currentName : "другая"), "Выбрать",
                () -> { if (imm != null) imm.showInputMethodPicker(); }));
        dynamic.addView(steps);

        // Боковая кнопка (13.10.2026): долгое нажатие — вкл/выкл записи вместо Gemini. Необязательная часть — в плашку готовности не входит
        android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
        boolean assistant = rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
        String a11yList = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean a11y = a11yList != null && a11yList.contains(getPackageName() + "/");
        dynamic.addView(header("Плавающая кнопка"));
        LinearLayout side = card();
        side.addView(step(4, a11y, "Вставка в поле", a11y ? "Текст вставляется туда, где курсор"
                        : "Спец. возможности → Установленные приложения → Dictate. Серое — Приложения → Dictate → ⋮ → «Разрешить ограниченные настройки». Без этого — только в буфер", "Открыть",
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        side.addView(divider());
        boolean fl = DictateA11y.floatEnabled(this);
        View frow = row("Плавающая кнопка", !a11y ? "нужна «Вставка в поле»" : fl ? "включена · тап — убрать" : "выключена · тап — показать", !a11y ? text2 : fl ? green : text2);
        frow.setOnClickListener(v -> { DictateA11y.setFloatEnabled(this, !DictateA11y.floatEnabled(this)); refresh(); });
        side.addView(frow);
        side.addView(divider());
        // Боковая кнопка — необязательна: у пользователь её держит хаб (16.10.2026), поэтому не красный шаг, а справка
        View brow = row("Боковая кнопка", assistant ? "у Dictate · долгое нажатие — говори, замолчишь на 4 с — вставлю"
                : "у другого помощника — Dictate не мешает · тап — выбрать", assistant ? green : text2);
        brow.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)));
        side.addView(brow);
        dynamic.addView(side);

        dynamic.addView(header("Состояние"));
        LinearLayout st = card();
        // Тап — вставить свой ключ (14.10.2026: без Mac и sync.sh — для чистой копии приложения, и запасной путь)
        View krow = row("Ключ xAI", key ? "есть · тап — заменить" : "нет · тап — вставить", key ? green : red);
        krow.setOnClickListener(v -> askKey());
        st.addView(krow);
        st.addView(divider());
        st.addView(row("Проверка текста", check == null ? "работает" : check, check == null ? green : red));
        st.addView(divider());
        st.addView(row("Словарь", plural(cfg.dictionary.size(), "термин", "термина", "терминов") + " · " + plural(cfg.replacements.size(), "замена", "замены", "замен"), text2));
        st.addView(divider());
        st.addView(row("Память", plural(mem.terms.size(), "термин", "термина", "терминов") + " · " + plural(mem.facts.size(), "факт", "факта", "фактов"), text2));
        st.addView(divider());
        st.addView(row("Контакты CRM", plural(mem.contacts.size(), "контакт", "контакта", "контактов"), text2));
        st.addView(divider());
        st.addView(row("История", plural(store.historyCount(), "диктовка", "диктовки", "диктовок") + " · " + cfg.historyDays + " дн.", text2));
        int waiting = Pending.count(this);
        if (waiting > 0) { st.addView(divider()); st.addView(row("Ждут сети", plural(waiting, "запись", "записи", "записей"), accent)); }
        dynamic.addView(st);

        // Обновления без провода (Updater): новая версия и словарь/память/CRM с Mac через сервер
        dynamic.addView(header("Обновления"));
        LinearLayout up = card();
        boolean token = store.hasUpdateToken();
        int readyV = Updater.readyVersion(this);
        long at = store.prefs().getLong("updCheckedAt", 0);
        String err = store.prefs().getString("updError", null);
        String when = at == 0 ? "ещё не проверялось" : "проверено " + android.text.format.DateFormat.format("d.MM HH:mm", at)
                + " · " + store.prefs().getString("updResult", "нового нет");
        up.addView(row("Версия", version() + " (" + Updater.versionCode(this) + ")", text2));
        up.addView(divider());
        if (Updater.BASE.isEmpty()) up.addView(row("Сервер", "не настроен — обновления выключены", text2));
        else if (!token) up.addView(row("Сервер", "нет токена — подключи к Mac, sync.sh", red));
        else if (readyV > 0) {
            TextView b = label("Установить версию " + store.prefs().getString("updReadyName", String.valueOf(readyV)), 16, accent, true);
            b.setPadding(dp(16), dp(14), dp(16), dp(14));
            b.setOnClickListener(v -> startActivity(new Intent(this, UpdateActivity.class).setAction(UpdateActivity.INSTALL)));
            up.addView(b);
        } else {
            View r = row("Проверить сейчас", err != null ? "⚠️ " + err : when, err != null ? red : text2);
            r.setOnClickListener(v -> { ((TextView) ((LinearLayout) v).getChildAt(1)).setText("проверяю…"); Updater.checkAsync(this, true, () -> { if (!isDestroyed()) refresh(); }); });
            up.addView(r);
        }
        dynamic.addView(up);
    }

    /** Ключ xAI (console.x.ai → API Keys): сразу в Keystore, на экран и в лог не выводится */
    private void askKey() {
        EditText in = new EditText(this);
        in.setHint("xai-…");
        in.setSingleLine(true);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Ключ xAI")
                .setMessage("console.x.ai → API Keys → Create. Вставь ключ целиком.")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String k = in.getText().toString().trim();
                    if (!k.matches("[A-Za-z0-9_-]{20,200}")) { android.widget.Toast.makeText(this, "Не похоже на ключ", android.widget.Toast.LENGTH_SHORT).show(); return; }
                    try { store.putApiKey(k); } catch (Exception e) { Log.e("put key: " + e.getClass().getSimpleName()); }
                    refresh();
                })
                .show();
    }

    /** Самопроверка чистки на самом телефоне: 11.10.2026 Text.java падал только на Android (флаг регулярок,
     *  которого нет в ICU), а проверка обычной Java на Mac это пропустила. null — всё хорошо, иначе — что не так */
    static String selfCheck(Store.Config cfg) {
        try {
            String a = Text.fixImperatives(Text.localClean("Эм, иван собака example.com, а, но за г. делаю"));
            String b = Text.insertLinks(Text.applyReplacements("Вот, держи ссылку на MyShop", cfg), true, cfg);
            String c = Text.applyReplacements(Text.localClean("123456, собака, gmail com. 123 4567 Gmail com. Пиши в Gmail com"), cfg);
            // Регулярки оформления (13.10) — на ICU телефона, не только на JVM Mac
            boolean fmt = Text.sentenceCount("Проверь отчёт. Потом напиши, т.е. сразу.") == 2 && !Text.needsLLM("Скорость 1.5 раза")
                    && Text.hasSelfCorrection("нет, точнее завтра");
            boolean ok = a.equals("ivan@example.com, но за год делай") && (cfg.links.isEmpty() || b.contains("https://")) && fmt;
            Log.i("selfcheck " + (ok ? "ok" : "FAIL") + ": " + a + " | " + b + " | " + c);
            return ok ? null : !fmt ? "ошибка: оформление" : "ошибка: «" + a + "»";
        } catch (Throwable t) {
            Log.e("selfcheck crash: " + t + (t.getCause() != null ? " cause: " + t.getCause() : ""));
            return "сломана — " + t.getClass().getSimpleName();
        }
    }

    private java.util.List<String> missing(boolean mic, boolean enabled, boolean key, String check) {
        java.util.List<String> m = new java.util.ArrayList<>();
        if (!mic) m.add("микрофон");
        if (!enabled) m.add("включить клавиатуру");
        if (!key) m.add("ключ xAI");
        if (check != null) m.add("проверка текста");
        return m;
    }

    private void confirmClear() {
        int n = store.historyCount();
        new AlertDialog.Builder(this)
                .setTitle("Очистить историю?")
                .setMessage("С телефона удалятся " + plural(n, "диктовка", "диктовки", "диктовок") + ". Копия на Mac останется до следующей синхронизации.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (d, w) -> { store.clearHistory(); refresh(); })
                .show();
    }

    // MARK: элементы

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.card(this, p));
        return c;
    }

    private TextView header(String s) {
        TextView t = label(s.toUpperCase(), 13, text2, true);
        t.setLetterSpacing(0.04f);
        t.setPadding(dp(16), dp(24), dp(16), dp(8));
        return t;
    }

    private View step(int n, boolean done, String title, String desc, String action, Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView badge = label(done ? "✓" : String.valueOf(n), 15, done ? 0xFFFFFFFF : text, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable g = round(done ? green : 0, 14);
        if (!done) g.setStroke(dp(2), text);
        badge.setBackground(g);
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(10), 0);
        col.addView(label(title, 17, text, false));
        TextView d = label(desc, 14, text2, false);
        d.setPadding(0, dp(2), 0, 0);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!done) {
            Ui.Key b = new Ui.Key(this, action, Ui.YELLOW, p, 14);
            b.setTextSize(15);
            b.setPadding(dp(16), 0, dp(16), dp(4));
            b.setOnClickListener(v -> r.run());
            row.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
            row.setOnClickListener(v -> r.run());
        } else if (n == 3 || n == 4) {
            // Выбрана — всё равно даём сменить (на Gboard и обратно)
            TextView b = label("Сменить", 15, accent, false);
            b.setPadding(dp(8), dp(8), 0, dp(8));
            b.setOnClickListener(v -> r.run());
            row.addView(b);
        }
        return row;
    }

    private View row(String name, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        TextView n = label(name, 16, text, false);
        n.setSingleLine(true);
        row.addView(n);
        TextView v = label(value, 15, valueColor, false);
        v.setGravity(Gravity.END);
        v.setPadding(dp(12), 0, 0, 0);
        row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View tip(String icon, String s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView i = label(icon, 14, Ui.INK, true);
        i.setGravity(Gravity.CENTER);
        i.setBackground(round(Ui.YELLOW, 8));
        row.addView(i, new LinearLayout.LayoutParams(dp(32), dp(28)));
        TextView t = label(s, 15, text, false);
        t.setPadding(dp(14), dp(3), 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** 1 диктовка, 3 диктовки, 5 диктовок */
    static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? one : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? few : many;
        return n + " " + w;
    }
}
