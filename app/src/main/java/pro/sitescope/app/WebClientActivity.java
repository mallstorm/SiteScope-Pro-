package pro.sitescope.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import pro.sitescope.app.anon.AnonNet;
import pro.sitescope.app.anon.AnonRouting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Compact built-in browser for discovered HTTP/HTTPS services. */
public final class WebClientActivity extends Activity {
    private static final int NAVY = Color.rgb(14, 35, 48);
    private static final int MUTED = Color.rgb(81, 99, 111);
    private static final int PRIMARY = Color.rgb(14, 124, 134);
    private static final int PRIMARY_SOFT = Color.rgb(211, 241, 243);
    private static final int BORDER = Color.rgb(211, 222, 230);
    private static final int BAR_DARK = Color.rgb(6, 34, 44);
    private static final int BAR_MID = Color.rgb(11, 70, 81);
    private static final int TOR_COLOR = Color.rgb(112, 72, 176);
    private static final int I2P_COLOR = Color.rgb(183, 98, 0);
    private static final int MAX_TABS = 24;
    /** Единый «Tor Browser»-подобный User-Agent для .onion/.i2p: сайты не видят отпечаток обычного WebView. */
    private static final String ANON_AGENT = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0";

    /** Вкладка браузера. WebView создаётся лениво при первом выборе вкладки. */
    private static final class Tab {
        WebView view;
        String url = "";
        String title = "";
        String kind = "web";
        String lastStatus = "";
        boolean fresh;
        int token;
    }

    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private int current = -1;
    private FrameLayout webHost;
    private TextView chip;
    private TextView tabCount;
    private AlertDialog tabsDialog;

