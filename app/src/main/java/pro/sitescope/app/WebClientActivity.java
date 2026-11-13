package pro.sitescope.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import pro.sitescope.app.anon.AnonNet;
import pro.sitescope.app.anon.AnonRouting;

import java.text.DateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Compact built-in browser for discovered HTTP/HTTPS services. */
public final class WebClientActivity extends Activity {
    private static final int NAVY = Color.rgb(43, 38, 34);
    private static final int MUTED = Color.rgb(133, 122, 112);
    private static final int PRIMARY = Color.rgb(217, 83, 43);
    private static final int PRIMARY_SOFT = Color.rgb(255, 232, 222);
    private static final int BORDER = Color.rgb(233, 223, 212);

    private ServiceEndpoint endpoint;
    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private AlertDialog tlsDialog;
    private AlertDialog authDialog;
    private int navToken;
    private boolean desktopMode;
    private boolean javaScript = true;
    private String mobileAgent = "";
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_REQUEST = 4711;
    private final Set<String> authTried = new HashSet<String>();
    private final Map<String, String> authHeaders = new HashMap<String, String>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        AnonNet.init(this);
        endpoint = ServiceEndpoint.from(getIntent());
        buildUi();
        String direct = getIntent().getStringExtra("open_url");
        String initial;
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
        } else {
            initial = endpoint.uri.length() > 0 ? endpoint.uri : ServiceEndpoint.buildUri(endpoint.protocol, endpoint.host, endpoint.port, endpoint.location);
            if (!initial.startsWith("http://") && !initial.startsWith("https://")) initial = "http://" + initial;
        }
        address.setText(initial);
        navigate(initial);
    }

    /** Единая точка загрузки: для .onion/.i2p сначала поднимает Tor/I2P и прокси WebView, затем открывает адрес. */
    private void navigate(String raw) {
        final String url = AnonRouting.INSTANCE.normalize(raw);
        if (url.length() == 0) return;
        final int token = ++navToken;
        AnonNet.prepare(this, url, new AnonNet.Listener() {
            @Override public void status(String text) {
                if (token == navToken && !isFinishing()) showStatusPage(url, text);
            }
            @Override public void ready() {
                if (token == navToken && !isFinishing()) webView.loadUrl(url);
            }
            @Override public void failed(String text) {
                if (token == navToken && !isFinishing()) showErrorPage(url, text);
            }
        });
    }

    private boolean needsPrepare(String url) {
        String host = AnonRouting.INSTANCE.hostOf(url);
        AnonRouting.Route route = AnonRouting.INSTANCE.routeOf(host, AnonNet.INSTANCE.settings().getTorForAll());
        boolean needProxy = route != AnonRouting.Route.DIRECT || AnonNet.directViaDpi();
        return needProxy && !AnonNet.INSTANCE.getState().getValue().getWebViewProxyOn();
    }

    private String page(String title, String body) {
        return "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
            + "<body style=\"font-family:sans-serif;margin:0;padding:28px 22px;color:#2b2622;background:#fff\"><h3 style=\"margin-top:0\">"
            + TextUtils.htmlEncode(title) + "</h3>" + body + "</body></html>";
    }

    private void showStatusPage(String url, String text) {
        webView.loadDataWithBaseURL(null, page("Подготовка", "<p style=\"color:#857a70\">" + TextUtils.htmlEncode(url) + "</p><p>" + TextUtils.htmlEncode(text) + "</p>"),
            "text/html", "utf-8", null);
    }

    private void showErrorPage(String url, String text) {
        String retry = "<p><a href=\"" + TextUtils.htmlEncode(url) + "\" style=\"color:#d9532b;font-weight:bold\">Повторить</a></p>";
        webView.loadDataWithBaseURL(url, page("Не удалось открыть страницу",
            "<p style=\"color:#857a70;word-break:break-all\">" + TextUtils.htmlEncode(url) + "</p><p style=\"white-space:pre-wrap\">" + TextUtils.htmlEncode(text) + "</p>" + retry),
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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(6));

        TextView close = button("‹");
        close.setTextSize(26);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        top.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(13);
        address.setTextColor(NAVY);
        address.setHintTextColor(MUTED);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setBackground(round(Color.rgb(247, 249, 252), 12, BORDER, 1));
        address.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                loadTypedAddress();
                return true;
            }
        });
        top.addView(address, margin(0, dp(42), 5, 0, 5, 0, 1f));

        TextView reload = button("↻");
        reload.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { String current = webView.getUrl(); if (current == null || current.startsWith("data:") || current.equals("about:blank")) navigate(address.getText().toString()); else webView.reload(); } });
        top.addView(reload, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView menu = button("⋮");
        menu.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showMenu(); } });
        top.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(top);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
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
        mobileAgent = settings.getUserAgentString().replace("; wv", "");
        settings.setUserAgentString(mobileAgent);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);
        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String agent, String disposition, String mime, long length) { startDownload(url, agent, disposition, mime); }
        });
        webView.setWebChromeClient(new WebChromeClient() {
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
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (url != null && !url.startsWith("data:") && !url.equals("about:blank")) address.setText(url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (url != null && !url.startsWith("data:") && !url.equals("about:blank")) address.setText(url);
            }
            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                showAuthDialog(handler, host, realm);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !String.valueOf(error.getDescription()).contains("ERR_ABORTED")) showErrorPage(request.getUrl().toString(), errorText(error.getErrorCode(), String.valueOf(error.getDescription())));
            }
            @Override public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                handleSslError(handler, error);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(4), dp(8), dp(8));
        bottom.setBackgroundColor(Color.WHITE);
        TextView back = navButton("‹", "Назад");
        TextView forward = navButton("›", "Вперёд");
        TextView home = navButton("⌂", "Узел");
        TextView external = navButton("↗", "Внешний");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (webView.canGoBack()) webView.goBack(); else finish(); } });
        forward.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (webView.canGoForward()) webView.goForward(); } });
        home.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { navigate(endpoint.uri); } });
        external.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openExternal(webView.getUrl()); } });
        bottom.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1f));
        bottom.addView(forward, new LinearLayout.LayoutParams(0, dp(48), 1f));
        bottom.addView(home, new LinearLayout.LayoutParams(0, dp(48), 1f));
        bottom.addView(external, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(bottom);
        setContentView(root);
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (needsPrepare(uri.toString())) { navigate(uri.toString()); return true; }
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
            "Версия для ПК: " + (desktopMode ? "включена (выключить)" : "выключена (включить)"), "Забыть сохранённые пароли узла", "Onion и I2P — настройки"};
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
                        webView.getSettings().setJavaScriptEnabled(javaScript);
                        Toast.makeText(WebClientActivity.this, javaScript ? "JavaScript включён" : "JavaScript выключен", Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 7) {
                        desktopMode = !desktopMode;
                        WebSettings st = webView.getSettings();
                        st.setUserAgentString(desktopMode ? "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" : mobileAgent);
                        st.setUseWideViewPort(true);
                        webView.reload();
                    } else if (which == 8) {
                        String host = AnonRouting.INSTANCE.hostOf(webView.getUrl() == null ? "" : webView.getUrl());
                        forgetAuth(host);
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

    @Override protected void onDestroy() {
        navToken++;
        if (tlsDialog != null && tlsDialog.isShowing()) tlsDialog.dismiss();
        if (authDialog != null && authDialog.isShowing()) authDialog.dismiss();
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
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
