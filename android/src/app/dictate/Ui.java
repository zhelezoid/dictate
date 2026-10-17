package app.dictate;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.TextView;

/** Дизайн (13.10.2026, по референсам Refero: Cassette — чёрный/жёлтый, Google Translate — разговор карточками).
 *  Жёлтый — только действие и активное состояние, не украшение. Кнопки объёмные: под лицевой стороной тёмная «губа»,
 *  при нажатии лицо опускается на неё. Тема — светлая/тёмная по системе. */
final class Ui {
    static final int YELLOW = 0xFFF0E226, INK = 0xFF19181A, RED = 0xFFFF4A3D, ORANGE = 0xFFFF8461, TEAL = 0xFF3CC4C6, BLUE = 0xFF8AA2FF, GREEN = 0xFF0DB55B;

    /** Цвета темы. Создавать заново при каждом построении экрана: тема может смениться на лету */
    static final class P {
        final boolean dark;
        final int bg, surface, text, text2, line, keyFace, keyLip, yellowLip, redLip, orangeLip, tealLip, blueLip;
        P(Context c) {
            dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            bg = dark ? 0xFF19181A : 0xFFF5F4F0;
            surface = dark ? 0xFF242326 : 0xFFFFFFFF;
            text = dark ? 0xFFF5F4F0 : INK;
            text2 = dark ? 0xFFA09E96 : 0xFF6B6A66;
            line = dark ? 0xFF2E2D31 : 0xFFE6E4DC;
            keyFace = dark ? 0xFF34333A : 0xFFFFFFFF;
            keyLip = dark ? 0xFF0B0B0C : 0xFFCFCDC3;
            yellowLip = dark ? 0xFF8F8710 : 0xFFB3A80C;
            redLip = 0xFFA8241A;
            orangeLip = 0xFFB5502F;
            tealLip = 0xFF207F81;
            blueLip = 0xFF4F63B8;
        }
        /** Губа под цветом: для жёлтого/красного/оранжевого — своя, иначе — общая */
        int lipFor(int face) {
            return face == YELLOW ? yellowLip : face == RED ? redLip : face == ORANGE ? orangeLip : face == TEAL ? tealLip : face == BLUE ? blueLip : keyLip;
        }
        /** Текст на цветном фоне: на жёлтом и оранжевом — чёрный, на красном — белый */
        int onColor(int face) {
            return face == YELLOW || face == ORANGE || face == TEAL || face == BLUE ? INK : face == RED ? 0xFFFFFFFF : text;
        }
    }

    /** Цвет режима / языка перевода: EN — жёлтый, VI — оранжевый, TR — бирюзовый (14.10.2026), FR — голубой (17.10), RU — обычная кнопка */
    static int targetColor(String t, P p) {
        return t.equals("en") ? YELLOW : t.equals("vi") ? ORANGE : t.equals("tr") ? TEAL : t.equals("fr") ? BLUE : p.keyFace;
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Карточка: поверхность чуть светлее фона, тонкая обводка вместо тени */
    static GradientDrawable card(Context c, P p) { return card(c, p, 20); }

    static GradientDrawable card(Context c, P p, float radiusDp) {
        GradientDrawable d = round(p.surface, dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), p.line);
        return d;
    }

    static TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    /** Объёмная кнопка. Лицо + губа рисуются сами (без фоновой картинки); нажатие — лицо опускается, лёгкая отдача */
    static final class Key extends TextView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int face, lip;
        private final float radius, depth;

        Key(Context c, String text, int face, P p, float radiusDp) {
            super(c);
            this.radius = dp(c, radiusDp);
            this.depth = dp(c, 4);
            setText(text);
            setGravity(Gravity.CENTER);
            setTextSize(18);
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setSingleLine(false);
            setColors(face, p);
            setClickable(true);
            setHapticFeedbackEnabled(true);
            // Текст по центру лица, а не всей кнопки: снизу — место под губу
            setPadding(dp(c, 6), 0, dp(c, 6), (int) depth);
            setBackground(null);
        }

        void setColors(int face, P p) {
            this.face = face;
            this.lip = p.lipFor(face);
            setTextColor(p.onColor(face));
            invalidate();
        }

        @Override public void setPressed(boolean pressed) {
            if (pressed && !isPressed()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            super.setPressed(pressed);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float down = isPressed() ? depth * 0.75f : 0;
            // Холст уже сдвинут на прокрутку TextView: у однострочной кнопки (режим RU/EN/VI) она ненулевая, и лицо
            // уезжало за край — 14.10.2026 в тёмной теме чёрная надпись «EN» осталась без жёлтой подложки
            float sx = getScrollX(), sy = getScrollY();
            paint.setColor(lip);
            r.set(sx, sy + depth, sx + w, sy + h);
            canvas.drawRoundRect(r, radius, radius, paint);
            paint.setColor(face);
            r.set(sx, sy + down, sx + w, sy + h - depth + down);
            canvas.drawRoundRect(r, radius, radius, paint);
            canvas.save();
            canvas.translate(0, down);
            super.onDraw(canvas);
            canvas.restore();
        }
    }
}
