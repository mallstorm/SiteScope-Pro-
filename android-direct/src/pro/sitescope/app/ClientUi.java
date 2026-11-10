package pro.sitescope.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact UI helpers shared by native protocol clients. */
public final class ClientUi {
    public static final int NAVY = Color.rgb(22, 32, 51);
    public static final int MUTED = Color.rgb(104, 116, 137);
    public static final int PRIMARY = Color.rgb(13, 99, 229);
    public static final int PRIMARY_SOFT = Color.rgb(231, 240, 255);
    public static final int GREEN = Color.rgb(20, 145, 93);
    public static final int RED = Color.rgb(205, 50, 63);
    public static final int BORDER = Color.rgb(222, 229, 239);
    public static final int SURFACE = Color.rgb(248, 243, 235);

    private ClientUi() { }

    public static LinearLayout root(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(Color.WHITE);
        return v;
    }

    public static LinearLayout page(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 28));
        return v;
    }

    public static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    public static LinearLayout header(final android.app.Activity a, String title, String subtitle) {
        LinearLayout h = row(a);
        h.setPadding(dp(a, 12), dp(a, 10), dp(a, 14), dp(a, 10));
        TextView back = text(a, "‹", 30, PRIMARY, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { a.finish(); } });
        h.addView(back, new LinearLayout.LayoutParams(dp(a, 42), dp(a, 42)));
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(a, title, 21, NAVY, true));
        t.addView(text(a, subtitle, 12, MUTED, false));
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return h;
    }

    public static LinearLayout card(Context c, int padding) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c,padding), dp(c,padding), dp(c,padding), dp(c,padding));
        v.setBackground(round(c, SURFACE, 17, BORDER, 1));
        v.setElevation(dp(c,2));
        return v;
    }

    public static TextView text(Context c, String value, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.08f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(NAVY);
        e.setHintTextColor(Color.rgb(145,155,171));
        e.setSingleLine(true);
        e.setPadding(dp(c,12), 0, dp(c,12), 0);
        e.setBackground(round(c, Color.WHITE, 12, BORDER, 1));
        return e;
    }

    public static TextView button(Context c, String title, boolean primary) {
        TextView b = text(c, title, 14, primary ? Color.WHITE : PRIMARY, true);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(c,10), 0, dp(c,10), 0);
        b.setBackground(round(c, primary ? PRIMARY : Color.WHITE, 12, primary ? Color.TRANSPARENT : BORDER, primary ? 0 : 1));
        return b;
    }

    public static GradientDrawable round(Context c, int color, int radius, int stroke, int width) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c,radius));
        if (width > 0 && stroke != Color.TRANSPARENT) d.setStroke(dp(c,width), stroke);
        return d;
    }

    public static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout.LayoutParams margins(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,h);
        p.setMargins(dp(c,l),dp(c,t),dp(c,r),dp(c,b));
        return p;
    }
}
