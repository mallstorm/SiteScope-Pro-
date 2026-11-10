package pro.sitescope.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

abstract class BaseSiteScopeActivity extends Activity {
    protected static final int NAVY = Color.rgb(43, 38, 34);
    protected static final int MUTED = Color.rgb(133, 122, 112);
    protected static final int PRIMARY = Color.rgb(217, 83, 43);
    protected static final int PRIMARY_SOFT = Color.rgb(255, 232, 222);
    protected static final int GREEN = Color.rgb(20, 145, 93);
    protected static final int GREEN_SOFT = Color.rgb(228, 247, 238);
    protected static final int AMBER = Color.rgb(192, 116, 8);
    protected static final int AMBER_SOFT = Color.rgb(255, 245, 222);
    protected static final int RED = Color.rgb(205, 50, 63);
    protected static final int RED_SOFT = Color.rgb(255, 234, 236);
    protected static final int BORDER = Color.rgb(233, 223, 212);
    protected static final int SURFACE = Color.rgb(248, 243, 235);
    protected static final int BACKGROUND = Color.WHITE;

    protected FrameLayout screenContent;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    protected void setRootScreen(View content, String activeTab) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        screenContent = new FrameLayout(this);
        screenContent.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(screenContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomNavigation(activeTab), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    protected void replaceContent(View content) {
        if (screenContent == null) return;
        screenContent.removeAllViews();
        screenContent.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout bottomNavigation(String active) {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(4), dp(6), dp(4), dp(8));
        nav.setBackgroundColor(Color.WHITE);
        nav.setElevation(dp(12));
        nav.addView(navItem("⌂\nГлавное", "HOME".equals(active), MainActivity.class), new LinearLayout.LayoutParams(0, dp(54), 1f));
        nav.addView(navItem("▣\nПроекты", "PROJECTS".equals(active), ProjectsActivity.class), new LinearLayout.LayoutParams(0, dp(54), 1f));
        nav.addView(navItem("🕘\nИстория", "HISTORY".equals(active), HistoryActivity.class), new LinearLayout.LayoutParams(0, dp(54), 1f));
        return nav;
    }

    private TextView navItem(String title, boolean selected, final Class<?> destination) {
        TextView item = label(title, 11, selected ? PRIMARY : MUTED, selected);
        item.setGravity(Gravity.CENTER);
        item.setBackground(round(selected ? PRIMARY_SOFT : Color.TRANSPARENT, 13, Color.TRANSPARENT, 0));
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (destination.isInstance(BaseSiteScopeActivity.this)) return;
                Intent intent = new Intent(BaseSiteScopeActivity.this, destination);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            }
        });
        return item;
    }

    protected LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(34));
        return page;
    }

    protected ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(child, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    protected LinearLayout appHeader(String title, String subtitle, boolean compact) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, compact ? dp(2) : dp(8), 0, dp(18));
        TextView logo = label(headerIcon(title), 20, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(PRIMARY, 14, Color.TRANSPARENT, 0));
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(13), 0, 0, 0);
        text.addView(label(title, compact ? 22 : 25, NAVY, true));
        text.addView(label(subtitle, 13, MUTED, false));
        header.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(badge("1.0", PRIMARY, PRIMARY_SOFT));
        return header;
    }

    protected String headerIcon(String title) {
        if (title.startsWith("SEO")) return "S";
        if (title.contains("IPTV")) return "TV";
        if (title.contains("IP") || title.contains("Сеть")) return "IP";
        if (title.contains("Проект")) return "P";
        if (title.contains("Истор")) return "H";
        return "S";
    }

    protected LinearLayout card(int paddingDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        card.setBackground(round(SURFACE, 18, BORDER, 1));
        card.setElevation(dp(2));
        card.setLayoutParams(marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 4));
        return card;
    }

    protected LinearLayout sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(title, 19, NAVY, true));
        if (subtitle != null && subtitle.length() > 0) box.addView(label(subtitle, 13, MUTED, false));
        box.setPadding(0, dp(18), 0, dp(6));
        return box;
    }

    protected LinearLayout checkRow(String title, String subtitle) {
        LinearLayout item = card(14);
        LinearLayout row = row();
        TextView mark = label("✓", 18, GREEN, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(GREEN_SOFT, 12, Color.TRANSPARENT, 0));
        row.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        text.addView(label(title, 15, NAVY, true));
        text.addView(label(subtitle, 12, MUTED, false));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        item.addView(row);
        return item;
    }

    protected LinearLayout emptyState(String title, String subtitle) {
        LinearLayout box = card(18);
        box.setGravity(Gravity.CENTER);
        box.addView(label(title, 17, NAVY, true));
        TextView sub = label(subtitle, 13, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        box.addView(sub, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        return box;
    }

    protected TextView infoStrip(String value) {
        TextView strip = label(value, 13, Color.rgb(62, 86, 120), false);
        strip.setPadding(dp(14), dp(12), dp(14), dp(12));
        strip.setBackground(round(Color.rgb(235, 243, 255), 14, Color.TRANSPARENT, 0));
        return strip;
    }

    protected LinearLayout keyValue(String key, String value) {
        LinearLayout row = row();
        TextView k = label(key, 13, MUTED, true);
        row.addView(k, new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = label(value(value), 13, NAVY, false);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setPadding(0, dp(8), 0, dp(8));
        return row;
    }

    protected TextView metricBox(String value, String caption, int color, int soft) {
        TextView box = label(value + "\n" + caption, 15, color, true);
        box.setGravity(Gravity.CENTER);
        box.setBackground(round(soft, 12, Color.TRANSPARENT, 0));
        return box;
    }

    protected TextView badge(String title, int color, int background) {
        TextView badge = label(title, 11, color, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(round(background, 999, Color.TRANSPARENT, 0));
        return badge;
    }

    protected TextView label(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    protected EditText input(String hint) {
        EditText input = new EditText(this);
        input.setTextSize(15);
        input.setTextColor(NAVY);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(round(Color.WHITE, 13, BORDER, 1));
        return input;
    }

    protected TextView smallOutlineButton(String title) {
        TextView button = outlineButton(title);
        button.setTextSize(12);
        return button;
    }
    protected TextView primaryButton(String title) { return button(title, Color.WHITE, PRIMARY, Color.TRANSPARENT); }
    protected TextView darkButton(String title) { return button(title, Color.WHITE, NAVY, Color.TRANSPARENT); }
    protected TextView outlineButton(String title) { return button(title, PRIMARY, Color.WHITE, BORDER); }
    protected TextView button(String title, int textColor, int background, int stroke) {
        TextView button = label(title, 14, textColor, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(round(background, 14, stroke, stroke == Color.TRANSPARENT ? 0 : 1));
        return button;
    }

    protected LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    protected Space space(int widthDp) { Space s = new Space(this); s.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1)); return s; }
    protected View divider() { View v = new View(this); v.setBackgroundColor(Color.rgb(235,239,245)); v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))); return v; }
    protected GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0 && strokeColor != Color.TRANSPARENT) d.setStroke(dp(strokeDp), strokeColor); return d;
    }
    protected int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    protected LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.setMargins(dp(left),dp(top),dp(right),dp(bottom)); return p;
    }
    protected LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height, weight); p.setMargins(dp(left),dp(top),dp(right),dp(bottom)); return p;
    }
    protected String value(String value) { return value == null || value.length() == 0 ? "—" : value; }
    protected String formatDate(long timestamp) { return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(timestamp)); }
    protected int parsePositiveInt(String value, int fallback) { try { int n = Integer.parseInt(value.trim()); return n > 0 ? n : fallback; } catch (Exception ignored) { return fallback; } }
    protected int scoreColor(int score) { return score >= 80 ? GREEN : score >= 55 ? AMBER : RED; }
    protected int scoreSoftColor(int score) { return score >= 80 ? GREEN_SOFT : score >= 55 ? AMBER_SOFT : RED_SOFT; }
    protected String scoreLabel(int score) { return score >= 90 ? "Отличное состояние" : score >= 75 ? "Хорошая основа" : score >= 55 ? "Нужны исправления" : "Обнаружены серьёзные проблемы"; }
    protected int severityColor(String severity) { return "critical".equals(severity) ? RED : "important".equals(severity) ? AMBER : "warning".equals(severity) ? PRIMARY : MUTED; }
    protected int severitySoft(String severity) { return "critical".equals(severity) ? RED_SOFT : "important".equals(severity) ? AMBER_SOFT : "warning".equals(severity) ? PRIMARY_SOFT : Color.rgb(239,242,246); }
    protected String severityTitle(String severity) { return "critical".equals(severity) ? "КРИТИЧНО" : "important".equals(severity) ? "ВАЖНО" : "warning".equals(severity) ? "ЗАМЕЧАНИЕ" : "РЕКОМЕНДАЦИЯ"; }
}
