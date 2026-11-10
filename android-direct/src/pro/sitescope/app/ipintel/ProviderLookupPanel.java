package pro.sitescope.app.ipintel;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProviderLookupPanel extends LinearLayout {
    private static final int NAVY = Color.rgb(22, 32, 51);
    private static final int MUTED = Color.rgb(104, 116, 137);
    private static final int PRIMARY = Color.rgb(13, 99, 229);
    private static final int PRIMARY_SOFT = Color.rgb(231, 240, 255);
    private static final int GREEN = Color.rgb(20, 145, 93);
    private static final int GREEN_SOFT = Color.rgb(228, 247, 238);
    private static final int AMBER = Color.rgb(192, 116, 8);
    private static final int AMBER_SOFT = Color.rgb(255, 245, 222);
    private static final int RED = Color.rgb(205, 50, 63);
    private static final int BORDER = Color.rgb(222, 229, 239);
    private static final int SURFACE = Color.rgb(248, 243, 235);

    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout currentArea;
    private final LinearLayout searchArea;
    private final EditText searchInput;
    private String lastCsv = "";
    private volatile boolean closed;

    public ProviderLookupPanel(Context context) {
        super(context);
        if (!(context instanceof Activity)) throw new IllegalArgumentException("ProviderLookupPanel требует Activity context");
        activity = (Activity) context;
        setOrientation(VERTICAL);

        LinearLayout main = card(17);
        main.addView(text("🏢 Provider Intelligence", 17, NAVY, true));
        main.addView(text("Определение текущего провайдера и поиск диапазонов по бренду, юридическому имени или ASN.", 13, MUTED, false), params(-1, -2, 0, 6, 0, 10));

        TextView detect = primaryButton("Определить моего провайдера");
        main.addView(detect, new LayoutParams(-1, dp(46)));
        currentArea = vertical();
        main.addView(currentArea, params(-1, -2, 0, 10, 0, 0));

        main.addView(divider(), params(-1, dp(1), 0, 14, 0, 14));
        main.addView(text("Поиск провайдера", 15, NAVY, true));
        searchInput = input("Интертелеком, Intertelecom, юридическое имя или AS31343");
        main.addView(searchInput, params(-1, dp(48), 0, 7, 0, 8));
        LinearLayout actions = row();
        TextView search = primaryButton("Найти диапазоны");
        TextView export = outlineButton("CSV");
        actions.addView(search, new LayoutParams(0, dp(44), 1f));
        actions.addView(space(7));
        actions.addView(export, new LayoutParams(0, dp(44), 0.38f));
        main.addView(actions);
        searchArea = vertical();
        main.addView(searchArea, params(-1, -2, 0, 10, 0, 0));
        addView(main);

        addView(info("Диапазоны загружаются онлайн из RIPEstat и RIPE Database, а не хранятся жёстко. Подсказка «Интертелеком → AS31343» обязательно перепроверяется онлайн. Одноимённые организации не объединяются автоматически."));

        detect.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { detectCurrent(); } });
        search.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { search(); } });
        export.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { shareCsv(); } });
    }

    public void shutdown() {
        closed = true;
        worker.shutdownNow();
    }

    private void detectCurrent() {
        currentArea.removeAllViews();
        currentArea.addView(loading("Определяем транспорт, внешний IP и BGP-префиксы…"));
        final Transport transport = detectTransport();
        worker.execute(new Runnable() {
            @Override public void run() {
                final ProviderApiClient.CurrentProvider result = ProviderApiClient.currentProvider();
                ui(new Runnable() { @Override public void run() { renderCurrent(transport, result); } });
            }
        });
    }

    private void search() {
        final String query = searchInput.getText().toString().trim();
        if (query.length() == 0) { searchInput.setError("Введите название провайдера, юридическое имя или ASN"); return; }
        searchArea.removeAllViews();
        searchArea.addView(loading("Ищем организации, ASN и актуальные префиксы…"));
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<ProviderApiClient.ProviderCandidate> results = ProviderApiClient.search(query);
                    ui(new Runnable() { @Override public void run() { renderSearch(query, results); } });
                } catch (final Exception error) {
                    ui(new Runnable() { @Override public void run() { showError(searchArea, message(error)); } });
                }
            }
        });
    }

    private void renderCurrent(Transport transport, ProviderApiClient.CurrentProvider result) {
        currentArea.removeAllViews();
        LinearLayout status = compactCard();
        status.addView(text("Подключение", 14, NAVY, true));
        status.addView(metric("Транспорт", transport.name));
        status.addView(metric("VPN", transport.vpn ? "Обнаружен" : "Нет"));
        if (transport.vpn) status.addView(warning("Показан провайдер выходного VPN-адреса, а не исходной сети устройства."));
        status.addView(metric("Внешний IPv4", empty(result.addresses.ipv4)));
        status.addView(metric("Внешний IPv6", result.addresses.ipv6.length() > 0 ? result.addresses.ipv6 : "Не обнаружен — это не ошибка"));
        currentArea.addView(status);

        List<ProviderApiClient.ProviderCandidate> exportCandidates = new ArrayList<ProviderApiClient.ProviderCandidate>();
        if (result.ipv4Provider != null) {
            currentArea.addView(candidateCard("IPv4-провайдер", result.ipv4Provider, result.addresses.ipv4));
            exportCandidates.add(result.ipv4Provider);
        }
        if (result.ipv6Provider != null) {
            boolean same = result.ipv4Provider != null && result.ipv4Provider.asn.equals(result.ipv6Provider.asn);
            if (!same) currentArea.addView(candidateCard("IPv6-провайдер", result.ipv6Provider, result.addresses.ipv6));
            exportCandidates.add(result.ipv6Provider);
        }
        if (result.error.length() > 0) currentArea.addView(error(result.error));
        lastCsv = buildCsv("Мой провайдер", exportCandidates, result.addresses.ipv4, result.addresses.ipv6, transport);
    }

    private void renderSearch(String query, List<ProviderApiClient.ProviderCandidate> results) {
        searchArea.removeAllViews();
        if (results == null || results.isEmpty()) {
            searchArea.addView(error("Провайдер или ASN не найден. Попробуйте юридическое имя или прямой ASN."));
            return;
        }
        int count = 0;
        List<ProviderApiClient.ProviderCandidate> valid = new ArrayList<ProviderApiClient.ProviderCandidate>();
        for (ProviderApiClient.ProviderCandidate candidate : results) {
            if (candidate.asn.length() == 0) {
                if (candidate.error.length() > 0) searchArea.addView(error(candidate.error));
                continue;
            }
            searchArea.addView(candidateCard(candidate.hint ? "Подсказка бренда · проверено онлайн" : "Найденный ASN", candidate, ""));
            valid.add(candidate);
            count++;
        }
        if (count == 0) searchArea.addView(error("RIPE Database не вернула подходящих ASN."));
        lastCsv = buildCsv(query, valid, "", "", null);
    }

    private LinearLayout candidateCard(String caption, ProviderApiClient.ProviderCandidate candidate, String currentIp) {
        LinearLayout card = compactCard();
        LinearLayout head = row();
        LinearLayout title = vertical();
        title.addView(text(caption, 11, candidate.hint ? AMBER : PRIMARY, true));
        title.addView(text(candidate.asn + " · " + candidate.displayName(), 15, NAVY, true));
        head.addView(title, new LayoutParams(0, -2, 1f));
        TextView status = badge(candidate.announced ? "Анонсируется" : "Статус не подтверждён", candidate.announced ? GREEN : AMBER, candidate.announced ? GREEN_SOFT : AMBER_SOFT);
        head.addView(status);
        card.addView(head);
        if (candidate.asName.length() > 0) card.addView(metric("AS name", candidate.asName));
        if (candidate.organizationId.length() > 0) card.addView(metric("Organisation", candidate.organizationId));
        if (candidate.organizationName.length() > 0) card.addView(metric("Юридическое имя", candidate.organizationName));
        if (candidate.country.length() > 0) card.addView(metric("Страна", candidate.country));
        if (candidate.source.length() > 0) card.addView(metric("Источник / RIR", candidate.source));
        if (candidate.error.length() > 0) card.addView(warning("Часть данных недоступна: " + candidate.error));

        if (candidate.prefixes.isEmpty()) {
            card.addView(text("Анонсируемые IPv4/IPv6-префиксы не получены.", 12, MUTED, false), params(-1, -2, 0, 10, 0, 0));
        } else {
            card.addView(text("Диапазоны провайдера · " + candidate.prefixes.size(), 13, NAVY, true), params(-1, -2, 0, 11, 0, 4));
            int shown = Math.min(250, candidate.prefixes.size());
            for (int i = 0; i < shown; i++) {
                String prefix = candidate.prefixes.get(i);
                try {
                    ProviderCore.CidrRange range = ProviderCore.range(prefix);
                    boolean active = currentIp.length() > 0 && range.contains(currentIp);
                    card.addView(prefixRow(range, active));
                } catch (Exception ignored) { }
            }
            if (candidate.prefixes.size() > shown) card.addView(text("Ещё " + (candidate.prefixes.size() - shown) + " диапазонов доступны в CSV-экспорте.", 11, MUTED, false));
        }
        return card;
    }

    private View prefixRow(final ProviderCore.CidrRange range, boolean active) {
        LinearLayout line = row();
        line.setPadding(dp(10), dp(8), dp(10), dp(8));
        line.setBackground(round(active ? GREEN_SOFT : Color.WHITE, 10, active ? GREEN : BORDER, 1));
        LinearLayout values = vertical();
        values.addView(text((active ? "● Текущий диапазон · " : "") + range.cidr, 12, active ? GREEN : PRIMARY, true));
        values.addView(text(range.first + " — " + range.last, 11, MUTED, false));
        values.addView(text(range.count.toString() + " адресов", 11, MUTED, false));
        line.addView(values, new LayoutParams(0, -2, 1f));
        TextView copy = text("Копировать", 11, PRIMARY, true);
        copy.setGravity(Gravity.CENTER);
        line.addView(copy, new LayoutParams(dp(76), dp(36)));
        line.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { copy(range.cidr); } });
        LayoutParams params = params(-1, -2, 0, 4, 0, 0);
        line.setLayoutParams(params);
        return line;
    }

    private String buildCsv(String query, List<ProviderApiClient.ProviderCandidate> candidates, String ipv4, String ipv6, Transport transport) {
        StringBuilder csv = new StringBuilder();
        csv.append("query,transport,vpn,public_ipv4,public_ipv6,asn,holder,organisation_id,organisation_name,country,announced,cidr,first_ip,last_ip,address_count\n");
        if (candidates == null) return csv.toString();
        for (ProviderApiClient.ProviderCandidate candidate : candidates) {
            if (candidate == null) continue;
            if (candidate.prefixes.isEmpty()) {
                csv.append(csvLine(query, transport, ipv4, ipv6, candidate, null));
            } else {
                for (String prefix : candidate.prefixes) {
                    try { csv.append(csvLine(query, transport, ipv4, ipv6, candidate, ProviderCore.range(prefix))); }
                    catch (Exception ignored) { }
                }
            }
        }
        return csv.toString();
    }

    private String csvLine(String query, Transport transport, String ipv4, String ipv6, ProviderApiClient.ProviderCandidate candidate, ProviderCore.CidrRange range) {
        String[] values = {
            query,
            transport == null ? "" : transport.name,
            transport == null ? "" : String.valueOf(transport.vpn),
            ipv4, ipv6, candidate.asn, candidate.displayName(), candidate.organizationId,
            candidate.organizationName, candidate.country, String.valueOf(candidate.announced),
            range == null ? "" : range.cidr, range == null ? "" : range.first,
            range == null ? "" : range.last, range == null ? "" : range.count.toString()
        };
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) line.append(',');
            line.append(ProviderCore.csvEscape(values[i]));
        }
        return line.append('\n').toString();
    }

    private void shareCsv() {
        if (lastCsv.length() == 0) {
            Toast.makeText(activity, "Сначала выполните определение или поиск провайдера", Toast.LENGTH_LONG).show();
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_SUBJECT, "SiteScope Pro — диапазоны провайдера.csv");
        share.putExtra(Intent.EXTRA_TEXT, lastCsv);
        activity.startActivity(Intent.createChooser(share, "Экспорт CSV"));
    }

    private Transport detectTransport() {
        Transport output = new Transport();
        try {
            ConnectivityManager manager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities caps = manager == null || network == null ? null : manager.getNetworkCapabilities(network);
            if (caps == null) { output.name = "Нет активной сети"; return output; }
            output.vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) output.name = "Wi‑Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) output.name = "Мобильная сеть";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) output.name = "Ethernet";
            else if (output.vpn) output.name = "VPN";
            else output.name = "Другая сеть";
            if (output.vpn && !output.name.contains("VPN")) output.name += " через VPN";
        } catch (Exception error) { output.name = "Не определён"; }
        return output;
    }

    private static final class Transport { String name = "Не определён"; boolean vpn; }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("CIDR", value));
        Toast.makeText(activity, "Скопировано: " + value, Toast.LENGTH_SHORT).show();
    }

    private void ui(Runnable action) {
        if (!closed) activity.runOnUiThread(action);
    }

    private void showError(LinearLayout area, String value) { area.removeAllViews(); area.addView(error(value)); }
    private String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private String empty(String value) { return value == null || value.length() == 0 ? "Не определён" : value; }

    private LinearLayout card(int padding) {
        LinearLayout view = vertical();
        view.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        view.setBackground(round(SURFACE, 17, BORDER, 1));
        view.setElevation(dp(2));
        view.setLayoutParams(params(-1, -2, 0, 8, 0, 4));
        return view;
    }
    private LinearLayout compactCard() {
        LinearLayout view = vertical();
        view.setPadding(dp(13), dp(13), dp(13), dp(13));
        view.setBackground(round(Color.WHITE, 13, BORDER, 1));
        view.setLayoutParams(params(-1, -2, 0, 8, 0, 0));
        return view;
    }
    private LinearLayout vertical() { LinearLayout v = new LinearLayout(activity); v.setOrientation(VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setOrientation(HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private View space(int width) { View v = new View(activity); v.setLayoutParams(new LayoutParams(dp(width), 1)); return v; }
    private View divider() { View v = new View(activity); v.setBackgroundColor(BORDER); return v; }
    private TextView text(String value, int size, int color, boolean bold) { TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(0, 1.08f); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private EditText input(String hint) { EditText v = new EditText(activity); v.setHint(hint); v.setTextColor(NAVY); v.setHintTextColor(Color.rgb(145,155,171)); v.setTextSize(14); v.setSingleLine(true); v.setPadding(dp(12),0,dp(12),0); v.setBackground(round(Color.WHITE, 12, BORDER, 1)); return v; }
    private TextView button(String title, boolean primary) { TextView v = text(title, 13, primary ? Color.WHITE : PRIMARY, true); v.setGravity(Gravity.CENTER); v.setBackground(round(primary ? PRIMARY : Color.WHITE, 12, primary ? Color.TRANSPARENT : BORDER, primary ? 0 : 1)); v.setClickable(true); return v; }
    private TextView primaryButton(String title) { return button(title, true); }
    private TextView outlineButton(String title) { return button(title, false); }
    private TextView metric(String key, String value) { TextView v = text(key + ":  " + value, 12, NAVY, false); v.setPadding(0, dp(5), 0, dp(5)); return v; }
    private TextView badge(String title, int color, int background) { TextView v = text(title, 10, color, true); v.setPadding(dp(8),dp(5),dp(8),dp(5)); v.setGravity(Gravity.CENTER); v.setBackground(round(background, 999, Color.TRANSPARENT, 0)); return v; }
    private TextView info(String value) { TextView v = text(value, 12, Color.rgb(49,78,123), false); v.setPadding(dp(13),dp(11),dp(13),dp(11)); v.setBackground(round(PRIMARY_SOFT, 12, Color.TRANSPARENT, 0)); v.setLayoutParams(params(-1,-2,0,6,0,8)); return v; }
    private TextView warning(String value) { TextView v = text(value, 12, AMBER, false); v.setPadding(dp(11),dp(9),dp(11),dp(9)); v.setBackground(round(AMBER_SOFT, 10, Color.TRANSPARENT, 0)); v.setLayoutParams(params(-1,-2,0,6,0,2)); return v; }
    private TextView error(String value) { TextView v = text(value, 12, RED, false); v.setPadding(dp(11),dp(9),dp(11),dp(9)); v.setBackground(round(Color.rgb(255,234,236), 10, Color.TRANSPARENT, 0)); v.setLayoutParams(params(-1,-2,0,6,0,2)); return v; }
    private View loading(String value) { LinearLayout line = row(); ProgressBar p = new ProgressBar(activity); line.addView(p, new LayoutParams(dp(34),dp(34))); line.addView(text(value, 12, MUTED, false), params(0,-2,10,0,0,0,1f)); line.setPadding(0,dp(8),0,dp(8)); return line; }
    private GradientDrawable round(int color, int radius, int strokeColor, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (stroke > 0) d.setStroke(dp(stroke), strokeColor); return d; }
    private LayoutParams params(int width, int height, int left, int top, int right, int bottom) { LayoutParams p = new LayoutParams(width,height); p.setMargins(dp(left),dp(top),dp(right),dp(bottom)); return p; }
    private LayoutParams params(int width, int height, int left, int top, int right, int bottom, float weight) { LayoutParams p = new LayoutParams(width,height,weight); p.setMargins(dp(left),dp(top),dp(right),dp(bottom)); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
