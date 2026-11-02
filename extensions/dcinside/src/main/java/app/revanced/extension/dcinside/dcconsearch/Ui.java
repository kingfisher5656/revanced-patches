package app.revanced.extension.dcinside.dcconsearch;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Small view helpers. Only framework widgets are used: the app's AndroidX classes are obfuscated.
 */
final class Ui {
    static final int ORANGE = 0xFFF0A020;
    static final int NAVY = 0xFF3B4890;

    private Ui() {
    }

    static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }

    static int themeColor(Context ctx, int attr, int fallback) {
        TypedArray a = ctx.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } catch (Exception ex) {
            return fallback;
        } finally {
            a.recycle();
        }
    }

    static int background(Context ctx) {
        return themeColor(ctx, android.R.attr.colorBackground, 0xFFFFFFFF);
    }

    static int text(Context ctx) {
        return themeColor(ctx, android.R.attr.textColorPrimary, 0xFF222222);
    }

    static int secondaryText(Context ctx) {
        return themeColor(ctx, android.R.attr.textColorSecondary, 0xFF888888);
    }

    static int accent(Context ctx) {
        return themeColor(ctx, android.R.attr.colorAccent, NAVY);
    }

    /** A faint fill that works on both light and dark backgrounds. */
    static int faint(Context ctx) {
        return (text(ctx) & 0x00FFFFFF) | 0x14000000;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static TextView label(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(secondaryText(ctx));
        t.setPadding(0, dp(ctx, 10), 0, dp(ctx, 4));
        return t;
    }

    static EditText field(Context ctx, String value, String hint, int inputType) {
        EditText e = new EditText(ctx);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        e.setInputType(inputType);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(text(ctx));
        e.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return e;
    }
}
