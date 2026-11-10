package pro.sitescope.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UiKit {
    static final int NAVY = Color.rgb(22,32,51);
    static final int MUTED = Color.rgb(104,116,137);
    static final int PRIMARY = Color.rgb(13,99,229);
    static final int PRIMARY_SOFT = Color.rgb(231,240,255);
    static final int GREEN = Color.rgb(20,145,93);
    static final int GREEN_SOFT = Color.rgb(228,247,238);
    static final int AMBER = Color.rgb(192,116,8);
    static final int AMBER_SOFT = Color.rgb(255,245,222);
    static final int RED = Color.rgb(205,50,63);
    static final int RED_SOFT = Color.rgb(255,234,236);
    static final int BORDER = Color.rgb(222,229,239);
    static final int SURFACE = Color.rgb(248,243,235);

    private UiKit() { }

    static int dp(Activity a, int value) { return Math.round(value * a.getResources().getDisplayMetrics().density); }

    static TextView text(Activity a, String value, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    static GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp, Activity a) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(a, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(a, strokeDp), stroke);
        return d;
    }

    static LinearLayout page(Activity a) {
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(a,18), dp(a,18), dp(a,18), dp(a,24));
        p.setBackgroundColor(Color.WHITE);
        return p;
    }

    static LinearLayout header(final Activity a, String icon, String title, String subtitle) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(a, "‹", 32, PRIMARY, true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(Color.WHITE, 16, BORDER, 1, a));
        back.setOnClickListener(new android.view.View.OnClickListener(){ @Override public void onClick(android.view.View v){ a.finish(); } });
        row.addView(back, new LinearLayout.LayoutParams(dp(a,52), dp(a,52)));
        TextView logo = text(a, icon, 24, NAVY, false);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(PRIMARY_SOFT, 15, Color.TRANSPARENT, 0, a));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a,52), dp(a,52)); lp.leftMargin = dp(a,10);
        row.addView(logo, lp);
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(a,12),0,0,0);
        col.addView(text(a,title,22,NAVY,true)); col.addView(text(a,subtitle,12,MUTED,false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    static LinearLayout card(Activity a, int padding) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(a,padding),dp(a,padding),dp(a,padding),dp(a,padding));
        c.setBackground(round(SURFACE,18,BORDER,1,a));
        c.setElevation(dp(a,2));
        return c;
    }

    static TextView button(Activity a, String title, boolean primary) {
        TextView b = text(a,title,14,primary ? Color.WHITE : PRIMARY,true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(primary ? PRIMARY : Color.WHITE,14,primary ? PRIMARY : BORDER,1,a));
        b.setPadding(dp(a,12),0,dp(a,12),0);
        return b;
    }

    static EditText input(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint); e.setTextSize(15); e.setTextColor(NAVY); e.setHintTextColor(Color.rgb(145,155,170));
        e.setSingleLine(true); e.setPadding(dp(a,14),0,dp(a,14),0);
        e.setBackground(round(Color.WHITE,14,BORDER,1,a));
        return e;
    }

    static TextView info(Activity a, String value) {
        TextView t = text(a,value,12,Color.rgb(64,86,116),false);
        t.setPadding(dp(a,16),dp(a,13),dp(a,16),dp(a,13));
        t.setBackground(round(Color.rgb(236,244,255),16,Color.TRANSPARENT,0,a));
        return t;
    }

    static LinearLayout.LayoutParams margins(Activity a, int w, int h, int l, int top, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,h);
        p.setMargins(dp(a,l),dp(a,top),dp(a,r),dp(a,b));
        return p;
    }
}
