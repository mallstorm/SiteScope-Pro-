package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;

/** Compact built-in browser for discovered HTTP/HTTPS services. */
public final class WebClientActivity extends Activity {
    private static final int NAVY = Color.rgb(22, 32, 51);
    private static final int MUTED = Color.rgb(104, 116, 137);
    private static final int PRIMARY = Color.rgb(13, 99, 229);
    private static final int PRIMARY_SOFT = Color.rgb(231, 240, 255);
    private static final int BORDER = Color.rgb(222, 229, 239);

    private ServiceEndpoint endpoint;
    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private AlertDialog tlsDialog;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        endpoint = ServiceEndpoint.from(getIntent());
        buildUi();
        String initial = endpoint.uri.length() > 0 ? endpoint.uri : ServiceEndpoint.buildUri(endpoint.protocol, endpoint.host, endpoint.port, endpoint.location);
        if (!initial.startsWith("http://") && !initial.startsWith("https://")) initial = "http://" + initial;
        address.setText(initial);
        webView.loadUrl(initial);
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
        reload.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { webView.reload(); } });
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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient() {
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
            @Override public void onPageFinished(WebView view, String url) {
                address.setText(url);
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
        home.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { webView.loadUrl(endpoint.uri); } });
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
        if ("http".equals(scheme) || "https".equals(scheme)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
            Toast.makeText(this, "Нет приложения для ссылки " + scheme, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private void loadTypedAddress() {
        String value = address.getText().toString().trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        webView.loadUrl(value);
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
        new AlertDialog.Builder(this)
            .setTitle(endpoint.displayName.length() > 0 ? endpoint.displayName : endpoint.endpointText())
            .setItems(new String[]{"Сведения о странице и TLS", "TLS-доверие для узла", "Скопировать URL", "Сохранить подключение", "Открыть внешним приложением", "Выбор обработчика протокола"}, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) showPageInfo();
                    else if (which == 1) showTlsTrust();
                    else if (which == 2) { ProtocolRouter.copy(WebClientActivity.this, webView.getUrl()); Toast.makeText(WebClientActivity.this, "URL скопирован", Toast.LENGTH_SHORT).show(); }
                    else if (which == 3) { endpoint.uri = webView.getUrl(); ConnectionProfileStore.save(WebClientActivity.this, endpoint); Toast.makeText(WebClientActivity.this, "Подключение сохранено", Toast.LENGTH_SHORT).show(); }
                    else if (which == 4) openExternal(webView.getUrl());
                    else showHandlerChoice();
                }
            }).show();
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
        if (tlsDialog != null && tlsDialog.isShowing()) tlsDialog.dismiss();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
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
