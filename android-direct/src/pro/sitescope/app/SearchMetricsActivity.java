package pro.sitescope.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class SearchMetricsActivity extends Activity {
    private static final int NAVY = Color.rgb(22, 32, 51);
    private static final int MUTED = Color.rgb(104, 116, 137);
    private static final int PRIMARY = Color.rgb(13, 99, 229);
    private static final int GREEN = Color.rgb(20, 145, 93);
    private static final int AMBER = Color.rgb(192, 116, 8);
    private static final int RED = Color.rgb(205, 50, 63);
    private static final int BORDER = Color.rgb(222, 229, 239);
    private static final int SURFACE = Color.rgb(248, 243, 235);
    private static final int BACKGROUND = Color.rgb(255, 255, 255);

    private SecureStore secrets;
    private DataStore dataStore;
    private LinearLayout root;
    private EditText target;
    private EditText pageSpeedKey;
    private EditText googleToken;
    private EditText googleProperty;
    private EditText yandexToken;
    private CheckBox remember;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        secrets = new SecureStore(this);
        dataStore = new DataStore(this);
        renderForm();
    }

    private void renderForm() {
        root = page();
        LinearLayout header = row();
        TextView back = button("‹");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("📈 Современные поисковые метрики", 21, NAVY, true));
        titles.addView(text("Google Search Console, Core Web Vitals и Яндекс ИКС", 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        root.addView(info("Публичного Google Rank больше нет. Google использует множество автоматических систем и сигналов. " +
            "Здесь отображаются реальные показатели подтверждённого сайта: клики, показы, CTR, средняя позиция, Core Web Vitals и Lighthouse. " +
            "тИЦ также не используется — актуальный показатель Яндекса называется ИКС."));

        LinearLayout model = card();
        model.addView(text("Актуальная модель поисковой оценки", 16, NAVY, true));
        model.addView(text("Google: crawl/index → полезный people-first контент → ссылки и структура → mobile-first/page experience → поисковая производительность Search Console. AI Overviews и AI Mode не имеют отдельного публичного Rank и опираются на те же базовые требования.", 12, MUTED, false), margins(-1,-2,0,8,0,0));
        model.addView(divider());
        model.addView(text("Яндекс: ИКС → индексирование → диагностика → запросы и показы → качество и удобство сайта. Значение ИКС рассматривается вместе с динамикой, а не как единственная цель оптимизации.", 12, MUTED, false), margins(-1,-2,0,8,0,0));
        root.addView(model, margins(-1,-2,0,12,0,0));

        LinearLayout form = card();
        form.addView(text("Ресурс", 13, NAVY, true));
        target = input("https://example.com");
        form.addView(target, margins(-1, dp(50), 0, 7, 0, 12));

        form.addView(text("PageSpeed API key — необязательно", 12, NAVY, true));
        pageSpeedKey = secretInput("Ключ повышает доступную квоту");
        pageSpeedKey.setText(secrets.get("pagespeed_key"));
        form.addView(pageSpeedKey, margins(-1, dp(50), 0, 7, 0, 12));

        form.addView(text("Google Search Console OAuth access token", 12, NAVY, true));
        googleToken = secretInput("Только для подтверждённого вами сайта");
        googleToken.setText(secrets.get("google_token"));
        form.addView(googleToken, margins(-1, dp(50), 0, 7, 0, 8));
        googleProperty = input("Property: sc-domain:example.com или https://example.com/");
        googleProperty.setText(secrets.get("google_property"));
        form.addView(googleProperty, margins(-1, dp(50), 0, 0, 0, 12));

        form.addView(text("Яндекс Вебмастер OAuth token", 12, NAVY, true));
        yandexToken = secretInput("ИКС доступен владельцу сайта через API Вебмастера");
        yandexToken.setText(secrets.get("yandex_token"));
        form.addView(yandexToken, margins(-1, dp(50), 0, 7, 0, 6));

        remember = new CheckBox(this);
        remember.setText("Сохранить токены в Android Keystore");
        remember.setChecked(true);
        remember.setTextColor(NAVY);
        form.addView(remember);

        TextView run = primaryButton("Получить актуальные метрики");
        run.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { collect(); } });
        form.addView(run, margins(-1, dp(50), 0, 12, 0, 0));
        root.addView(form, margins(-1, -2, 0, 16, 0, 0));
        setContentView(scroll(root));
    }

    private void collect() {
        final SearchMetricsEngine.Config config = new SearchMetricsEngine.Config();
        config.targetUrl = target.getText().toString().trim();
        if (config.targetUrl.length() == 0) { target.setError("Введите домен или URL"); return; }
        config.pageSpeedKey = pageSpeedKey.getText().toString().trim();
        config.googleAccessToken = googleToken.getText().toString().trim();
        config.googleProperty = googleProperty.getText().toString().trim();
        config.yandexAccessToken = yandexToken.getText().toString().trim();
        if (remember.isChecked()) saveSecrets(config);

        root.removeAllViews();
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(24), dp(80), dp(24), dp(80));
        loading.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(58), dp(58)));
        loading.addView(text("Получаем официальные данные", 21, NAVY, true), margins(-2, -2, 0, 20, 0, 0));
        loading.addView(text("PageSpeed mobile/desktop → Search Console → Яндекс Вебмастер", 13, PRIMARY, true));
        loading.addView(text("Запрос может занять до минуты. Токены не передаются SiteScope Pro и используются только для прямых запросов к выбранным API.", 12, MUTED, false), margins(-1, -2, 0, 12, 0, 0));
        setContentView(loading);

        final Models.AuditResult audit = findAudit(config.targetUrl);
        new Thread(new Runnable() {
            @Override public void run() {
                final SearchMetricsEngine.Result result = SearchMetricsEngine.collect(config, audit);
                runOnUiThread(new Runnable() { @Override public void run() { renderResult(result); } });
            }
        }).start();
    }

    private void renderResult(SearchMetricsEngine.Result result) {
        root = page();
        LinearLayout header = row();
        TextView back = button("‹");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { renderForm(); } });
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("📈 Поисковая аналитика", 22, NAVY, true));
        titles.addView(text(result.targetUrl, 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header);

        LinearLayout readiness = card();
        readiness.addView(text(result.searchReadiness + " / 100", 34, scoreColor(result.searchReadiness), true));
        readiness.addView(text("Search Readiness", 16, NAVY, true));
        readiness.addView(text(result.readinessExplanation, 12, MUTED, false));
        root.addView(readiness, margins(-1, -2, 0, 12, 0, 0));

        root.addView(section("Google PageSpeed и Core Web Vitals", "Полевые данные при наличии в CrUX; лабораторные показатели — Lighthouse"));
        root.addView(pageSpeedCard(result.mobile));
        root.addView(pageSpeedCard(result.desktop), margins(-1, -2, 0, 10, 0, 0));

        root.addView(section("Google Search Console", "Реальная поисковая производительность подтверждённого ресурса за 28 дней"));
        LinearLayout google = card();
        if (!result.google.requested) {
            google.addView(status("Не подключено", "Добавьте OAuth token и property URL. Google Rank не вычисляется и не имитируется.", AMBER));
        } else if (!result.google.available) {
            google.addView(status("Ошибка Search Console", result.google.error, RED));
        } else {
            google.addView(metric("Клики", decimal(result.google.clicks, 0)));
            google.addView(divider());
            google.addView(metric("Показы", decimal(result.google.impressions, 0)));
            google.addView(divider());
            google.addView(metric("CTR", decimal(result.google.ctr * 100.0, 2) + "%"));
            google.addView(divider());
            google.addView(metric("Средняя позиция", decimal(result.google.position, 2)));
            google.addView(divider());
            google.addView(metric("Период", result.google.dateFrom + " — " + result.google.dateTo));
            google.addView(divider());
            google.addView(metric("Property", result.google.property));
        }
        root.addView(google);

        root.addView(section("Яндекс — индекс качества сайта", "Актуальный ИКС и состояние подтверждённого сайта; тИЦ не используется"));
        LinearLayout yandex = card();
        if (!result.yandex.requested) {
            yandex.addView(status("Не подключено", "Добавьте OAuth token Яндекс Вебмастера.", AMBER));
        } else if (!result.yandex.available) {
            yandex.addView(status("Ошибка Яндекс Вебмастера", result.yandex.error, RED));
        } else {
            yandex.addView(metric("ИКС", result.yandex.sqi < 0 ? "Нет данных" : String.valueOf(result.yandex.sqi)));
            yandex.addView(divider());
            yandex.addView(metric("Дата ИКС", empty(result.yandex.sqiDate)));
            yandex.addView(divider());
            yandex.addView(metric("Сайт", empty(result.yandex.hostUrl)));
            yandex.addView(divider());
            yandex.addView(metric("Права подтверждены", result.yandex.verified ? "Да" : "Нет"));
            yandex.addView(divider());
            yandex.addView(metric("Страницы в поиске", result.yandex.searchablePages < 0 ? "Нет данных" : String.valueOf(result.yandex.searchablePages)));
            yandex.addView(divider());
            yandex.addView(metric("Критические проблемы", result.yandex.criticalProblems < 0 ? "Нет данных" : String.valueOf(result.yandex.criticalProblems)));
        }
        root.addView(yandex);

        root.addView(info("Интерпретация: высокая средняя позиция не является отдельным Rank сайта; это агрегат по выбранным запросам и страницам. " +
            "Core Web Vitals используются в системах Google, но хорошие значения сами по себе не гарантируют высокие позиции. ИКС — показатель полезности и удобства сайта с точки зрения Яндекса."));
        TextView again = primaryButton("Изменить подключения");
        again.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { renderForm(); } });
        root.addView(again, margins(-1, dp(50), 0, 14, 0, 20));
        setContentView(scroll(root));
    }

    private View pageSpeedCard(SearchMetricsEngine.PageSpeed value) {
        LinearLayout card = card();
        card.addView(text("Стратегия: " + value.strategy.toUpperCase(Locale.US), 14, NAVY, true));
        if (!value.available) {
            card.addView(status("Нет результата", empty(value.error), value.error.length() == 0 ? AMBER : RED));
            return card;
        }
        LinearLayout scores = row();
        scores.addView(scoreBox("Perf", value.performance), new LinearLayout.LayoutParams(0, dp(70), 1f));
        scores.addView(scoreBox("SEO", value.seo), new LinearLayout.LayoutParams(0, dp(70), 1f));
        scores.addView(scoreBox("A11y", value.accessibility), new LinearLayout.LayoutParams(0, dp(70), 1f));
        scores.addView(scoreBox("Best", value.bestPractices), new LinearLayout.LayoutParams(0, dp(70), 1f));
        card.addView(scores, margins(-1, -2, 0, 10, 0, 8));
        card.addView(metric("CWV assessment", value.fieldAssessment));
        card.addView(divider());
        card.addView(metric("LCP", milliseconds(value.lcpMs)));
        card.addView(divider());
        card.addView(metric("INP", milliseconds(value.inpMs)));
        card.addView(divider());
        card.addView(metric("CLS", value.cls < 0 ? "Нет данных" : decimal(value.cls, 3)));
        card.addView(divider());
        card.addView(metric("TTFB", milliseconds(value.ttfbMs)));
        card.addView(divider());
        card.addView(metric("TBT laboratory", milliseconds(value.tbtMs)));
        return card;
    }

    private Models.AuditResult findAudit(String url) {
        String normalized = SearchMetricsEngine.normalizeUrl(url).toLowerCase(Locale.US);
        List<Models.AuditResult> audits = dataStore.audits();
        for (Models.AuditResult audit : audits) {
            String target = SearchMetricsEngine.normalizeUrl(audit.target).toLowerCase(Locale.US);
            if (normalized.contains(audit.host.toLowerCase(Locale.US)) || target.equals(normalized)) return audit;
        }
        return audits.isEmpty() ? null : audits.get(0);
    }

    private void saveSecrets(SearchMetricsEngine.Config config) {
        try {
            secrets.put("pagespeed_key", config.pageSpeedKey);
            secrets.put("google_token", config.googleAccessToken);
            secrets.put("google_property", config.googleProperty);
            secrets.put("yandex_token", config.yandexAccessToken);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось сохранить токены в Keystore: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private String sectionIcon(String title) {
        String value = title.toLowerCase(Locale.US);
        if (value.contains("pagespeed") || value.contains("core web vitals")) return "⚡";
        if (value.contains("search console")) return "📈";
        if (value.contains("яндекс")) return "🟡";
        return "•";
    }

        private ScrollView scroll(View view) { ScrollView s = new ScrollView(this); s.setFillViewport(true); s.addView(view); return s; }
    private LinearLayout page() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(16), dp(18), dp(16), dp(24)); v.setBackgroundColor(BACKGROUND); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout card() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(16), dp(16), dp(16), dp(16)); v.setBackground(background(SURFACE, 16, BORDER)); v.setElevation(dp(1)); return v; }
    private TextView text(String value, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(0, 1.12f); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private EditText input(String hint) { EditText v = new EditText(this); v.setHint(hint); v.setTextColor(NAVY); v.setHintTextColor(Color.rgb(150,160,176)); v.setTextSize(13); v.setSingleLine(true); v.setPadding(dp(12),0,dp(12),0); v.setBackground(background(Color.WHITE, 10, Color.rgb(230,236,244))); return v; }
    private EditText secretInput(String hint) { EditText v = input(hint); v.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return v; }
    private TextView primaryButton(String value) { TextView v = text(value, 14, Color.WHITE, true); v.setGravity(Gravity.CENTER); v.setBackground(background(PRIMARY, 12, PRIMARY)); return v; }
    private TextView button(String value) { TextView v = text(value, 30, PRIMARY, false); v.setGravity(Gravity.CENTER); return v; }
    private TextView section(String title, String subtitle) { TextView v = text(sectionIcon(title) + "  " + title + "\n" + subtitle, 16, NAVY, true); v.setPadding(0, dp(20), 0, dp(8)); return v; }
    private View info(String message) { TextView v = text(message, 12, MUTED, false); v.setPadding(dp(14),dp(12),dp(14),dp(12)); v.setBackground(background(Color.rgb(235,242,255), 12, Color.rgb(204,220,248))); return withMargins(v, 0, 12, 0, 0); }
    private View status(String title, String detail, int color) { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.addView(text(title,15,color,true)); v.addView(text(detail,12,MUTED,false), margins(-1,-2,0,4,0,0)); return v; }
    private View metric(String name, String value) { LinearLayout v = row(); TextView a=text(name,12,MUTED,false); TextView b=text(value,13,NAVY,true); b.setGravity(Gravity.END); v.addView(a,new LinearLayout.LayoutParams(0,-2,1f)); v.addView(b,new LinearLayout.LayoutParams(0,-2,1.25f)); v.setPadding(0,dp(7),0,dp(7)); return v; }
    private View divider() { View v = new View(this); v.setBackgroundColor(BORDER); v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1))); return v; }
    private View scoreBox(String label, int value) { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER); v.addView(text(value < 0 ? "—" : String.valueOf(value),20,scoreColor(value),true)); v.addView(text(label,10,MUTED,true)); return v; }
    private int scoreColor(int score) { return score >= 90 ? GREEN : score >= 50 ? AMBER : RED; }
    private String milliseconds(double value) { return value < 0 ? "Нет данных" : decimal(value,0) + " мс"; }
    private String decimal(double value, int digits) { DecimalFormat f = new DecimalFormat(digits == 0 ? "#,##0" : digits == 2 ? "#,##0.00" : "#,##0.000"); return f.format(value); }
    private String empty(String value) { return value == null || value.length() == 0 ? "Нет данных" : value; }
    private GradientDrawable background(int color, int radius, int stroke) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1),stroke); return d; }
    private View withMargins(View v,int l,int t,int r,int b){ v.setLayoutParams(margins(-1,-2,l,t,r,b)); return v; }
    private LinearLayout.LayoutParams margins(int w,int h,int l,int t,int r,int b){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int value){ return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
}
