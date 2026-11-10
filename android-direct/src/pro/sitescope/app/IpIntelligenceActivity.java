package pro.sitescope.app;

import pro.sitescope.app.ipintel.ProviderLookupPanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public final class IpIntelligenceActivity extends Activity {
    private static final int PICK_CITY = 8201;
    private static final int PICK_ASN = 8202;

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
    private static final int BACKGROUND = Color.WHITE;

    private final AtomicBoolean cancelWork = new AtomicBoolean(false);
    private IpDataStore database;
    private LinearLayout content;
    private LinearLayout tabs;
    private int currentTab;
    private String lastExport = "";
    private TextView databaseStatus;
    private TextView databaseHealthBadge;
    private TextView databaseLastCheck;
    private TextView cityHealthBadge;
    private TextView asnHealthBadge;
    private TextView cityDatabaseDetails;
    private TextView asnDatabaseDetails;
    private TextView cityPrimaryAction;
    private TextView asnPrimaryAction;
    private TextView databaseCancelAction;
    private ProgressBar databaseProgress;
    private TextView databaseProgressText;
    private volatile boolean validationRunning;
    private volatile boolean databaseWorkRunning;
    private ProviderLookupPanel providerLookupPanel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        database = new IpDataStore(this);
        recoverDownloadedMetadata();
        buildShell();
        showTab(0);
    }

    @Override protected void onDestroy() {
        cancelWork.set(true);
        if (providerLookupPanel != null) providerLookupPanel.shutdown();
        try { database.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void recoverDownloadedMetadata() {
        File directory = new File(getFilesDir(), "ip-intelligence");
        File[] files = directory.listFiles();
        if (files == null) return;
        File city = null;
        File asn = null;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".part")) continue;
            if (name.startsWith("dbip-city-lite-") && name.endsWith(".csv.gz") && (city == null || name.compareTo(city.getName().toLowerCase(Locale.US)) > 0)) city = file;
            if (name.startsWith("dbip-asn-lite-") && name.endsWith(".csv.gz") && (asn == null || name.compareTo(asn.getName().toLowerCase(Locale.US)) > 0)) asn = file;
        }
        if (city != null) database.recoverSourceMetadata(true, city.getName(), city.lastModified());
        if (asn != null) database.recoverSourceMetadata(false, asn.getName(), asn.lastModified());
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout header = row();
        header.setPadding(dp(14), dp(12), dp(14), dp(8));
        TextView back = button("‹", false);
        back.setTextSize(27);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(dp(10), 0, 0, 0);
        title.addView(text("🧭 IP Intelligence", 22, NAVY, true));
        title.addView(text("География, ASN, провайдеры и диапазоны", 12, MUTED, false));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView export = button("Экспорт", false);
        export.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { exportLast(); } });
        header.addView(export, new LinearLayout.LayoutParams(dp(82), dp(42)));
        root.addView(header);

        tabs = row();
        tabs.setPadding(dp(12), dp(4), dp(12), dp(8));
        String[] names = {"IP / ASN", "Каталог", "База", "Калькуляторы"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView item = text(names[i], 12, MUTED, true);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(5), 0, dp(5), 0);
            item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showTab(index); } });
            tabs.addView(item, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        root.addView(tabs);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void showTab(int index) {
        currentTab = index;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView item = (TextView) tabs.getChildAt(i);
            boolean selected = i == index;
            item.setTextColor(selected ? PRIMARY : MUTED);
            item.setBackground(round(selected ? PRIMARY_SOFT : Color.TRANSPARENT, 12, Color.TRANSPARENT, 0));
        }
        content.removeAllViews();
        if (index == 0) content.addView(lookupView());
        else if (index == 1) content.addView(catalogView());
        else if (index == 2) content.addView(databaseView());
        else content.addView(calculatorsView());
    }

    private View lookupView() {
        final LinearLayout page = page();
        page.addView(info("Пассивная идентификация IP, префикса, домена или ASN. Локальная DB‑IP дополняется онлайн-данными RIPEstat, RDAP, WHOIS, маршрутизацией и abuse-контактами."));

        providerLookupPanel = new ProviderLookupPanel(this);
        page.addView(providerLookupPanel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(section("Точечный IP / ASN lookup", "Отдельная проверка заданного адреса, домена, префикса или автономной системы"));

        LinearLayout form = card();
        form.addView(caption("IP, домен, префикс или ASN"));
        final EditText input = input("8.8.8.8, example.com, 2001:db8::/32 или AS15169");
        form.addView(input, params(-1, dp(50), 0, 7, 0, 10));
        TextView run = primaryButton("Получить полную информацию");
        form.addView(run, new LinearLayout.LayoutParams(-1, dp(48)));
        page.addView(form);

        final LinearLayout result = vertical();
        page.addView(result);
        run.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String value = input.getText().toString().trim();
                if (value.length() == 0) { input.setError("Введите IP, домен, префикс или ASN"); return; }
                result.removeAllViews();
                result.addView(loading("Получаем данные RIPEstat, RDAP и локальной базы…"));
                new Thread(new Runnable() {
                    @Override public void run() {
                        String resolved = value;
                        String resolution = "";
                        if (!looksLikeIpOrAsn(value)) {
                            try {
                                InetAddress[] addresses = InetAddress.getAllByName(value);
                                if (addresses.length > 0) {
                                    resolved = addresses[0].getHostAddress();
                                    StringBuilder all = new StringBuilder();
                                    for (InetAddress address : addresses) {
                                        if (all.length() > 0) all.append(", ");
                                        all.append(address.getHostAddress());
                                    }
                                    resolution = all.toString();
                                }
                            } catch (Exception ignored) { }
                        }
                        final String resource = resolved;
                        final String dns = resolution;
                        final IpDataStore.GeoRange localGeo = isIpAddress(resource) ? database.lookupGeo(stripPrefix(resource)) : null;
                        final IpDataStore.AsnRange localAsn = isIpAddress(resource) ? database.lookupAsn(stripPrefix(resource)) : null;
                        final RipeStatClient.Lookup online = RipeStatClient.lookup(resource);
                        saveHistory(value);
                        runOnUiThread(new Runnable() { @Override public void run() { renderLookup(result, value, resource, dns, localGeo, localAsn, online); } });
                    }
                }).start();
            }
        });

        List<String> history = history();
        if (!history.isEmpty()) {
            page.addView(section("Недавние запросы", "Хранятся локально на устройстве"));
            LinearLayout recent = card();
            for (final String value : history) {
                TextView row = text("↻  " + value, 13, PRIMARY, true);
                row.setPadding(0, dp(9), 0, dp(9));
                row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { input.setText(value); } });
                recent.addView(row);
            }
            page.addView(recent);
        }
        return scroll(page);
    }

    private void renderLookup(LinearLayout area, String requested, String resource, String dns, IpDataStore.GeoRange localGeo, IpDataStore.AsnRange localAsn, RipeStatClient.Lookup online) {
        area.removeAllViews();
        LinearLayout summary = card();
        summary.addView(text(resource, 20, NAVY, true));
        if (dns.length() > 0) summary.addView(metric("DNS", dns));
        if (online.prefix.length() > 0) summary.addView(metric("Префикс", online.prefix));
        String asn = online.asn.length() > 0 ? online.asn : (localAsn == null ? "" : "AS" + localAsn.asn);
        if (asn.length() > 0) summary.addView(metric("ASN", asn));
        String holder = online.holder.length() > 0 ? online.holder : (localAsn == null ? "" : localAsn.organization);
        if (holder.length() > 0) summary.addView(metric("Организация", holder));
        summary.addView(metric("Маршрутизируется", online.announced ? "Да" : "Не подтверждено"));
        if (online.rir.length() > 0) summary.addView(metric("RIR / блок", online.rir));
        if (online.rdapHandle.length() > 0) summary.addView(metric("RDAP handle", online.rdapHandle));
        area.addView(summary);

        if (localGeo != null || online.country.length() > 0) {
            LinearLayout geo = card();
            geo.addView(text("📍 География", 16, NAVY, true));
            String country = localGeo != null && localGeo.country.length() > 0 ? localGeo.country + " (" + localGeo.countryCode + ")" : online.country;
            String region = localGeo != null ? localGeo.region : online.region;
            String city = localGeo != null ? localGeo.city : online.city;
            double lat = localGeo != null && localGeo.latitude != 0 ? localGeo.latitude : online.latitude;
            double lon = localGeo != null && localGeo.longitude != 0 ? localGeo.longitude : online.longitude;
            geo.addView(metric("Страна", empty(country)));
            geo.addView(metric("Регион", empty(region)));
            geo.addView(metric("Город", empty(city)));
            if (lat != 0 || lon != 0) {
                geo.addView(metric("Координаты", decimal(lat) + ", " + decimal(lon)));
                TextView map = outlineButton("Открыть приблизительную точку на карте");
                final double finalLat = lat, finalLon = lon;
                map.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openMap(finalLat, finalLon); } });
                geo.addView(map, params(-1, dp(44), 0, 10, 0, 0));
            }
            geo.addView(info("Геолокация IP приблизительна и обычно отражает узел или зарегистрированную территорию провайдера, а не точный адрес пользователя."));
            area.addView(geo);
        }

        if (localGeo != null) {
            LinearLayout local = card();
            local.addView(text("🗃 Локальный диапазон DB‑IP", 16, NAVY, true));
            local.addView(metric("Диапазон", localGeo.startIp + " — " + localGeo.endIp));
            local.addView(metric("CIDR", localGeo.cidrs()));
            if (localGeo.organization.length() > 0) local.addView(metric("Провайдер", localGeo.organization));
            area.addView(local);
        }

        if (!online.abuseContacts.isEmpty() || online.reverseDns.length() > 0) {
            LinearLayout contacts = card();
            contacts.addView(text("✉ Контакты и reverse DNS", 16, NAVY, true));
            if (online.reverseDns.length() > 0) contacts.addView(metric("Reverse DNS", online.reverseDns));
            if (!online.abuseContacts.isEmpty()) contacts.addView(metric("Abuse", join(online.abuseContacts, ", ")));
            area.addView(contacts);
        }

        if (!online.announcedPrefixes.isEmpty()) {
            LinearLayout prefixes = card();
            prefixes.addView(text("🌐 Анонсируемые префиксы", 16, NAVY, true));
            int shown = Math.min(60, online.announcedPrefixes.size());
            for (int i = 0; i < shown; i++) prefixes.addView(compactLine(online.announcedPrefixes.get(i)));
            if (online.announcedPrefixes.size() > shown) prefixes.addView(text("Ещё " + (online.announcedPrefixes.size() - shown) + " префиксов доступны в экспорте", 12, MUTED, false));
            area.addView(prefixes);
        }

        if (!online.whois.isEmpty()) {
            LinearLayout whois = card();
            whois.addView(text("📄 WHOIS", 16, NAVY, true));
            int shown = Math.min(35, online.whois.size());
            for (int i = 0; i < shown; i++) whois.addView(text(online.whois.get(i), 12, MUTED, false));
            area.addView(whois);
        }

        if (online.error.length() > 0) area.addView(errorBox("Онлайн-данные получены не полностью: " + online.error));
        StringBuilder export = new StringBuilder();
        export.append("SiteScope Pro IP Intelligence\nЗапрос: ").append(requested).append("\nРесурс: ").append(resource).append('\n');
        if (online.prefix.length() > 0) export.append("Префикс: ").append(online.prefix).append('\n');
        if (asn.length() > 0) export.append("ASN: ").append(asn).append('\n');
        if (holder.length() > 0) export.append("Организация: ").append(holder).append('\n');
        if (localGeo != null) export.append("Диапазон: ").append(localGeo.startIp).append(" - ").append(localGeo.endIp).append("\nCIDR: ").append(localGeo.cidrs()).append('\n');
        if (!online.announcedPrefixes.isEmpty()) export.append("\nАнонсируемые префиксы:\n").append(join(online.announcedPrefixes, "\n"));
        lastExport = export.toString();
    }

    private View catalogView() {
        final LinearLayout page = page();
        page.addView(info("Иерархический каталог: страна → регион → город → провайдер/ASN → диапазоны. Страны доступны онлайн через RIPEstat; регионы и города требуют локальной DB‑IP City Lite."));

        LinearLayout form = card();
        final EditText continent = input("Континент: EU, AS, NA — необязательно");
        final EditText country = input("Страна: DE или Germany");
        final EditText region = input("Регион / область — необязательно");
        final EditText city = input("Город — необязательно");
        final EditText provider = input("Провайдер, организация или ASN");
        final EditText pageNumber = input("Страница диапазонов");
        pageNumber.setText("1");
        pageNumber.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(caption("Континент")); form.addView(continent, params(-1, dp(48), 0, 6, 0, 8));
        form.addView(caption("Страна")); form.addView(country, params(-1, dp(48), 0, 6, 0, 8));
        form.addView(caption("Регион / область")); form.addView(region, params(-1, dp(48), 0, 6, 0, 8));
        form.addView(caption("Город")); form.addView(city, params(-1, dp(48), 0, 6, 0, 8));
        form.addView(caption("Провайдер / ASN")); form.addView(provider, params(-1, dp(48), 0, 6, 0, 8));
        form.addView(caption("Страница локальных диапазонов · 500 записей")); form.addView(pageNumber, params(-1, dp(44), 0, 6, 0, 8));

        LinearLayout pickers = row();
        TextView countries = smallButton("Страны");
        TextView regions = smallButton("Регионы");
        TextView cities = smallButton("Города");
        pickers.addView(countries, new LinearLayout.LayoutParams(0, dp(40), 1f)); pickers.addView(space(6));
        pickers.addView(regions, new LinearLayout.LayoutParams(0, dp(40), 1f)); pickers.addView(space(6));
        pickers.addView(cities, new LinearLayout.LayoutParams(0, dp(40), 1f));
        form.addView(pickers);

        LinearLayout actions1 = row();
        TextView countryRanges = primaryButton("Диапазоны страны");
        TextView geoRanges = outlineButton("Регион / город");
        actions1.addView(countryRanges, new LinearLayout.LayoutParams(0, dp(46), 1f)); actions1.addView(space(7));
        actions1.addView(geoRanges, new LinearLayout.LayoutParams(0, dp(46), 1f));
        form.addView(actions1, params(-1, -2, 0, 10, 0, 0));
        LinearLayout actions2 = row();
        TextView providers = outlineButton("Провайдеры территории");
        TextView providerRanges = outlineButton("Диапазоны ASN");
        actions2.addView(providers, new LinearLayout.LayoutParams(0, dp(46), 1f)); actions2.addView(space(7));
        actions2.addView(providerRanges, new LinearLayout.LayoutParams(0, dp(46), 1f));
        form.addView(actions2, params(-1, -2, 0, 7, 0, 0));
        page.addView(form);

        final LinearLayout result = vertical();
        page.addView(result);

        countries.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            List<String> values = database.countries(1000);
            if (values.isEmpty()) values = builtInCountries();
            showChoice("Страны", values, country);
        } });
        regions.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showChoice("Регионы", database.regions(country.getText().toString().trim(), 2000), region); } });
        cities.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showChoice("Города", database.cities(country.getText().toString().trim(), region.getText().toString().trim(), 3000), city); } });

        countryRanges.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String code = countryCode(country.getText().toString());
                if (code.length() != 2) { country.setError("Для онлайн-поиска укажите двухбуквенный ISO-код, например DE"); return; }
                result.removeAllViews(); result.addView(loading("Получаем зарегистрированные ресурсы страны…"));
                new Thread(new Runnable() { @Override public void run() {
                    final RipeStatClient.CountryResources resources = RipeStatClient.countryResources(code);
                    runOnUiThread(new Runnable() { @Override public void run() { renderCountryResources(result, resources); } });
                } }).start();
            }
        });

        geoRanges.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                result.removeAllViews(); result.addView(loading("Ищем диапазоны локальной базы…"));
                new Thread(new Runnable() { @Override public void run() {
                    int page = positive(pageNumber.getText().toString(), 1);
                    final int offset = (page - 1) * 500;
                    final List<IpDataStore.GeoRange> ranges = database.searchGeo(continent.getText().toString().trim(), country.getText().toString().trim(), region.getText().toString().trim(), city.getText().toString().trim(), 500, offset);
                    runOnUiThread(new Runnable() { @Override public void run() { renderGeoRanges(result, ranges, offset); } });
                } }).start();
            }
        });

        providers.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                result.removeAllViews(); result.addView(loading("Сопоставляем географию с ASN…"));
                new Thread(new Runnable() { @Override public void run() {
                    final List<IpDataStore.ProviderSummary> values = database.providersForGeo(continent.getText().toString().trim(), country.getText().toString().trim(), region.getText().toString().trim(), city.getText().toString().trim(), 2500);
                    runOnUiThread(new Runnable() { @Override public void run() { renderProviders(result, values, provider); } });
                } }).start();
            }
        });

        providerRanges.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String query = provider.getText().toString().trim();
                if (query.length() == 0) { provider.setError("Введите провайдера или ASN"); return; }
                result.removeAllViews(); result.addView(loading("Получаем диапазоны провайдера…"));
                new Thread(new Runnable() { @Override public void run() {
                    final List<IpDataStore.AsnRange> local = database.searchAsn(query, 1000, 0);
                    List<String> online = new ArrayList<String>();
                    String normalized = query.toUpperCase(Locale.US).replace("AS", "").trim();
                    if (normalized.matches("\\d+")) {
                        try { online = RipeStatClient.announcedPrefixes("AS" + normalized); } catch (Exception ignored) { }
                    }
                    final List<String> finalOnline = online;
                    runOnUiThread(new Runnable() { @Override public void run() { renderAsnRanges(result, query, local, finalOnline); } });
                } }).start();
            }
        });
        return scroll(page);
    }

    private void renderCountryResources(LinearLayout area, RipeStatClient.CountryResources value) {
        area.removeAllViews();
        if (value.error.length() > 0) { area.addView(errorBox(value.error)); return; }
        LinearLayout card = card();
        card.addView(text("🌍 " + value.countryCode + " — ресурсы страны", 17, NAVY, true));
        card.addView(metric("ASN", String.valueOf(value.asns.size())));
        card.addView(metric("IPv4-префиксы", String.valueOf(value.ipv4.size())));
        card.addView(metric("IPv6-префиксы", String.valueOf(value.ipv6.size())));
        area.addView(card);
        area.addView(prefixList("IPv4", value.ipv4, 120));
        area.addView(prefixList("IPv6", value.ipv6, 80));
        if (!value.asns.isEmpty()) area.addView(prefixList("ASN", value.asns, 80));
        lastExport = "Country " + value.countryCode + "\n\nIPv4:\n" + join(value.ipv4, "\n") + "\n\nIPv6:\n" + join(value.ipv6, "\n") + "\n\nASN:\n" + join(value.asns, "\n");
    }

    private void renderGeoRanges(LinearLayout area, List<IpDataStore.GeoRange> ranges, int offset) {
        area.removeAllViews();
        if (ranges.isEmpty()) { area.addView(emptyState("Диапазоны не найдены", "Импортируйте City Lite либо уточните страну, регион и город.")); return; }
        area.addView(section("Найдено " + ranges.size(), "Записи " + (offset + 1) + "–" + (offset + ranges.size()) + "; смените номер страницы для продолжения"));
        StringBuilder export = new StringBuilder("start_ip,end_ip,cidr,country,region,city,asn,provider\n");
        for (final IpDataStore.GeoRange range : ranges) {
            LinearLayout card = card();
            card.addView(text(range.startIp + " — " + range.endIp, 14, NAVY, true));
            String location = joinNonEmpty(range.country, range.region, range.city);
            if (location.length() > 0) card.addView(text(location, 12, MUTED, false));
            String provider = joinNonEmpty(range.asn, range.organization);
            if (provider.length() > 0) card.addView(text(provider, 12, PRIMARY, true));
            String cidr = range.cidrs();
            if (cidr.length() > 0) card.addView(text(cidr, 11, MUTED, false));
            TextView copy = smallButton("Копировать диапазон");
            copy.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copy(range.startIp + "-" + range.endIp); } });
            card.addView(copy, params(-1, dp(38), 0, 9, 0, 0));
            area.addView(card);
            export.append(csv(range.startIp)).append(',').append(csv(range.endIp)).append(',').append(csv(cidr)).append(',')
                .append(csv(range.country)).append(',').append(csv(range.region)).append(',').append(csv(range.city)).append(',')
                .append(csv(range.asn)).append(',').append(csv(range.organization)).append('\n');
        }
        lastExport = export.toString();
    }

    private void renderProviders(LinearLayout area, List<IpDataStore.ProviderSummary> providers, final EditText target) {
        area.removeAllViews();
        if (providers.isEmpty()) { area.addView(emptyState("Провайдеры не найдены", "Требуются одновременно City Lite и ASN Lite.")); return; }
        area.addView(info("Список строится сопоставлением первых 2500 географических диапазонов с ASN. Для огромных территорий это выборка, а не абсолютный рейтинг."));
        StringBuilder export = new StringBuilder("asn,provider,sampled_ranges\n");
        for (final IpDataStore.ProviderSummary item : providers) {
            TextView line = text("AS" + item.asn + "  ·  " + item.organization + "  ·  " + item.sampledRanges + " диапазонов", 13, NAVY, true);
            line.setPadding(dp(14), dp(12), dp(14), dp(12));
            line.setBackground(round(SURFACE, 13, BORDER, 1));
            line.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { target.setText("AS" + item.asn); } });
            area.addView(line, params(-1, -2, 0, 5, 0, 0));
            export.append(item.asn).append(',').append(csv(item.organization)).append(',').append(item.sampledRanges).append('\n');
        }
        lastExport = export.toString();
    }

    private void renderAsnRanges(LinearLayout area, String query, List<IpDataStore.AsnRange> local, List<String> online) {
        area.removeAllViews();
        if (local.isEmpty() && online.isEmpty()) { area.addView(emptyState("Диапазоны не найдены", "Для поиска по названию провайдера импортируйте ASN Lite. Для ASN используется также RIPEstat.")); return; }
        Set<String> prefixes = new LinkedHashSet<String>(online);
        StringBuilder export = new StringBuilder("Provider / ASN: " + query + "\n");
        if (!online.isEmpty()) {
            area.addView(prefixList("Анонсируемые префиксы RIPEstat", online, 150));
            export.append("\nAnnounced prefixes:\n").append(join(online, "\n"));
        }
        if (!local.isEmpty()) {
            LinearLayout block = card();
            block.addView(text("Локальные диапазоны ASN Lite", 16, NAVY, true));
            int shown = Math.min(150, local.size());
            for (int i = 0; i < shown; i++) {
                IpDataStore.AsnRange item = local.get(i);
                block.addView(compactLine("AS" + item.asn + " · " + item.startIp + " — " + item.endIp + " · " + item.organization));
                export.append("\n").append(item.startIp).append('-').append(item.endIp).append("  ").append(item.cidrs());
            }
            area.addView(block);
        }
        lastExport = export.toString();
    }

    private View databaseView() {
        LinearLayout page = page();
        page.addView(info("Для поиска по регионам, городам и названиям провайдеров установите бесплатные DB‑IP City Lite и ASN Lite. После импорта приложение проверяет полноту, индексы, контрольные записи, версию и целостность SQLite."));

        LinearLayout status = card();
        LinearLayout statusTitle = row();
        statusTitle.addView(text("🗃 Локальная база", 17, NAVY, true), new LinearLayout.LayoutParams(0, -2, 1f));
        databaseHealthBadge = healthBadge("Проверяем…", MUTED, Color.rgb(239, 242, 247));
        statusTitle.addView(databaseHealthBadge);
        status.addView(statusTitle);
        databaseStatus = text("Проверяем состояние…", 13, MUTED, false);
        status.addView(databaseStatus, params(-1, -2, 0, 8, 0, 0));
        databaseLastCheck = text("", 11, MUTED, false);
        status.addView(databaseLastCheck, params(-1, -2, 0, 5, 0, 0));
        databaseProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        databaseProgress.setMax(1000);
        databaseProgress.setVisibility(databaseWorkRunning ? View.VISIBLE : View.GONE);
        status.addView(databaseProgress, params(-1, dp(10), 0, 12, 0, 0));
        databaseProgressText = text(databaseWorkRunning ? "Операция выполняется…" : "", 12, MUTED, false);
        status.addView(databaseProgressText, params(-1, -2, 0, 6, 0, 0));
        databaseCancelAction = outlineButton("Остановить текущую загрузку / импорт");
        databaseCancelAction.setVisibility(databaseWorkRunning ? View.VISIBLE : View.GONE);
        databaseCancelAction.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cancelWork.set(true); databaseProgressText.setText("Остановка операции…"); } });
        status.addView(databaseCancelAction, params(-1, dp(42), 0, 8, 0, 0));
        TextView validate = outlineButton("Проверить целостность базы");
        validate.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { validateDatabase(true); } });
        status.addView(validate, params(-1, dp(42), 0, 8, 0, 0));
        page.addView(status);

        LinearLayout city = card();
        LinearLayout cityTitle = row();
        cityTitle.addView(text("🌍 DB‑IP City Lite", 16, NAVY, true), new LinearLayout.LayoutParams(0, -2, 1f));
        cityHealthBadge = healthBadge("Не установлена", MUTED, Color.rgb(239, 242, 247));
        cityTitle.addView(cityHealthBadge);
        city.addView(cityTitle);
        city.addView(text("Страны, регионы, города, координаты, IPv4 и IPv6.", 12, MUTED, false));
        cityDatabaseDetails = text("", 12, MUTED, false);
        city.addView(cityDatabaseDetails, params(-1, -2, 0, 7, 0, 0));
        LinearLayout cityActions = row();
        cityPrimaryAction = primaryButton("Проверяем…");
        TextView importCity = outlineButton("Выбрать файл");
        cityActions.addView(cityPrimaryAction, new LinearLayout.LayoutParams(0, dp(45), 1f)); cityActions.addView(space(7));
        cityActions.addView(importCity, new LinearLayout.LayoutParams(0, dp(45), 0.72f));
        city.addView(cityActions, params(-1, -2, 0, 10, 0, 0));
        page.addView(city);

        LinearLayout asn = card();
        LinearLayout asnTitle = row();
        asnTitle.addView(text("🏢 DB‑IP ASN Lite", 16, NAVY, true), new LinearLayout.LayoutParams(0, -2, 1f));
        asnHealthBadge = healthBadge("Не установлена", MUTED, Color.rgb(239, 242, 247));
        asnTitle.addView(asnHealthBadge);
        asn.addView(asnTitle);
        asn.addView(text("ASN, организация и принадлежащие ей диапазоны IPv4/IPv6.", 12, MUTED, false));
        asnDatabaseDetails = text("", 12, MUTED, false);
        asn.addView(asnDatabaseDetails, params(-1, -2, 0, 7, 0, 0));
        LinearLayout asnActions = row();
        asnPrimaryAction = primaryButton("Проверяем…");
        TextView importAsn = outlineButton("Выбрать файл");
        asnActions.addView(asnPrimaryAction, new LinearLayout.LayoutParams(0, dp(45), 1f)); asnActions.addView(space(7));
        asnActions.addView(importAsn, new LinearLayout.LayoutParams(0, dp(45), 0.72f));
        asn.addView(asnActions, params(-1, -2, 0, 10, 0, 0));
        page.addView(asn);

        LinearLayout maintenance = card();
        maintenance.addView(text("Обслуживание", 16, NAVY, true));
        maintenance.addView(text("Переустановка выполняется только вручную. Обычная проверка обновления не скачивает уже установленную актуальную версию повторно.", 12, MUTED, false));
        LinearLayout reinstallActions = row();
        TextView reinstallCity = outlineButton("Переустановить City");
        TextView reinstallAsn = outlineButton("Переустановить ASN");
        reinstallActions.addView(reinstallCity, new LinearLayout.LayoutParams(0, dp(43), 1f)); reinstallActions.addView(space(7));
        reinstallActions.addView(reinstallAsn, new LinearLayout.LayoutParams(0, dp(43), 1f));
        maintenance.addView(reinstallActions, params(-1, -2, 0, 9, 0, 0));
        LinearLayout clearActions = row();
        TextView clearCity = outlineButton("Удалить City");
        TextView clearAsn = outlineButton("Удалить ASN");
        clearActions.addView(clearCity, new LinearLayout.LayoutParams(0, dp(43), 1f)); clearActions.addView(space(7));
        clearActions.addView(clearAsn, new LinearLayout.LayoutParams(0, dp(43), 1f));
        maintenance.addView(clearActions, params(-1, -2, 0, 7, 0, 0));
        page.addView(maintenance);

        page.addView(info("Атрибуция: IP Geolocation by DB‑IP (db-ip.com), лицензия CC BY 4.0. Геолокация приблизительна. Актуальной считается успешно проверенная официальная база текущего или предыдущего месяца."));

        cityPrimaryAction.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { databasePrimaryAction(true); } });
        asnPrimaryAction.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { databasePrimaryAction(false); } });
        importCity.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFile(PICK_CITY); } });
        importAsn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFile(PICK_ASN); } });
        reinstallCity.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmReinstall(true); } });
        reinstallAsn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmReinstall(false); } });
        clearCity.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmClear(true); } });
        clearAsn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmClear(false); } });

        refreshDbStatus();
        scheduleAutomaticValidation();
        return scroll(page);
    }

    private View calculatorsView() {
        LinearLayout page = page();
        page.addView(info("Автономные калькуляторы IPv4/IPv6: подсеть, диапазон → CIDR, агрегация, преобразование систем счисления и нормализация IPv6."));

        LinearLayout subnet = card();
        subnet.addView(text("CIDR / подсеть", 16, NAVY, true));
        final EditText cidr = input("192.168.1.10/24 или 2001:db8::/48");
        subnet.addView(cidr, params(-1, dp(48), 0, 8, 0, 8));
        final TextView subnetOut = output();
        TextView calculate = primaryButton("Рассчитать подсеть");
        subnet.addView(calculate, new LinearLayout.LayoutParams(-1, dp(44)));
        subnet.addView(subnetOut, params(-1, -2, 0, 9, 0, 0));
        page.addView(subnet);
        calculate.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runMath(subnetOut, new MathAction() { @Override public String run() { return IpMath.subnetReport(cidr.getText().toString()); } }); } });

        LinearLayout range = card();
        range.addView(text("Диапазон → минимальные CIDR", 16, NAVY, true));
        final EditText rangeInput = input("192.168.1.10 - 192.168.3.200");
        range.addView(rangeInput, params(-1, dp(48), 0, 8, 0, 8));
        final TextView rangeOut = output();
        TextView rangeRun = primaryButton("Преобразовать");
        range.addView(rangeRun, new LinearLayout.LayoutParams(-1, dp(44)));
        range.addView(rangeOut, params(-1, -2, 0, 9, 0, 0));
        page.addView(range);
        rangeRun.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runMath(rangeOut, new MathAction() { @Override public String run() { IpMath.Range r = IpMath.parseRangeExpression(rangeInput.getText().toString()); return join(IpMath.rangeToCidrs(r.start, r.end, r.bits), "\n"); } }); } });

        LinearLayout aggregate = card();
        aggregate.addView(text("Агрегация диапазонов", 16, NAVY, true));
        final EditText aggregateInput = multiline("По одному CIDR или диапазону в строке");
        aggregate.addView(aggregateInput, params(-1, dp(130), 0, 8, 0, 8));
        final TextView aggregateOut = output();
        TextView aggregateRun = primaryButton("Объединить и агрегировать");
        aggregate.addView(aggregateRun, new LinearLayout.LayoutParams(-1, dp(44)));
        aggregate.addView(aggregateOut, params(-1, -2, 0, 9, 0, 0));
        page.addView(aggregate);
        aggregateRun.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runMath(aggregateOut, new MathAction() { @Override public String run() {
            List<IpMath.Range> ranges = new ArrayList<IpMath.Range>();
            for (String line : aggregateInput.getText().toString().split("\\r?\\n")) if (line.trim().length() > 0) ranges.add(IpMath.parseRangeExpression(line.trim()));
            List<IpMath.Range> merged = IpMath.aggregate(ranges);
            List<String> cidrs = new ArrayList<String>();
            for (IpMath.Range item : merged) cidrs.addAll(IpMath.rangeToCidrs(item.start, item.end, item.bits));
            return join(cidrs, "\n");
        } }); } });

        LinearLayout conversion = card();
        conversion.addView(text("IP ↔ Decimal / Hex / Binary", 16, NAVY, true));
        final EditText conversionInput = input("192.168.1.1, 3232235777 или 0xC0A80101");
        conversion.addView(conversionInput, params(-1, dp(48), 0, 8, 0, 8));
        final TextView conversionOut = output();
        TextView conversionRun = primaryButton("Преобразовать");
        conversion.addView(conversionRun, new LinearLayout.LayoutParams(-1, dp(44)));
        conversion.addView(conversionOut, params(-1, -2, 0, 9, 0, 0));
        page.addView(conversion);
        conversionRun.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runMath(conversionOut, new MathAction() { @Override public String run() { return IpMath.conversionReport(conversionInput.getText().toString()); } }); } });

        LinearLayout ipv6 = card();
        ipv6.addView(text("IPv6 expand / compress", 16, NAVY, true));
        final EditText ipv6Input = input("2001:db8::1");
        ipv6.addView(ipv6Input, params(-1, dp(48), 0, 8, 0, 8));
        final TextView ipv6Out = output();
        LinearLayout ipv6Actions = row();
        TextView expand = primaryButton("Раскрыть");
        TextView compress = outlineButton("Сжать");
        ipv6Actions.addView(expand, new LinearLayout.LayoutParams(0, dp(44), 1f)); ipv6Actions.addView(space(7));
        ipv6Actions.addView(compress, new LinearLayout.LayoutParams(0, dp(44), 1f));
        ipv6.addView(ipv6Actions); ipv6.addView(ipv6Out, params(-1, -2, 0, 9, 0, 0));
        page.addView(ipv6);
        expand.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runMath(ipv6Out, new MathAction() { @Override public String run() { return IpMath.expandIpv6(ipv6Input.getText().toString()); } }); } });
        compress.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runMath(ipv6Out, new MathAction() { @Override public String run() { return IpMath.compressIpv6(ipv6Input.getText().toString()); } }); } });
        return scroll(page);
    }

    private void databasePrimaryAction(final boolean city) {
        IpDataStore.Status status = database.status();
        IpDataStore.DatasetState item = city ? status.city : status.asn;
        if (item.valid) checkForDatabaseUpdate(city);
        else downloadDatabase(city, false);
    }

    private void checkForDatabaseUpdate(final boolean city) {
        if (databaseWorkRunning) return;
        setDatabaseWorkUi(true, "Проверяем наличие новой версии…", true);
        new Thread(new Runnable() { @Override public void run() {
            try {
                final String remoteName = findLatestRemoteName(city);
                final String remoteVersion = IpDataStore.sourceVersion(remoteName);
                final IpDataStore.DatasetState local = city ? database.status().city : database.status().asn;
                runOnUiThread(new Runnable() { @Override public void run() {
                    setDatabaseWorkUi(false, "", false);
                    if (local.valid && remoteVersion.length() > 0 && remoteVersion.equals(local.version)) {
                        new AlertDialog.Builder(IpIntelligenceActivity.this)
                            .setTitle("База актуальна")
                            .setMessage((city ? "City Lite" : "ASN Lite") + " версии " + remoteVersion + " уже установлена и прошла проверку. Повторная загрузка не требуется.")
                            .setPositiveButton("Готово", null)
                            .show();
                    } else {
                        String installed = local.version.length() > 0 ? local.version : "версия не определена";
                        new AlertDialog.Builder(IpIntelligenceActivity.this)
                            .setTitle(local.valid ? "Доступно обновление" : "Установить официальную базу?")
                            .setMessage("Установлено: " + installed + "\nДоступно: " + remoteVersion + "\n\nПосле загрузки база будет импортирована и проверена автоматически.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton(local.valid ? "Обновить" : "Установить", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) { downloadDatabase(city, true); }
                            }).show();
                    }
                    refreshDbStatus();
                } });
            } catch (final Exception error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    setDatabaseWorkUi(false, "Не удалось проверить обновление: " + message(error), false);
                    Toast.makeText(IpIntelligenceActivity.this, "Не удалось проверить обновление. Локальная база остаётся доступной.", Toast.LENGTH_LONG).show();
                    refreshDbStatus();
                } });
            }
        } }).start();
    }

    private void confirmReinstall(final boolean city) {
        new AlertDialog.Builder(this)
            .setTitle("Переустановить базу?")
            .setMessage("Текущий локальный индекс будет заменён свежей официальной версией после успешной загрузки и импорта. Используйте это действие только для восстановления или принудительной переустановки.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Переустановить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { downloadDatabase(city, true); }
            }).show();
    }

    private void scheduleAutomaticValidation() {
        final IpDataStore.Status status = database.status();
        long age = status.lastValidatedAt <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - status.lastValidatedAt;
        if (!status.overallValid || age > 24L * 60L * 60L * 1000L) validateDatabase(false);
    }

    private void validateDatabase(final boolean thorough) {
        if (validationRunning || databaseWorkRunning) return;
        validationRunning = true;
        if (databaseProgressText != null) databaseProgressText.setText(thorough ? "Полная проверка SQLite и индексов…" : "Проверка локальной базы…");
        if (databaseProgress != null) { databaseProgress.setVisibility(View.VISIBLE); databaseProgress.setIndeterminate(true); }
        new Thread(new Runnable() { @Override public void run() {
            final IpDataStore.Status status = database.validate(thorough);
            runOnUiThread(new Runnable() { @Override public void run() {
                validationRunning = false;
                if (databaseProgress != null && !databaseWorkRunning) databaseProgress.setVisibility(View.GONE);
                if (databaseProgressText != null && !databaseWorkRunning) databaseProgressText.setText(thorough ? "Проверка завершена" : "");
                renderDbStatus(status);
                if (thorough) Toast.makeText(IpIntelligenceActivity.this, status.overallValid ? "Целостность базы подтверждена" : "Обнаружены проблемы базы", Toast.LENGTH_LONG).show();
            } });
        } }).start();
    }

    private void downloadDatabase(final boolean city, final boolean force) {
        if (databaseWorkRunning) return;
        IpDataStore.DatasetState existing = city ? database.status().city : database.status().asn;
        if (!force && existing.valid && existing.current) {
            checkForDatabaseUpdate(city);
            return;
        }
        if (city) {
            StatFs stat = new StatFs(getFilesDir().getAbsolutePath());
            long available = stat.getAvailableBytes();
            if (available < 3L * 1024L * 1024L * 1024L) {
                new AlertDialog.Builder(this)
                    .setTitle("Недостаточно свободного места")
                    .setMessage("City Lite содержит миллионы диапазонов. Для временного файла и SQLite-индекса рекомендуется не менее 3 ГБ свободного места. Сейчас доступно около " + human(available) + ".")
                    .setPositiveButton("Понятно", null)
                    .show();
                return;
            }
        }
        cancelWork.set(false);
        setDatabaseWorkUi(true, "Подготовка загрузки…", false);
        new Thread(new Runnable() {
            @Override public void run() {
                File file = null;
                try {
                    File directory = new File(getFilesDir(), "ip-intelligence");
                    if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Не удалось создать каталог базы");
                    file = downloadLatest(city, directory);
                    final String name = file.getName();
                    runOnUiThread(new Runnable() { @Override public void run() { databaseProgressText.setText("Импортируем " + name + "…"); databaseProgress.setIndeterminate(true); } });
                    InputStream input = openMaybeGzip(new FileInputStream(file));
                    long count = city ? database.importCity(input, name, importProgress()) : database.importAsn(input, name, importProgress());
                    final long finalCount = count;
                    final IpDataStore.Status validated = database.validate(true);
                    if (file.exists()) file.delete();
                    runOnUiThread(new Runnable() { @Override public void run() {
                        setDatabaseWorkUi(false, "Импортировано и проверено " + format(finalCount) + " диапазонов", false);
                        renderDbStatus(validated);
                    } });
                } catch (final Exception error) {
                    if (file != null && file.getName().endsWith(".part") && file.exists()) file.delete();
                    runOnUiThread(new Runnable() { @Override public void run() {
                        setDatabaseWorkUi(false, "Ошибка: " + message(error), false);
                        refreshDbStatus();
                    } });
                }
            }
        }).start();
    }

    private void setDatabaseWorkUi(boolean running, String message, boolean indeterminate) {
        databaseWorkRunning = running;
        if (databaseProgress != null) {
            databaseProgress.setVisibility(running ? View.VISIBLE : View.GONE);
            databaseProgress.setIndeterminate(indeterminate);
            if (running && !indeterminate) databaseProgress.setProgress(0);
        }
        if (databaseProgressText != null) databaseProgressText.setText(message == null ? "" : message);
        if (databaseCancelAction != null) databaseCancelAction.setVisibility(running ? View.VISIBLE : View.GONE);
        if (cityPrimaryAction != null) cityPrimaryAction.setEnabled(!running);
        if (asnPrimaryAction != null) asnPrimaryAction.setEnabled(!running);
    }

    private String findLatestRemoteName(boolean city) throws Exception {
        Calendar calendar = Calendar.getInstance();
        Exception last = null;
        for (int offset = 0; offset < 4; offset++) {
            Calendar value = (Calendar) calendar.clone();
            value.add(Calendar.MONTH, -offset);
            String month = String.format(Locale.US, "%04d-%02d", value.get(Calendar.YEAR), value.get(Calendar.MONTH) + 1);
            String name = "dbip-" + (city ? "city" : "asn") + "-lite-" + month + ".csv.gz";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://download.db-ip.com/free/" + name).openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent", "SiteScopePro/0.26 IP-Intelligence");
                int code = connection.getResponseCode();
                if (code == 405 || code == 403) {
                    connection.disconnect();
                    connection = (HttpURLConnection) new URL("https://download.db-ip.com/free/" + name).openConnection();
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("User-Agent", "SiteScopePro/0.26 IP-Intelligence");
                    connection.setRequestProperty("Range", "bytes=0-0");
                    code = connection.getResponseCode();
                }
                if (code >= 200 && code < 400) return name;
                if (code != 404) last = new IllegalStateException("DB-IP HTTP " + code);
            } catch (Exception error) { last = error; }
            finally { if (connection != null) connection.disconnect(); }
        }
        throw last == null ? new IllegalStateException("Не найдена актуальная ежемесячная база DB-IP") : last;
    }

    private File downloadLatest(boolean city, File directory) throws Exception {
        Calendar calendar = Calendar.getInstance();
        Exception last = null;
        for (int offset = 0; offset < 4; offset++) {
            Calendar value = (Calendar) calendar.clone();
            value.add(Calendar.MONTH, -offset);
            String month = String.format(Locale.US, "%04d-%02d", value.get(Calendar.YEAR), value.get(Calendar.MONTH) + 1);
            String kind = city ? "city" : "asn";
            String name = "dbip-" + kind + "-lite-" + month + ".csv.gz";
            String address = "https://download.db-ip.com/free/" + name;
            File output = new File(directory, name + ".part");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "SiteScopePro/0.26 IP-Intelligence");
                int code = connection.getResponseCode();
                if (code == 404) continue;
                if (code < 200 || code >= 300) throw new IllegalStateException("DB-IP HTTP " + code);
                long total = connection.getContentLengthLong();
                InputStream input = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                FileOutputStream stream = new FileOutputStream(output);
                byte[] buffer = new byte[128 * 1024];
                long loaded = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (cancelWork.get()) throw new InterruptedException("Загрузка остановлена");
                    stream.write(buffer, 0, read); loaded += read;
                    final int progress = total > 0 ? (int) Math.min(1000, loaded * 1000 / total) : 0;
                    final long bytes = loaded;
                    runOnUiThread(new Runnable() { @Override public void run() { databaseProgress.setIndeterminate(false); databaseProgress.setProgress(progress); databaseProgressText.setText("Загружено " + human(bytes)); } });
                }
                stream.close(); input.close();
                if (total > 0 && loaded != total) throw new IllegalStateException("Файл загружен не полностью: " + loaded + " из " + total + " байт");
                File target = new File(directory, name);
                if (target.exists()) target.delete();
                if (!output.renameTo(target)) throw new IllegalStateException("Не удалось завершить файл загрузки");
                return target;
            } catch (Exception error) { last = error; if (output.exists()) output.delete(); }
            finally { if (connection != null) connection.disconnect(); }
        }
        throw last == null ? new IllegalStateException("Не найдена актуальная ежемесячная база DB-IP") : last;
    }

    private IpDataStore.Progress importProgress() {
        return new IpDataStore.Progress() {
            @Override public void onProgress(final long rows, final String stage) {
                runOnUiThread(new Runnable() { @Override public void run() { databaseProgressText.setText(stage + ": " + format(rows) + " строк"); } });
            }
            @Override public boolean isCancelled() { return cancelWork.get(); }
        };
    }

    private void pickFile(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean city = requestCode == PICK_CITY;
        if (!city && requestCode != PICK_ASN) return;
        if (databaseWorkRunning) return;
        cancelWork.set(false);
        setDatabaseWorkUi(true, "Импорт выбранного файла…", true);
        new Thread(new Runnable() { @Override public void run() {
            try {
                String name = displayName(uri);
                InputStream input = openMaybeGzip(getContentResolver().openInputStream(uri));
                long count = city ? database.importCity(input, name, importProgress()) : database.importAsn(input, name, importProgress());
                final long finalCount = count;
                final IpDataStore.Status validated = database.validate(true);
                runOnUiThread(new Runnable() { @Override public void run() {
                    setDatabaseWorkUi(false, "Импортировано и проверено " + format(finalCount) + " диапазонов", false);
                    renderDbStatus(validated);
                } });
            } catch (final Exception error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    setDatabaseWorkUi(false, "Ошибка: " + message(error), false);
                    refreshDbStatus();
                } });
            }
        } }).start();
    }

    private static InputStream openMaybeGzip(InputStream source) throws Exception {
        if (source == null) throw new IllegalStateException("Файл недоступен");
        BufferedInputStream input = new BufferedInputStream(source, 128 * 1024);
        input.mark(4);
        int a = input.read(); int b = input.read();
        input.reset();
        return a == 0x1f && b == 0x8b ? new GZIPInputStream(input, 128 * 1024) : input;
    }

    private void confirmClear(final boolean city) {
        new AlertDialog.Builder(this)
            .setTitle("Удалить локальную базу?")
            .setMessage(city ? "Будет удалён индекс стран, регионов и городов." : "Будет удалён индекс ASN и провайдеров.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { if (city) database.clearCity(); else database.clearAsn(); refreshDbStatus(); } })
            .show();
    }

    private void refreshDbStatus() {
        if (databaseStatus == null) return;
        renderDbStatus(database.status());
    }

    private void renderDbStatus(IpDataStore.Status status) {
        if (databaseStatus == null || status == null) return;
        String overall;
        int overallColor;
        int overallBackground;
        if (status.overallCurrent) {
            overall = "✓ Актуально";
            overallColor = Color.WHITE;
            overallBackground = GREEN;
        } else if (status.overallValid) {
            overall = "↻ Проверить обновление";
            overallColor = AMBER;
            overallBackground = AMBER_SOFT;
        } else if (status.city.installed || status.asn.installed) {
            overall = "! Требует проверки";
            overallColor = RED;
            overallBackground = Color.rgb(255, 234, 236);
        } else {
            overall = "Не установлена";
            overallColor = MUTED;
            overallBackground = Color.rgb(239, 242, 247);
        }
        applyHealthBadge(databaseHealthBadge, overall, overallColor, overallBackground);
        databaseStatus.setText(
            "City Lite: " + format(status.city.rows) + " диапазонов" + versionSuffix(status.city) +
            "\nASN Lite: " + format(status.asn.rows) + " диапазонов" + versionSuffix(status.asn));
        if (databaseLastCheck != null) databaseLastCheck.setText(status.lastValidatedAt > 0
            ? "Последняя проверка: " + friendlyTime(status.lastValidatedAt)
            : "Целостность ещё не проверялась");
        renderDataset(status.city, cityHealthBadge, cityDatabaseDetails, cityPrimaryAction);
        renderDataset(status.asn, asnHealthBadge, asnDatabaseDetails, asnPrimaryAction);
    }

    private void renderDataset(IpDataStore.DatasetState item, TextView badge, TextView details, TextView action) {
        if (item == null) return;
        String badgeText;
        int badgeColor;
        int badgeBackground;
        String actionText;
        if (!item.installed) {
            badgeText = "Не установлена";
            badgeColor = MUTED;
            badgeBackground = Color.rgb(239, 242, 247);
            actionText = "Скачать и импортировать";
        } else if (!item.valid) {
            badgeText = "! Ошибка";
            badgeColor = RED;
            badgeBackground = Color.rgb(255, 234, 236);
            actionText = "Восстановить базу";
        } else if (item.current) {
            badgeText = "✓ Актуально";
            badgeColor = GREEN;
            badgeBackground = GREEN_SOFT;
            actionText = "Проверить обновление";
        } else if (item.version.length() == 0) {
            badgeText = "✓ Валидна";
            badgeColor = GREEN;
            badgeBackground = GREEN_SOFT;
            actionText = "Проверить обновление";
        } else {
            badgeText = "↻ Обновить";
            badgeColor = AMBER;
            badgeBackground = AMBER_SOFT;
            actionText = "Проверить обновление";
        }
        applyHealthBadge(badge, badgeText, badgeColor, badgeBackground);
        if (action != null) action.setText(actionText);
        if (details != null) {
            StringBuilder value = new StringBuilder();
            if (item.installed) value.append(format(item.rows)).append(" диапазонов");
            if (item.version.length() > 0) value.append(" · версия ").append(item.version);
            else if (item.installed) value.append(" · версия не определена");
            if (item.importedAt > 0) value.append("\nИмпортировано: ").append(friendlyTime(item.importedAt));
            if (item.validatedAt > 0) value.append("\nПроверено: ").append(friendlyTime(item.validatedAt));
            if (item.source.length() > 0) value.append("\nИсточник: ").append(item.source);
            if (!item.valid && item.issue.length() > 0) value.append("\nПроблема: ").append(item.issue);
            if (!item.installed) value.append("Локальный индекс отсутствует.");
            details.setText(value.toString());
        }
    }

    private String versionSuffix(IpDataStore.DatasetState item) {
        if (item == null || !item.installed) return " · не установлена";
        if (item.version.length() > 0) return " · " + item.version;
        return " · версия неизвестна";
    }

    private String friendlyTime(long time) {
        if (time <= 0) return "—";
        Calendar now = Calendar.getInstance();
        Calendar value = Calendar.getInstance(); value.setTimeInMillis(time);
        boolean today = now.get(Calendar.YEAR) == value.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == value.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "'сегодня,' HH:mm" : "dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private void showChoice(String title, List<String> values, final EditText target) {
        if (values == null || values.isEmpty()) { Toast.makeText(this, "Список пуст. Установите локальную базу или заполните предыдущий уровень.", Toast.LENGTH_LONG).show(); return; }
        final String[] items = values.toArray(new String[values.size()]);
        new AlertDialog.Builder(this).setTitle(title).setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                String value = items[which];
                if (value.contains(" — ")) value = value.substring(0, value.indexOf(" — "));
                target.setText(value);
            }
        }).show();
    }

    private void runMath(final TextView target, MathAction action) {
        try { String value = action.run(); target.setText(value); lastExport = value; }
        catch (Exception error) { target.setText("Ошибка: " + message(error)); }
    }

    private interface MathAction { String run(); }

    private void exportLast() {
        if (lastExport == null || lastExport.trim().length() == 0) { Toast.makeText(this, "Сначала выполните запрос или расчёт", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "SiteScope Pro — IP Intelligence");
        intent.putExtra(Intent.EXTRA_TEXT, lastExport);
        startActivity(Intent.createChooser(intent, "Экспорт результата"));
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("SiteScope Pro", value));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private void openMap(double lat, double lon) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon))); }
        catch (Exception error) { Toast.makeText(this, "Не найдено приложение карты", Toast.LENGTH_SHORT).show(); }
    }

    private LinearLayout prefixList(String title, List<String> values, int max) {
        LinearLayout card = card();
        card.addView(text(title + " · " + values.size(), 16, NAVY, true));
        int shown = Math.min(max, values.size());
        for (int i = 0; i < shown; i++) card.addView(compactLine(values.get(i)));
        if (values.size() > shown) card.addView(text("Ещё " + (values.size() - shown) + " записей доступны в экспорте", 12, MUTED, false));
        return card;
    }

    private TextView compactLine(final String value) {
        TextView line = text(value, 12, NAVY, false);
        line.setPadding(0, dp(6), 0, dp(6));
        line.setTextIsSelectable(true);
        line.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copy(value); } });
        return line;
    }

    private LinearLayout loading(String value) {
        LinearLayout block = card();
        block.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        block.addView(progress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView label = text(value, 13, MUTED, false); label.setGravity(Gravity.CENTER);
        block.addView(label, params(-1, -2, 0, 10, 0, 0));
        return block;
    }

    private LinearLayout errorBox(String value) {
        LinearLayout block = card();
        block.setBackground(round(Color.rgb(255, 238, 239), 16, Color.rgb(244, 190, 195), 1));
        block.addView(text("Ошибка", 15, RED, true));
        block.addView(text(value, 13, RED, false));
        return block;
    }

    private LinearLayout emptyState(String title, String subtitle) {
        LinearLayout block = card(); block.setGravity(Gravity.CENTER);
        TextView icon = text("◎", 30, PRIMARY, false); icon.setGravity(Gravity.CENTER); block.addView(icon);
        TextView t = text(title, 17, NAVY, true); t.setGravity(Gravity.CENTER); block.addView(t, params(-1,-2,0,8,0,0));
        TextView s = text(subtitle, 13, MUTED, false); s.setGravity(Gravity.CENTER); block.addView(s);
        return block;
    }

    private LinearLayout metric(String key, String value) {
        LinearLayout row = row(); row.setPadding(0, dp(7), 0, dp(7));
        row.addView(text(key, 12, MUTED, true), new LinearLayout.LayoutParams(dp(112), -2));
        TextView v = text(empty(value), 13, NAVY, false); v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private TextView output() {
        TextView view = text("", 12, NAVY, false);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(round(Color.WHITE, 12, BORDER, 1));
        return view;
    }

    private LinearLayout section(String title, String subtitle) {
        LinearLayout block = new LinearLayout(this); block.setOrientation(LinearLayout.VERTICAL); block.setPadding(0, dp(18), 0, dp(9));
        block.addView(text(title, 18, NAVY, true)); block.addView(text(subtitle, 12, MUTED, false)); return block;
    }

    private TextView info(String value) {
        TextView view = text(value, 12, Color.rgb(49, 78, 123), false);
        view.setPadding(dp(13), dp(11), dp(13), dp(11));
        view.setBackground(round(PRIMARY_SOFT, 12, Color.TRANSPARENT, 0));
        view.setLayoutParams(params(-1, -2, 0, 5, 0, 8));
        return view;
    }

    private TextView healthBadge(String value, int color, int background) {
        TextView badge = text(value, 11, color, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(round(background, 999, Color.TRANSPARENT, 0));
        return badge;
    }

    private void applyHealthBadge(TextView badge, String value, int color, int background) {
        if (badge == null) return;
        badge.setText(value);
        badge.setTextColor(color);
        badge.setBackground(round(background, 999, Color.TRANSPARENT, 0));
    }

    private TextView caption(String value) { return text(value, 12, NAVY, true); }
    private TextView primaryButton(String value) { return button(value, true); }
    private TextView outlineButton(String value) { return button(value, false); }
    private TextView smallButton(String value) { TextView item = button(value, false); item.setTextSize(11); return item; }

    private TextView button(String value, boolean primary) {
        TextView view = text(value, 13, primary ? Color.WHITE : PRIMARY, true);
        view.setGravity(Gravity.CENTER); view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackground(round(primary ? PRIMARY : Color.WHITE, 12, primary ? Color.TRANSPARENT : BORDER, primary ? 0 : 1));
        view.setClickable(true); view.setFocusable(true); return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this); view.setHint(hint); view.setSingleLine(true);
        view.setTextSize(14); view.setTextColor(NAVY); view.setHintTextColor(Color.rgb(145,155,171));
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        view.setPadding(dp(13), 0, dp(13), 0); view.setBackground(round(Color.WHITE, 12, BORDER, 1)); return view;
    }

    private EditText multiline(String hint) {
        EditText view = input(hint); view.setSingleLine(false); view.setGravity(Gravity.TOP); view.setPadding(dp(13), dp(10), dp(13), dp(10)); return view;
    }

    private LinearLayout card() {
        LinearLayout card = vertical(); card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.setBackground(round(SURFACE, 16, BORDER, 1)); card.setElevation(dp(1));
        card.setLayoutParams(params(-1, -2, 0, 5, 0, 7)); return card;
    }

    private LinearLayout page() { LinearLayout page = vertical(); page.setPadding(dp(16), dp(6), dp(16), dp(30)); return page; }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private View space(int width) { View view = new View(this); view.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return view; }
    private ScrollView scroll(View child) { ScrollView view = new ScrollView(this); view.setFillViewport(true); view.addView(child, new ScrollView.LayoutParams(-1,-2)); return view; }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(0, 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }

    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor); return drawable;
    }

    private LinearLayout.LayoutParams params(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void saveHistory(String value) {
        List<String> values = history(); values.remove(value); values.add(0, value); while (values.size() > 8) values.remove(values.size()-1);
        getSharedPreferences("ip_intelligence", MODE_PRIVATE).edit().putString("history", join(values, "\n")).apply();
    }

    private List<String> history() {
        List<String> output = new ArrayList<String>();
        String raw = getSharedPreferences("ip_intelligence", MODE_PRIVATE).getString("history", "");
        for (String line : raw.split("\\n")) if (line.trim().length() > 0) output.add(line.trim());
        return output;
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return "import.csv.gz";
    }

    private static boolean looksLikeIpOrAsn(String value) {
        String v = value.toUpperCase(Locale.US);
        return v.startsWith("AS") || v.matches("\\d+") || v.contains(".") || v.contains(":") || v.contains("/");
    }
    private static boolean isIpAddress(String value) { String v = stripPrefix(value); return v.contains(".") || v.contains(":"); }
    private static String stripPrefix(String value) { int slash = value.indexOf('/'); return slash >= 0 ? value.substring(0, slash) : value; }
    private static List<String> builtInCountries() {
        List<String> output = new ArrayList<String>();
        for (String code : Locale.getISOCountries()) {
            Locale locale = new Locale("", code);
            output.add(code + " — " + locale.getDisplayCountry(Locale.getDefault()));
        }
        java.util.Collections.sort(output);
        return output;
    }

    private static int positive(String value, int fallback) { try { return Math.max(1, Integer.parseInt(value.trim())); } catch (Exception ignored) { return fallback; } }
    private static String countryCode(String value) {
        String v = value == null ? "" : value.trim();
        if (v.contains("—")) v = v.substring(0, v.indexOf('—')).trim();
        if (v.length() == 2) return v.toUpperCase(Locale.US);
        for (String code : Locale.getISOCountries()) {
            Locale locale = new Locale("", code);
            if (v.equalsIgnoreCase(locale.getDisplayCountry(Locale.getDefault())) || v.equalsIgnoreCase(locale.getDisplayCountry(Locale.ENGLISH))) return code;
        }
        return v.toUpperCase(Locale.US);
    }
    private static String empty(String value) { return value == null || value.trim().length() == 0 ? "Нет данных" : value; }
    private static String source(String value) { return value == null || value.length() == 0 ? "" : " · " + value; }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String decimal(double value) { return new DecimalFormat("0.#####").format(value); }
    private static String format(long value) { return String.format(Locale.US, "%,d", value).replace(',', ' '); }
    private static String human(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024*1024) return decimal(bytes/1024.0) + " KB"; return decimal(bytes/(1024.0*1024.0)) + " MB"; }
    private static String csv(String value) { String v = value == null ? "" : value; return "\"" + v.replace("\"", "\"\"") + "\""; }
    private static String join(List<String> values, String separator) { StringBuilder out = new StringBuilder(); for (String value : values) { if (out.length()>0) out.append(separator); out.append(value); } return out.toString(); }
    private static String joinNonEmpty(String... values) { StringBuilder out = new StringBuilder(); for (String value : values) if (value != null && value.trim().length() > 0) { if (out.length()>0) out.append(" · "); out.append(value.trim()); } return out.toString(); }
}