    private ServiceEndpoint endpoint;
    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private AlertDialog tlsDialog;
    private AlertDialog authDialog;
    private static final String DESKTOP_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private boolean desktopMode;
    private boolean javaScript = true;
    private String mobileAgent = "";
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_REQUEST = 4711;
    private final Set<String> authTried = new HashSet<String>();
    private final Map<String, String> authHeaders = new HashMap<String, String>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BAR_DARK);
        AnonNet.init(this);
        endpoint = ServiceEndpoint.from(getIntent());
        buildUi();
        restoreTabs();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        endpoint = ServiceEndpoint.from(intent);
        handleIntent(intent);
    }

    /** Открывает адрес из интента во вкладке: уже открытый адрес просто выбирается, новый создаёт вкладку. */
    private void handleIntent(Intent intent) {
        String direct = intent.getStringExtra("open_url");
        String initial = "";
        if (direct != null && direct.length() > 0) {
            initial = AnonRouting.INSTANCE.normalize(direct);
            try {
                Uri parsed = Uri.parse(initial);
                endpoint.protocol = "https".equalsIgnoreCase(parsed.getScheme()) ? "HTTPS" : "HTTP";
                endpoint.host = parsed.getHost() == null ? "" : parsed.getHost();
                endpoint.port = parsed.getPort() > 0 ? parsed.getPort() : ("HTTPS".equals(endpoint.protocol) ? 443 : 80);
                endpoint.displayName = endpoint.host;
                endpoint.uri = initial;
            } catch (Exception ignored) { }
        } else if (!intent.getBooleanExtra("restore_tabs", false) && (endpoint.uri.length() > 0 || endpoint.host.length() > 0)) {
            initial = endpoint.uri.length() > 0 ? endpoint.uri : ServiceEndpoint.buildUri(endpoint.protocol, endpoint.host, endpoint.port, endpoint.location);
            if (!initial.startsWith("http://") && !initial.startsWith("https://")) initial = "http://" + initial;
        }
        String newKind = intent.getStringExtra("new_tab_kind");
        if (newKind != null && initial.length() == 0) { addTab("", newKind, true); return; }
        if (initial.length() > 0) {
            for (int i = 0; i < tabs.size(); i++) {
                if (sameUrl(tabs.get(i).url, initial)) { selectTab(i); return; }
            }
            Tab now = tab();
            if (now != null && now.url.length() == 0 && now.view != null) { navigate(now, initial); return; }
            addTab(initial, kindOf(initial), true);
            return;
        }
        if (tabs.isEmpty()) addTab("", "web", true);
        else selectTab(Math.max(0, Math.min(current < 0 ? 0 : current, tabs.size() - 1)));
    }

    private static boolean sameUrl(String a, String b) {
        if (a == null || b == null || a.length() == 0 || b.length() == 0) return false;
        return a.replaceAll("/+$", "").equalsIgnoreCase(b.replaceAll("/+$", ""));
    }

    private static String kindOf(String url) {
        AnonRouting.Route route = AnonRouting.INSTANCE.routeOf(AnonRouting.INSTANCE.hostOf(url), false);
        if (route == AnonRouting.Route.TOR) return "tor";
        if (route == AnonRouting.Route.I2P) return "i2p";
        return "web";
    }

    // ───── вкладки ─────

    private Tab tab() { return current >= 0 && current < tabs.size() ? tabs.get(current) : null; }

    private Tab tabOf(WebView view) {
        for (Tab t : tabs) if (t.view == view) return t;
        return null;
    }

    private Tab addTab(String url, String kind, boolean select) {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Достигнут предел вкладок (" + MAX_TABS + ")", Toast.LENGTH_SHORT).show();
            return null;
        }
        Tab t = new Tab();
        t.url = url == null ? "" : url;
        t.kind = kind == null ? "web" : kind;
        tabs.add(t);
        if (select) selectTab(tabs.size() - 1);
        return t;
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);
        if (t.view == null) {
            t.view = createWebView();
            t.fresh = true;
            webHost.addView(t.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (Tab o : tabs) {
            if (o.view == null) continue;
            boolean active = o == t;
            o.view.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active) o.view.onResume(); else o.view.onPause();
        }
        current = index;
        webView = t.view;
        updateChrome();
        if (t.fresh) {
            t.fresh = false;
            if (t.url.length() > 0) navigate(t, t.url);
            else { showStartPage(t); focusAddress(); }
        }
        saveTabs();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.remove(index);
        t.token++;
        destroyView(t);
        if (tabs.isEmpty()) {
            current = -1;
            webView = null;
            saveTabs();
            finish();
            return;
        }
        if (index < current) current--;
        else if (index == current) current = Math.min(index, tabs.size() - 1);
        selectTab(current);
    }

    private void destroyView(Tab t) {
        if (t.view == null) return;
        try {
            t.view.stopLoading();
            t.view.loadUrl("about:blank");
            t.view.clearHistory();
            webHost.removeView(t.view);
            t.view.removeAllViews();
            t.view.destroy();
        } catch (Exception ignored) { }
        t.view = null;
    }

    private void updateChrome() {
        Tab t = tab();
        if (t == null) return;
        address.setText(t.url);
        String kind = t.url.length() > 0 ? kindOf(t.url) : t.kind;
        if ("tor".equals(kind)) setChip("TOR", TOR_COLOR);
        else if ("i2p".equals(kind)) setChip("I2P", I2P_COLOR);
        else if (t.url.startsWith("https://")) setChip("HTTPS", Color.rgb(31, 157, 99));
        else if (t.url.length() > 0) setChip("HTTP", MUTED);
        else setChip("WEB", MUTED);
        tabCount.setText(String.valueOf(tabs.size()));
    }

    private void setChip(String label, int color) {
        chip.setText(label);
        chip.setBackground(round(color, 8, Color.TRANSPARENT, 0));
    }

    private void focusAddress() {
        address.post(new Runnable() { @Override public void run() {
            address.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(address, 0);
        } });
    }

    private SharedPreferences tabPrefs() { return getSharedPreferences("browser_tabs", Context.MODE_PRIVATE); }

    private void saveTabs() {
        try {
            JSONArray array = new JSONArray();
            for (Tab t : tabs) {
                JSONObject o = new JSONObject();
                o.put("url", t.url); o.put("title", t.title); o.put("kind", t.kind);
                array.put(o);
            }
            tabPrefs().edit().putString("tabs", array.toString()).putInt("current", Math.max(0, current)).apply();
        } catch (Exception ignored) { }
    }

    private void restoreTabs() {
        try {
            JSONArray array = new JSONArray(tabPrefs().getString("tabs", "[]"));
            for (int i = 0; i < array.length() && i < MAX_TABS; i++) {
                JSONObject o = array.getJSONObject(i);
                Tab t = new Tab();
                t.url = o.optString("url", "");
                t.title = o.optString("title", "");
                t.kind = o.optString("kind", "web");
                tabs.add(t);
            }
            current = tabs.isEmpty() ? -1 : Math.min(tabPrefs().getInt("current", 0), tabs.size() - 1);
        } catch (Exception ignored) { tabs.clear(); current = -1; }
    }

    private void showTabs() {
        if (tabsDialog != null && tabsDialog.isShowing()) tabsDialog.dismiss();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), 0);
        LinearLayout create = new LinearLayout(this);
        create.setOrientation(LinearLayout.HORIZONTAL);
        create.addView(newTabButton("＋ Вкладка", PRIMARY, "web"), margin(0, dp(40), 0, 0, 4, 0, 1f));
        create.addView(newTabButton("＋ Tor", TOR_COLOR, "tor"), margin(0, dp(40), 4, 0, 4, 0, 1f));
        create.addView(newTabButton("＋ I2P", I2P_COLOR, "i2p"), margin(0, dp(40), 4, 0, 0, 0, 1f));
        box.addView(create);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(4));
        for (int i = 0; i < tabs.size(); i++) list.addView(tabRow(i));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tabsDialog = new AlertDialog.Builder(this).setTitle("Вкладки: " + tabs.size()).setView(box)
            .setNeutralButton("Закрыть все", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { closeAllTabs(); }
            })
            .setPositiveButton("Готово", null).create();
        tabsDialog.show();
    }

    private TextView newTabButton(String label, int color, final String kind) {
        TextView b = text(label, 13, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(color, 12, Color.TRANSPARENT, 0));
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (tabsDialog != null) tabsDialog.dismiss();
            addTab("", kind, true);
        } });
        return b;
    }

    private View tabRow(final int index) {
        final Tab t = tabs.get(index);
        String kind = t.url.length() > 0 ? kindOf(t.url) : t.kind;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(4), dp(6));
        row.setBackground(round(index == current ? PRIMARY_SOFT : Color.TRANSPARENT, 12, index == current ? PRIMARY : BORDER, 1));
        TextView badge = text("tor".equals(kind) ? "TOR" : "i2p".equals(kind) ? "I2P" : "WEB", 10, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round("tor".equals(kind) ? TOR_COLOR : "i2p".equals(kind) ? I2P_COLOR : MUTED, 8, Color.TRANSPARENT, 0));
        row.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(22)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        String host = AnonRouting.INSTANCE.hostOf(t.url);
        String title = t.title.length() > 0 ? t.title : host.length() > 0 ? host : "Новая вкладка";
        TextView name = text(title, 14, NAVY, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = text(t.url.length() > 0 ? t.url : "пустая вкладка", 11, MUTED, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        labels.addView(sub);
        row.addView(labels, margin(0, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 6, 0, 1f));
        TextView close = text("✕", 16, MUTED, false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (tabsDialog != null) tabsDialog.dismiss();
            closeTab(index);
            if (!tabs.isEmpty() && !isFinishing()) showTabs();
        } });
        row.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (tabsDialog != null) tabsDialog.dismiss();
            selectTab(index);
        } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void closeAllTabs() {
        for (Tab t : new ArrayList<Tab>(tabs)) { t.token++; destroyView(t); }
        tabs.clear();
        current = -1;
        webView = null;
        saveTabs();
        finish();
    }

    // ───── загрузка страниц ─────

    private void navigate(String raw) {
        Tab t = tab();
        if (t == null) t = addTab("", "web", true);
        if (t != null) navigate(t, raw);
    }

    /** Единая точка загрузки: для .onion/.i2p сначала поднимает Tor/I2P и прокси WebView, затем открывает адрес во вкладке. */
    private void navigate(final Tab target, String raw) {
        final String url = AnonRouting.INSTANCE.normalize(raw);
        if (url.length() == 0 || target.view == null) return;
        target.url = url;
        target.kind = kindOf(url);
        target.lastStatus = "";
        if (target == tab()) updateChrome();
        applyAgent(target, url);
        final int token = ++target.token;
        AnonNet.prepare(this, url, new AnonNet.Listener() {
            @Override public void status(String text) {
                if (token == target.token && !isFinishing() && target.view != null) showStatusPage(target, url, text);
            }
            @Override public void ready() {
                if (token == target.token && !isFinishing() && target.view != null) target.view.loadUrl(url);
            }
            @Override public void failed(String text) {
                if (token == target.token && !isFinishing() && target.view != null) showErrorPage(target.view, url, text);
            }
        });
        saveTabs();
    }

    /** UA и сторонние cookies зависят от маршрута: у .onion/.i2p единый Tor-подобный UA и включены cookies (нужны для проверок вроде капчи). */
    private void applyAgent(Tab t, String url) {
        if (t.view == null) return;
        boolean anon = !"web".equals(kindOf(url)) || (AnonNet.INSTANCE.settings().getTorForAll() && !AnonRouting.INSTANCE.isLocalHost(AnonRouting.INSTANCE.hostOf(url)));
        WebSettings st = t.view.getSettings();
        String agent = anon ? ANON_AGENT : desktopMode ? DESKTOP_AGENT : mobileAgent;
        if (!agent.equals(st.getUserAgentString())) st.setUserAgentString(agent);
        CookieManager.getInstance().setAcceptThirdPartyCookies(t.view, anon);
    }

    private boolean needsPrepare(String url) {
        String host = AnonRouting.INSTANCE.hostOf(url);
        AnonRouting.Route route = AnonRouting.INSTANCE.routeOf(host, AnonNet.INSTANCE.settings().getTorForAll());
        boolean needProxy = route != AnonRouting.Route.DIRECT || AnonNet.directViaDpi();
        return needProxy && !AnonNet.INSTANCE.getState().getValue().getWebViewProxyOn();
    }

    private String page(String title, String body) {
        return "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>body{font-family:sans-serif;margin:0;background:#edf3f8;color:#0e2330}.c{margin:22px 16px;padding:20px;background:#fff;border-radius:18px;"
            + "box-shadow:0 1px 4px rgba(6,34,44,.12)}h3{margin:0 0 10px;font-size:19px}p{margin:8px 0;line-height:1.45}.m{color:#51636f;word-break:break-all;font-size:13px}"
            + ".bar{height:5px;border-radius:5px;background:#d3f1f3;overflow:hidden;margin:16px 0 4px}.bar i{display:block;height:100%;width:35%;border-radius:5px;background:#0e7c86;animation:s 1.3s ease-in-out infinite}"
            + "@keyframes s{0%{margin-left:-35%}100%{margin-left:100%}}a.b{display:inline-block;margin-top:10px;padding:10px 18px;border-radius:12px;background:#0e7c86;color:#fff;text-decoration:none;font-weight:bold}"
            + "</style></head><body><div class=\"c\"><h3>" + TextUtils.htmlEncode(title) + "</h3>" + body + "</div></body></html>";
    }

    private void showStatusPage(Tab t, String url, String text) {
        if (t.view == null || text.equals(t.lastStatus)) return;
        t.lastStatus = text;
        String kind = kindOf(url);
        String title = "tor".equals(kind) ? "Подключение к Tor" : "i2p".equals(kind) ? "Подключение к I2P" : "Подготовка";
        t.view.loadDataWithBaseURL(null, page(title, "<p class=\"m\">" + TextUtils.htmlEncode(url) + "</p><div class=\"bar\"><i></i></div><p style=\"white-space:pre-wrap\">"
            + TextUtils.htmlEncode(text) + "</p>"), "text/html", "utf-8", null);
    }

    private void showStartPage(Tab t) {
        if (t.view == null) return;
        String hint = "tor".equals(t.kind) ? "Введите адрес .onion в строке сверху. Tor запустится автоматически."
            : "i2p".equals(t.kind) ? "Введите адрес .i2p в строке сверху. I2P запустится автоматически, первый запуск занимает несколько минут."
            : "Введите адрес сайта в строке сверху. Адреса .onion и .i2p откроются через встроенные Tor и I2P.";
        String title = "tor".equals(t.kind) ? "Новая вкладка · Tor" : "i2p".equals(t.kind) ? "Новая вкладка · I2P" : "Новая вкладка";
        t.view.loadDataWithBaseURL(null, page(title, "<p>" + TextUtils.htmlEncode(hint) + "</p>"), "text/html", "utf-8", null);
    }

    private void showErrorPage(WebView view, String url, String text) {
        String retry = "<a class=\"b\" href=\"" + TextUtils.htmlEncode(url) + "\">Повторить</a>";
        view.loadDataWithBaseURL(url, page("Не удалось открыть страницу",
            "<p class=\"m\">" + TextUtils.htmlEncode(url) + "</p><p style=\"white-space:pre-wrap\">" + TextUtils.htmlEncode(text) + "</p>" + retry),
            "text/html", "utf-8", url);
    }

    private String errorText(int code, String description) {
        String reason;
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP: reason = "Адрес не найден (DNS). Проверьте имя узла и подключение."; break;
            case WebViewClient.ERROR_CONNECT: reason = "Узел не принимает подключение. Проверьте адрес, порт и что вы в той же сети."; break;
            case WebViewClient.ERROR_TIMEOUT: reason = "Узел не отвечает (тайм-аут)."; break;
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE: reason = "Не удалось установить защищённое соединение. Возможно, узел отвечает по http, а не https."; break;
            case WebViewClient.ERROR_UNSUPPORTED_SCHEME: reason = "Схема адреса не поддерживается."; break;
            case WebViewClient.ERROR_TOO_MANY_REQUESTS: reason = "Слишком много запросов."; break;
            case WebViewClient.ERROR_REDIRECT_LOOP: reason = "Бесконечная переадресация."; break;
            default: reason = "Ошибка загрузки."; break;
        }
        return reason + (description == null || description.length() == 0 ? "" : "\n\n" + description);
    }

    private void pageUrl(WebView view, String url) {
        if (url == null || url.startsWith("data:") || url.equals("about:blank")) return;
        Tab t = tabOf(view);
        if (t != null) { t.url = url; t.kind = kindOf(url); }
        if (view == webView) updateChrome();
    }

    private WebView createWebView() {
        final WebView wv = new WebView(this);
        wv.setBackgroundColor(Color.rgb(237, 243, 248));
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDatabaseEnabled(true);
        if (mobileAgent.length() == 0) mobileAgent = settings.getUserAgentString().replace("; wv", "");
        settings.setUserAgentString(desktopMode ? DESKTOP_AGENT : mobileAgent);
        settings.setJavaScriptEnabled(javaScript);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(wv, false);
        wv.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String agent, String disposition, String mime, long length) { startDownload(url, agent, disposition, mime); }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                return showJsDialog(url, message, null, result, null);
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                return showJsDialog(url, message, null, result, null);
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
                return showJsDialog(url, message, defaultValue == null ? "" : defaultValue, result, result);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                } catch (Exception error) {
                    fileCallback = null;
                    Toast.makeText(WebClientActivity.this, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                Tab t = tabOf(view);
                if (t != null && title != null && !title.startsWith("data:")) { t.title = title; }
            }
            @Override public void onProgressChanged(WebView view, int newProgress) {
                if (view != webView) return;
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(view, request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(view, Uri.parse(url));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                pageUrl(view, url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                pageUrl(view, url);
                Tab t = tabOf(view);
                if (t != null) { String title = view.getTitle(); if (title != null && url != null && !url.startsWith("data:")) t.title = title; }
                CookieManager.getInstance().flush();
                saveTabs();
            }
            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                showAuthDialog(handler, host, realm);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !String.valueOf(error.getDescription()).contains("ERR_ABORTED")) showErrorPage(view, request.getUrl().toString(), errorText(error.getErrorCode(), String.valueOf(error.getDescription())));
            }
            @Override public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                handleSslError(handler, error);
            }
        });
        return wv;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(237, 243, 248));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(6), dp(6), dp(8));
        GradientDrawable topBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{BAR_DARK, BAR_MID});
        top.setBackground(topBg);

        TextView close = button("‹");
        close.setTextSize(26);
        close.setTextColor(Color.WHITE);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        top.addView(close, new LinearLayout.LayoutParams(dp(40), dp(42)));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), 0, dp(2), 0);
        pill.setBackground(round(Color.WHITE, 14, Color.TRANSPARENT, 0));
        chip = text("WEB", 10, Color.WHITE, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), 0, dp(6), 0);
        chip.setBackground(round(MUTED, 8, Color.TRANSPARENT, 0));
        pill.addView(chip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(13);
        address.setTextColor(NAVY);
        address.setHint("Адрес, .onion или .i2p");
        address.setHintTextColor(MUTED);
        address.setBackgroundColor(Color.TRANSPARENT);
        address.setPadding(dp(8), 0, dp(4), 0);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                loadTypedAddress();
                return true;
            }
        });
        pill.addView(address, new LinearLayout.LayoutParams(0, dp(40), 1f));

        TextView reload = button("↻");
        reload.setTextSize(18);
        reload.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            String current = webView == null ? null : webView.getUrl();
            if (current == null || current.startsWith("data:") || current.equals("about:blank")) navigate(address.getText().toString()); else webView.reload();
        } });
        pill.addView(reload, new LinearLayout.LayoutParams(dp(36), dp(40)));
        top.addView(pill, margin(0, dp(40), 4, 0, 4, 0, 1f));

        TextView menu = button("⋮");
        menu.setTextColor(Color.WHITE);
        menu.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showMenu(); } });
        top.addView(menu, new LinearLayout.LayoutParams(dp(38), dp(42)));
        root.addView(top);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34, 230, 210)));
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webHost = new FrameLayout(this);
        root.addView(webHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(2), dp(8), dp(4));
        bottom.setBackgroundColor(Color.WHITE);
        TextView back = navButton("‹", "Назад");
        TextView forward = navButton("›", "Вперёд");
        TextView add = navButton("＋", "Вкладка");
        tabCount = navButton("1", "Вкладки");
        TextView external = navButton("↗", "Внешний");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (webView != null && webView.canGoBack()) webView.goBack(); else finish(); } });
        forward.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (webView != null && webView.canGoForward()) webView.goForward(); } });
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Tab t = tab(); addTab("", t == null ? "web" : (t.url.length() > 0 ? kindOf(t.url) : t.kind), true); } });
        tabCount.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showTabs(); } });
        external.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (webView != null) openExternal(webView.getUrl()); } });
        bottom.addView(back, new LinearLayout.LayoutParams(0, dp(44), 1f));
        bottom.addView(forward, new LinearLayout.LayoutParams(0, dp(44), 1f));
        bottom.addView(add, new LinearLayout.LayoutParams(0, dp(44), 1f));
        bottom.addView(tabCount, new LinearLayout.LayoutParams(0, dp(44), 1f));
        bottom.addView(external, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(bottom);
        setContentView(root);
    }

    private boolean handleNavigation(WebView view, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (needsPrepare(uri.toString())) { Tab t = tabOf(view); if (t != null) navigate(t, uri.toString()); return true; }
            { Tab t = tabOf(view); if (t != null) applyAgent(t, uri.toString()); }
            return false;
        }
        if ("about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme) || "javascript".equals(scheme)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
            Toast.makeText(this, "Нет приложения для ссылки " + scheme, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private void loadTypedAddress() {
        navigate(address.getText().toString().trim());
    }

    private void handleSslError(final SslErrorHandler handler, final SslError error) {
        if (tlsDialog != null && tlsDialog.isShowing()) {
            handler.cancel();
            return;
        }
        final String url = error.getUrl() == null || error.getUrl().length() == 0
            ? address.getText().toString() : error.getUrl();
        final String authority = TlsTrustStore.authority(url);
        final SslCertificate certificate = error.getCertificate();
        final String fingerprint = TlsTrustStore.fingerprint(certificate);
        final String pinned = TlsTrustStore.pinnedFingerprint(this, authority);
        final boolean pinnable = isPinnableTlsError(error);

        if (pinnable && fingerprint.length() > 0 && TlsTrustStore.matches(this, authority, fingerprint)) {
            handler.proceed();
            Toast.makeText(this, "TLS: сохранённый сертификат подтверждён", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean changed = pinned.length() > 0 && fingerprint.length() > 0 && !pinned.equalsIgnoreCase(fingerprint);
        StringBuilder message = new StringBuilder();
        if (changed) {
            message.append("Сертификат узла изменился. Автоматическое подключение заблокировано.\n\n");
            message.append("Сохранённый SHA-256:\n").append(pinned).append("\n\n");
            message.append("Новый SHA-256:\n").append(fingerprint).append("\n\n");
        } else {
            message.append(sslErrorMessage(error)).append("\n\n");
        }
        message.append("Узел: ").append(authority).append("\n");
        message.append("URL: ").append(url).append("\n\n");
        message.append(TlsTrustStore.details(certificate));
        if (pinnable) {
            message.append("\n\nМожно продолжить один раз или сохранить точный отпечаток только для этого узла. ")
                .append("При смене сертификата доступ снова будет заблокирован.");
        } else {
            message.append("\n\nОшибки срока действия нельзя сохранить как постоянное доверие. Допускается только одноразовое продолжение.");
        }

        final String[] actions;
        if (changed && pinnable) {
            actions = new String[]{"Заменить доверенный сертификат и продолжить", "Продолжить один раз", "Открыть внешним", "Закрыть"};
        } else if (pinnable && fingerprint.length() > 0) {
            actions = new String[]{"Продолжить один раз", "Доверять этому сертификату", "Открыть внешним", "Закрыть"};
        } else {
            actions = new String[]{"Продолжить один раз", "Открыть внешним", "Закрыть"};
        }

        final boolean[] resolved = {false};
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(changed ? "TLS-сертификат изменился" : "Ошибка TLS-сертификата")
            .setMessage(message.toString())
            .setItems(actions, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    String action = actions[which];
                    if (action.startsWith("Продолжить")) {
                        resolved[0] = true;
                        handler.proceed();
                    } else if (action.startsWith("Доверять") || action.startsWith("Заменить")) {
                        try {
                            TlsTrustStore.trust(WebClientActivity.this, authority, certificate);
                            resolved[0] = true;
                            handler.proceed();
                            Toast.makeText(WebClientActivity.this, "Сертификат закреплён для " + authority, Toast.LENGTH_LONG).show();
                        } catch (Exception failure) {
                            resolved[0] = true;
                            handler.cancel();
                            Toast.makeText(WebClientActivity.this, failure.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    } else if (action.startsWith("Открыть")) {
                        resolved[0] = true;
                        handler.cancel();
                        openExternal(url);
                    } else {
                        resolved[0] = true;
                        handler.cancel();
                    }
                }
            });
        tlsDialog = builder.create();
        tlsDialog.setCanceledOnTouchOutside(false);
        tlsDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) {
                if (!resolved[0]) {
                    resolved[0] = true;
                    handler.cancel();
                }
            }
        });
        tlsDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                if (!resolved[0]) {
                    resolved[0] = true;
                    handler.cancel();
                }
                tlsDialog = null;
            }
        });
        tlsDialog.show();
    }

    private boolean isPinnableTlsError(SslError error) {
        int code = error.getPrimaryError();
        return code == SslError.SSL_UNTRUSTED || code == SslError.SSL_IDMISMATCH;
    }

    private void showTlsTrust() {
        String url = webView.getUrl() == null ? address.getText().toString() : webView.getUrl();
        final String authority = TlsTrustStore.authority(url);
        final String pinned = TlsTrustStore.pinnedFingerprint(this, authority);
        if (pinned.length() == 0) {
            new AlertDialog.Builder(this)
                .setTitle("TLS-доверие")
                .setMessage("Для " + authority + " нет закреплённого сертификата.\n\nДоверие можно сохранить только из диалога TLS-ошибки после проверки SHA-256 отпечатка.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Закреплённый сертификат")
            .setMessage("Узел: " + authority + "\n\nSHA-256:\n" + pinned + "\n\nПосле удаления следующая TLS-ошибка снова потребует подтверждения.")
            .setPositiveButton("Удалить доверие", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    TlsTrustStore.clear(WebClientActivity.this, authority);
                    Toast.makeText(WebClientActivity.this, "TLS-доверие удалено", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    private void showMenu() {
        final String[] items = {"Сведения о странице и TLS", "TLS-доверие для узла", "Скопировать URL", "Сохранить подключение", "Открыть внешним приложением",
            "Выбор обработчика протокола", "JavaScript: " + (javaScript ? "включён (выключить)" : "выключен (включить)"),
            "Версия для ПК: " + (desktopMode ? "включена (выключить)" : "выключена (включить)"), "Забыть сохранённые пароли узла", "Сбросить cookies и данные сайтов", "Onion и I2P — настройки"};
        new AlertDialog.Builder(this)
            .setTitle(endpoint.displayName.length() > 0 ? endpoint.displayName : endpoint.endpointText())
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) showPageInfo();
                    else if (which == 1) showTlsTrust();
                    else if (which == 2) { ProtocolRouter.copy(WebClientActivity.this, webView.getUrl()); Toast.makeText(WebClientActivity.this, "URL скопирован", Toast.LENGTH_SHORT).show(); }
                    else if (which == 3) { endpoint.uri = webView.getUrl(); ConnectionProfileStore.save(WebClientActivity.this, endpoint); Toast.makeText(WebClientActivity.this, "Подключение сохранено", Toast.LENGTH_SHORT).show(); }
                    else if (which == 4) openExternal(webView.getUrl());
                    else if (which == 5) showHandlerChoice();
                    else if (which == 6) {
                        javaScript = !javaScript;
                        for (Tab t : tabs) if (t.view != null) t.view.getSettings().setJavaScriptEnabled(javaScript);
                        Toast.makeText(WebClientActivity.this, javaScript ? "JavaScript включён" : "JavaScript выключен", Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 7) {
                        desktopMode = !desktopMode;
                        WebSettings st = webView.getSettings();
                        Tab cur = tab();
                        if (cur != null) applyAgent(cur, cur.url);
                        st.setUseWideViewPort(true);
                        webView.reload();
                    } else if (which == 8) {
                        String host = AnonRouting.INSTANCE.hostOf(webView.getUrl() == null ? "" : webView.getUrl());
                        forgetAuth(host);
                    } else if (which == 9) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        android.webkit.WebStorage.getInstance().deleteAllData();
                        for (Tab t : tabs) if (t.view != null) t.view.clearCache(true);
                        Toast.makeText(WebClientActivity.this, "Cookies и данные сайтов удалены", Toast.LENGTH_SHORT).show();
                    } else startActivity(new Intent(WebClientActivity.this, pro.sitescope.app.ui.ToolActivity.class).putExtra("screen", "anon"));
                }
            }).show();
    }

    // ───── HTTP-авторизация (логин/пароль роутера), JS-диалоги, загрузки ─────

    private String authKey(String host, String realm) {
        return "web_auth_" + Integer.toHexString((host + "\n" + realm).hashCode()) + "_" + host.replaceAll("[^A-Za-z0-9]", "_");
    }

    private void showAuthDialog(final HttpAuthHandler handler, final String host, final String realm) {
        if (isFinishing()) { handler.cancel(); return; }
        if (authDialog != null && authDialog.isShowing()) { handler.cancel(); return; }
        final String key = authKey(host, realm);
        String savedUser = "";
        String savedPassword = "";
        try {
            String raw = new SecureStore(this).get(key);
            if (raw != null && raw.length() > 0) {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                savedUser = o.optString("user");
                savedPassword = o.optString("password");
            }
        } catch (Exception ignored) { }
        final boolean again = authTried.contains(key);
        if (!again && savedUser.length() > 0) {
            authTried.add(key);
            rememberAuthHeader(host, savedUser, savedPassword);
            handler.proceed(savedUser, savedPassword);
            return;
        }
        authTried.add(key);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        TextView info = text((again ? "Логин или пароль не подошли. " : "") + "Узел: " + host + (realm == null || realm.length() == 0 ? "" : "\nОбласть: " + realm), 13, MUTED, false);
        form.addView(info);
        final EditText user = new EditText(this);
        user.setHint("Логин");
        user.setSingleLine(true);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        user.setText(savedUser);
        form.addView(user, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText password = new EditText(this);
        password.setHint("Пароль");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(password, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final CheckBox remember = new CheckBox(this);
        remember.setText("Запомнить на этом устройстве (в защищённом хранилище)");
        remember.setTextSize(13);
        remember.setChecked(savedUser.length() > 0);
        form.addView(remember);

        final boolean[] done = {false};
        authDialog = new AlertDialog.Builder(this)
            .setTitle("Вход на узел")
            .setView(form)
            .setPositiveButton("Войти", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    done[0] = true;
                    String u = user.getText().toString();
                    String p = password.getText().toString();
                    try {
                        if (remember.isChecked()) {
                            new SecureStore(WebClientActivity.this).put(key, new org.json.JSONObject().put("user", u).put("password", p).toString());
                        } else new SecureStore(WebClientActivity.this).clear(key);
                    } catch (Exception failure) {
                        Toast.makeText(WebClientActivity.this, "Не удалось сохранить пароль", Toast.LENGTH_SHORT).show();
                    }
                    rememberAuthHeader(host, u, p);
                    handler.proceed(u, p);
                }
            })
            .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { done[0] = true; handler.cancel(); }
            })
            .create();
        authDialog.setCanceledOnTouchOutside(false);
        authDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                if (!done[0]) { done[0] = true; handler.cancel(); }
                authDialog = null;
            }
        });
        authDialog.show();
    }

    private void rememberAuthHeader(String host, String user, String password) {
        authHeaders.put(host, "Basic " + Base64.encodeToString((user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private void forgetAuth(String host) {
        if (host == null || host.length() == 0) { Toast.makeText(this, "Нет открытого узла", Toast.LENGTH_SHORT).show(); return; }
        SecureStore store = new SecureStore(this);
        java.util.Iterator<String> it = authTried.iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (key.endsWith("_" + host.replaceAll("[^A-Za-z0-9]", "_"))) { store.clear(key); it.remove(); }
        }
        authHeaders.remove(host);
        Toast.makeText(this, "Сохранённые пароли узла удалены", Toast.LENGTH_SHORT).show();
    }

    private boolean showJsDialog(String url, String message, String promptDefault, final JsResult result, final JsPromptResult prompt) {
        if (isFinishing()) { result.cancel(); return true; }
        String origin = AnonRouting.INSTANCE.hostOf(url == null ? "" : url);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(origin.length() == 0 ? "Страница" : origin).setMessage(message);
        final EditText input;
        if (prompt != null) {
            input = new EditText(this);
            input.setText(promptDefault);
            input.setSingleLine(true);
            builder.setView(input);
        } else input = null;
        final boolean[] done = {false};
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                done[0] = true;
                if (prompt != null) prompt.confirm(input.getText().toString()); else result.confirm();
            }
        });
        builder.setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) { done[0] = true; result.cancel(); }
        });
        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) { if (!done[0]) { done[0] = true; result.cancel(); } }
        });
        builder.show();
        return true;
    }

    private void startDownload(String url, String agent, String disposition, String mime) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String name = URLUtil.guessFileName(url, disposition, mime);
            request.setTitle(name);
            request.setMimeType(mime);
            request.addRequestHeader("User-Agent", agent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            String auth = authHeaders.get(Uri.parse(url).getHost());
            if (auth != null) request.addRequestHeader("Authorization", auth);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, "Загрузка: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            openExternal(url);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void showPageInfo() {
        StringBuilder message = new StringBuilder();
        message.append("URL: ").append(value(webView.getUrl())).append("\n");
        message.append("Заголовок: ").append(value(webView.getTitle())).append("\n");
        message.append("Протокол: ").append(endpoint.protocol).append("\n");
        message.append("Узел: ").append(endpoint.host).append(':').append(endpoint.port).append("\n");
        if (endpoint.tlsSubject.length() > 0) message.append("TLS subject: ").append(endpoint.tlsSubject).append("\n");
        SslCertificate certificate = webView.getCertificate();
        if (certificate != null) {
            message.append("\nСертификат\n");
            message.append("Кому: ").append(certificate.getIssuedTo().getDName()).append("\n");
            message.append("Кем: ").append(certificate.getIssuedBy().getDName()).append("\n");
            DateFormat format = DateFormat.getDateTimeInstance();
            message.append("Действует с: ").append(format.format(certificate.getValidNotBeforeDate())).append("\n");
            message.append("Действует до: ").append(format.format(certificate.getValidNotAfterDate())).append("\n");
            String fingerprint = TlsTrustStore.fingerprint(certificate);
            if (fingerprint.length() > 0) message.append("SHA-256: ").append(fingerprint).append("\n");
            String authority = TlsTrustStore.authority(webView.getUrl());
            String pinned = TlsTrustStore.pinnedFingerprint(this, authority);
            message.append("Закрепление: ").append(pinned.length() == 0 ? "нет" : (pinned.equalsIgnoreCase(fingerprint) ? "подтверждено" : "НЕ СОВПАДАЕТ")).append("\n");
        } else if (webView.getUrl() != null && webView.getUrl().startsWith("https://")) {
            message.append("\nСведения о сертификате ещё не получены.");
        }
        new AlertDialog.Builder(this).setTitle("Web Client").setMessage(message.toString()).setPositiveButton("OK", null).show();
    }

    private void showHandlerChoice() {
        final String[] values = {ProtocolPreferences.BUILTIN, ProtocolPreferences.EXTERNAL, ProtocolPreferences.PANEL};
        final String[] labels = {"Встроенный Web Client", "Внешнее приложение", "Показывать панель действий"};
        String current = ProtocolPreferences.handler(this, endpoint.protocol);
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;
        new AlertDialog.Builder(this)
            .setTitle(endpoint.protocol + " открывать через")
            .setSingleChoiceItems(labels, checked, null)
            .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    AlertDialog alert = (AlertDialog) dialog;
                    int selected = alert.getListView().getCheckedItemPosition();
                    if (selected >= 0) ProtocolPreferences.setHandler(WebClientActivity.this, endpoint.protocol, values[selected]);
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void openExternal(String url) {
        try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Открыть в браузере")); }
        catch (Exception error) { Toast.makeText(this, "Внешний браузер не найден", Toast.LENGTH_LONG).show(); }
    }

    private String sslErrorMessage(SslError error) {
        switch (error.getPrimaryError()) {
            case SslError.SSL_DATE_INVALID: return "Некорректная дата сертификата";
            case SslError.SSL_EXPIRED: return "Сертификат истёк";
            case SslError.SSL_IDMISMATCH: return "Имя узла не совпадает с сертификатом";
            case SslError.SSL_NOTYETVALID: return "Сертификат ещё не действует";
            case SslError.SSL_UNTRUSTED: return "Центр сертификации не является доверенным";
            default: return "Не удалось подтвердить сертификат";
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() {
        saveTabs();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        saveTabs();
        if (tabsDialog != null && tabsDialog.isShowing()) tabsDialog.dismiss();
        if (tlsDialog != null && tlsDialog.isShowing()) tlsDialog.dismiss();
        if (authDialog != null && authDialog.isShowing()) authDialog.dismiss();
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        for (Tab t : tabs) { t.token++; destroyView(t); }
        webView = null;
        AnonNet.releaseWebView();
        super.onDestroy();
    }

    private TextView button(String value) {
        TextView view = text(value, 20, NAVY, false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0));
        return view;
    }

    private TextView navButton(String icon, String title) {
        TextView view = text(icon + "\n" + title, 10, MUTED, true);
        view.setGravity(Gravity.CENTER);
        view.setLines(2);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0 && stroke != Color.TRANSPARENT) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String value(String value) { return value == null || value.length() == 0 ? "—" : value; }
}
