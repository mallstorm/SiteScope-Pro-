package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class IptvActivity extends Activity {
    private static final int PICK_M3U = 7101;
    private static final int PICK_XMLTV = 7102;
    private static final int TAB_CHANNELS = 0;
    private static final int TAB_SOURCES = 1;
    private static final int TAB_DIAGNOSTICS = 2;

    private static final int NAVY = Color.rgb(43, 38, 34);
    private static final int MUTED = Color.rgb(133, 122, 112);
    private static final int PRIMARY = Color.rgb(217, 83, 43);
    private static final int PRIMARY_SOFT = Color.rgb(255, 232, 222);
    private static final int GREEN = Color.rgb(20, 145, 93);
    private static final int GREEN_SOFT = Color.rgb(228, 247, 238);
    private static final int AMBER = Color.rgb(192, 116, 8);
    private static final int RED = Color.rgb(205, 50, 63);
    private static final int BORDER = Color.rgb(233, 223, 212);
    private static final int SURFACE = Color.rgb(248, 243, 235);
    private static final int BACKGROUND = Color.rgb(255, 255, 255);
    private static final int PLAYER_BG = Color.rgb(17, 24, 39);

    private final List<Models.IptvChannel> channels = new ArrayList<Models.IptvChannel>();
    private final List<Models.UdpxyEndpoint> endpoints = new ArrayList<Models.UdpxyEndpoint>();
    private final Set<String> favorites = new HashSet<String>();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final AtomicBoolean cancelProbe = new AtomicBoolean(false);
    private final List<TextView> tabButtons = new ArrayList<TextView>();

    private FrameLayout tabContent;
    private View channelsScreen;
    private View sourcesScreen;
    private View diagnosticsScreen;
    private int currentTab = TAB_CHANNELS;
    private int selectedIndex = -1;
    private String activeFilter = "Все";

    private EditText searchInput;
    private LinearLayout filterStrip;
    private LinearLayout channelArea;
    private TextView channelCount;
    private TextView playerTitle;
    private TextView playerSubtitle;
    private TextView playerPlay;

    private EditText udpxyTargets;
    private EditText udpxyPorts;
    private EditText udpxyBase;
    private EditText playlistUrl;
    private CheckBox authorized;
    private LinearLayout endpointArea;
    private TextView sourceStatus;

    private LinearLayout diagnosticsSummary;
    private LinearLayout diagnosticsSelected;
    private LinearLayout diagnosticsList;
    private TextView diagnosticStatus;
    private ProgressBar progress;
    private TextView probeButton;
    private TextView stopButton;
    private TextView headerCount;

    private WifiManager.MulticastLock multicastLock;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildShell());
        restoreChannels();
        showTab(TAB_CHANNELS);
    }

    @Override protected void onDestroy() {
        cancelProbe.set(true);
        workers.shutdownNow();
        releaseMulticastLock();
        super.onDestroy();
    }

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout header = row();
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView back = iconButton("‹");
        back.setTextSize(27);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8), 0, 0, 0);
        titles.addView(text("IPTV Engineering", 21, NAVY, true));
        titles.addView(text("Каналы, источники и диагностика", 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerCount = badge("0 каналов", PRIMARY, PRIMARY_SOFT);
        header.addView(headerCount);
        root.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(2), dp(12), dp(10));
        String[] names = {"Каналы", "Источники", "Диагностика"};
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            TextView item = tabButton(names[i]);
            item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showTab(tab); } });
            tabButtons.add(item);
            tabs.addView(item, new LinearLayout.LayoutParams(0, dp(42), 1f));
            if (i < names.length - 1) tabs.addView(space(6));
        }
        root.addView(tabs);

        tabContent = new FrameLayout(this);
        channelsScreen = buildChannelsScreen();
        sourcesScreen = buildSourcesScreen();
        diagnosticsScreen = buildDiagnosticsScreen();
        tabContent.addView(channelsScreen, new FrameLayout.LayoutParams(-1, -1));
        tabContent.addView(sourcesScreen, new FrameLayout.LayoutParams(-1, -1));
        tabContent.addView(diagnosticsScreen, new FrameLayout.LayoutParams(-1, -1));
        root.addView(tabContent, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private View buildChannelsScreen() {
        LinearLayout page = page();

        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setPadding(dp(16), dp(14), dp(16), dp(14));
        player.setBackground(round(PLAYER_BG, 18, PLAYER_BG, 0));
        player.setElevation(dp(2));

        LinearLayout playerTop = row();
        TextView television = text("▶", 30, Color.WHITE, true);
        television.setGravity(Gravity.CENTER);
        television.setBackground(round(Color.rgb(37, 50, 72), 16, Color.TRANSPARENT, 0));
        playerTop.addView(television, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout playerText = new LinearLayout(this);
        playerText.setOrientation(LinearLayout.VERTICAL);
        playerText.setPadding(dp(14), 0, 0, 0);
        playerTitle = text("Выберите канал", 18, Color.WHITE, true);
        playerSubtitle = text("Плейлист пока не загружен", 12, Color.rgb(190, 199, 214), false);
        playerText.addView(playerTitle);
        playerText.addView(playerSubtitle, params(-1, -2, 0, 7, 0, 0));
        playerTop.addView(playerText, new LinearLayout.LayoutParams(0, -2, 1f));
        player.addView(playerTop);

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        TextView previous = darkControl("‹‹");
        playerPlay = darkControl("▶ Смотреть");
        TextView next = darkControl("››");
        TextView info = darkControl("i");
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(42), 0.7f));
        controls.addView(space(7));
        controls.addView(playerPlay, new LinearLayout.LayoutParams(0, dp(42), 1.5f));
        controls.addView(space(7));
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(42), 0.7f));
        controls.addView(space(7));
        controls.addView(info, new LinearLayout.LayoutParams(0, dp(42), 0.7f));
        player.addView(controls, params(-1, -2, 0, 14, 0, 0));
        page.addView(player);

        previous.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectRelative(-1); } });
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectRelative(1); } });
        playerPlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openSelectedPlayer(); } });
        info.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (selectedIndex >= 0 && selectedIndex < channels.size()) channelDialog(channels.get(selectedIndex)); } });

        LinearLayout searchRow = row();
        searchInput = input("Поиск канала или группы");
        searchInput.setSingleLine(true);
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView clearSearch = iconButton("×");
        searchRow.addView(clearSearch, params(dp(46), dp(46), 7, 0, 0, 0));
        page.addView(searchRow, params(-1, -2, 0, 12, 0, 0));
        clearSearch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { searchInput.setText(""); } });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderChannels(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        filterStrip = row();
        filters.addView(filterStrip, new HorizontalScrollView.LayoutParams(-2, -2));
        page.addView(filters, params(-1, dp(44), 0, 8, 0, 0));

        channelCount = text("0 каналов", 12, MUTED, false);
        page.addView(channelCount, params(-1, -2, 2, 10, 2, 6));
        channelArea = new LinearLayout(this);
        channelArea.setOrientation(LinearLayout.VERTICAL);
        page.addView(channelArea);
        return scroll(page);
    }

    private View buildSourcesScreen() {
        LinearLayout page = page();
        page.addView(section("Доступ и источники", "Импорт и обнаружение вынесены из экрана просмотра"));

        LinearLayout permission = card();
        authorized = new CheckBox(this);
        authorized.setText("Подтверждаю владение сетью и потоками или наличие разрешения");
        authorized.setTextColor(NAVY);
        authorized.setTextSize(13);
        authorized.setChecked(getSharedPreferences("sitescope_iptv", MODE_PRIVATE).getBoolean("authorized", false));
        permission.addView(authorized);
        authorized.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("sitescope_iptv", MODE_PRIVATE).edit().putBoolean("authorized", authorized.isChecked()).apply();
            }
        });
        page.addView(permission);

        LinearLayout playlist = card();
        playlist.addView(titleRow("🗒️", "Плейлист и программа", "M3U/M3U8 и XMLTV"));
        playlistUrl = input("https://сервер/playlist.m3u");
        playlist.addView(playlistUrl, params(-1, dp(46), 0, 12, 0, 8));
        LinearLayout playlistActions = row();
        TextView loadUrl = compactButton("Загрузить URL", true);
        TextView openM3u = compactButton("Открыть файл", false);
        playlistActions.addView(loadUrl, new LinearLayout.LayoutParams(0, dp(42), 1f));
        playlistActions.addView(space(7));
        playlistActions.addView(openM3u, new LinearLayout.LayoutParams(0, dp(42), 1f));
        playlist.addView(playlistActions);
        LinearLayout playlistMore = row();
        TextView xml = compactButton("XMLTV", false);
        TextView manual = compactButton("Вставить M3U", false);
        TextView clear = compactButton("Очистить", false);
        playlistMore.addView(xml, new LinearLayout.LayoutParams(0, dp(40), 1f));
        playlistMore.addView(space(6));
        playlistMore.addView(manual, new LinearLayout.LayoutParams(0, dp(40), 1f));
        playlistMore.addView(space(6));
        playlistMore.addView(clear, new LinearLayout.LayoutParams(0, dp(40), 1f));
        playlist.addView(playlistMore, params(-1, -2, 0, 8, 0, 0));
        page.addView(playlist);

        loadUrl.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { loadPlaylistUrl(); } });
        openM3u.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFile(PICK_M3U, new String[]{"audio/x-mpegurl", "application/x-mpegURL", "text/plain", "*/*"}); } });
        xml.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFile(PICK_XMLTV, new String[]{"application/xml", "text/xml", "*/*"}); } });
        manual.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { manualPlaylistDialog(); } });
        clear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { clearChannels(); } });

        LinearLayout network = card();
        network.addView(titleRow("📡", "Сетевые источники", "udpxy и SAP/SDP"));
        udpxyTargets = input("192.168.1.1 или 192.168.1.0/28");
        network.addView(udpxyTargets, params(-1, dp(46), 0, 12, 0, 8));
        udpxyPorts = input("4022,8080,81,80");
        udpxyPorts.setText("4022,8080,81,80");
        network.addView(udpxyPorts, params(-1, dp(46), 0, 0, 0, 8));
        LinearLayout networkActions = row();
        TextView discover = compactButton("Найти udpxy", true);
        TextView sap = compactButton("SAP/SDP", false);
        networkActions.addView(discover, new LinearLayout.LayoutParams(0, dp(42), 1f));
        networkActions.addView(space(7));
        networkActions.addView(sap, new LinearLayout.LayoutParams(0, dp(42), 1f));
        network.addView(networkActions);
        udpxyBase = input("Пусто = прямой multicast; либо http://192.168.1.1:4022");
        network.addView(udpxyBase, params(-1, dp(46), 0, 10, 0, 0));
        endpointArea = new LinearLayout(this);
        endpointArea.setOrientation(LinearLayout.VERTICAL);
        network.addView(endpointArea, params(-1, -2, 0, 8, 0, 0));
        page.addView(network);
        discover.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { discoverUdpxy(); } });
        sap.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { discoverSap(); } });

        sourceStatus = statusBox("Источники готовы. Загрузите плейлист или выполните локальное обнаружение.");
        page.addView(sourceStatus);
        return scroll(page);
    }

    private View buildDiagnosticsScreen() {
        LinearLayout page = page();
        page.addView(section("Диагностика потоков", "Проверка доступности и техническая информация отдельно от просмотра"));

        diagnosticsSummary = new LinearLayout(this);
        diagnosticsSummary.setOrientation(LinearLayout.VERTICAL);
        page.addView(diagnosticsSummary);

        LinearLayout controls = card();
        controls.addView(titleRow("🧪", "Проверка каналов", "IGMP join, udpxy и MPEG-TS"));
        LinearLayout actions = row();
        probeButton = compactButton("Проверить", true);
        stopButton = compactButton("Остановить", false);
        stopButton.setEnabled(false);
        actions.addView(probeButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(space(7));
        actions.addView(stopButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        controls.addView(actions, params(-1, -2, 0, 12, 0, 0));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        controls.addView(progress, params(-1, dp(8), 0, 12, 0, 0));
        diagnosticStatus = text("Загрузите плейлист для запуска проверки", 12, MUTED, false);
        controls.addView(diagnosticStatus, params(-1, -2, 0, 8, 0, 0));
        LinearLayout export = row();
        TextView copy = compactButton("Копировать отчёт", false);
        TextView share = compactButton("Поделиться", false);
        export.addView(copy, new LinearLayout.LayoutParams(0, dp(40), 1f));
        export.addView(space(7));
        export.addView(share, new LinearLayout.LayoutParams(0, dp(40), 1f));
        controls.addView(export, params(-1, -2, 0, 10, 0, 0));
        page.addView(controls);
        probeButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { probeChannels(); } });
        stopButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cancelProbe.set(true); diagnosticStatus.setText("Остановка после текущего запроса…"); } });
        copy.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copyTable(); } });
        share.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { shareTable(); } });

        diagnosticsSelected = new LinearLayout(this);
        diagnosticsSelected.setOrientation(LinearLayout.VERTICAL);
        page.addView(diagnosticsSelected);
        diagnosticsList = new LinearLayout(this);
        diagnosticsList.setOrientation(LinearLayout.VERTICAL);
        page.addView(diagnosticsList);
        return scroll(page);
    }

    private void showTab(int tab) {
        currentTab = tab;
        channelsScreen.setVisibility(tab == TAB_CHANNELS ? View.VISIBLE : View.GONE);
        sourcesScreen.setVisibility(tab == TAB_SOURCES ? View.VISIBLE : View.GONE);
        diagnosticsScreen.setVisibility(tab == TAB_DIAGNOSTICS ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabButtons.size(); i++) styleTab(tabButtons.get(i), i == tab);
        if (tab == TAB_CHANNELS) renderChannels();
        else if (tab == TAB_DIAGNOSTICS) renderDiagnostics();
    }

    private void renderFilterChips() {
        if (filterStrip == null) return;
        filterStrip.removeAllViews();
        LinkedHashSet<String> filters = new LinkedHashSet<String>();
        filters.add("Все");
        filters.add("Избранное");
        for (Models.IptvChannel channel : channels) if (channel.group != null && channel.group.trim().length() > 0) filters.add(channel.group.trim());
        int count = 0;
        for (final String value : filters) {
            if (count >= 14) break;
            TextView chip = filterChip(value, value.equals(activeFilter));
            chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { activeFilter = value; renderFilterChips(); renderChannels(); } });
            filterStrip.addView(chip);
            filterStrip.addView(space(6));
            count++;
        }
    }

    private void renderChannels() {
        if (channelArea == null) return;
        renderFilterChips();
        channelArea.removeAllViews();
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.US);
        List<Models.IptvChannel> visible = new ArrayList<Models.IptvChannel>();
        for (Models.IptvChannel channel : channels) {
            if ("Избранное".equals(activeFilter) && !favorites.contains(channelKey(channel))) continue;
            if (!"Все".equals(activeFilter) && !"Избранное".equals(activeFilter) && !activeFilter.equals(channel.group)) continue;
            String haystack = (channel.name + " " + channel.group + " " + channel.providerName).toLowerCase(Locale.US);
            if (query.length() > 0 && !haystack.contains(query)) continue;
            visible.add(channel);
        }
        int shown = Math.min(500, visible.size());
        channelCount.setText(visible.size() + " каналов" + (visible.size() > shown ? " · показаны первые " + shown : ""));
        headerCount.setText(channels.size() + " каналов");
        if (visible.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(text(channels.isEmpty() ? "Плейлист не загружен" : "Каналы не найдены", 16, NAVY, true));
            empty.addView(text(channels.isEmpty() ? "Откройте вкладку «Источники» и добавьте M3U, XMLTV или SAP/SDP." : "Измените поиск или фильтр.", 13, MUTED, false), params(-1, -2, 0, 7, 0, 0));
            channelArea.addView(empty);
        } else {
            for (int i = 0; i < shown; i++) channelArea.addView(channelRow(visible.get(i)), params(-1, -2, 0, 0, 0, 7));
        }
        updatePlayerCard();
    }

    private View channelRow(final Models.IptvChannel channel) {
        final int index = channels.indexOf(channel);
        LinearLayout card = card(10);
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout row = row();
        final ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setImageDrawable(letterDrawable(channel.name));
        row.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(11), 0, 0, 0);
        TextView title = text(channel.name.length() > 0 ? channel.name : "Без названия", 14, NAVY, true);
        title.setSingleLine(true);
        labels.addView(title);
        String secondary = channel.group.length() > 0 ? channel.group : channel.sourceType;
        labels.addView(text(secondary, 11, MUTED, false));
        String state = channel.playable ? "Доступен" : (channel.status.length() > 0 ? channel.status : "Не проверен");
        labels.addView(text(state, 10, channel.playable ? GREEN : MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        TextView star = text(favorites.contains(channelKey(channel)) ? "★" : "☆", 22, favorites.contains(channelKey(channel)) ? AMBER : MUTED, false);
        star.setGravity(Gravity.CENTER);
        right.addView(star, new LinearLayout.LayoutParams(dp(38), dp(30)));
        if (channel.videoCodec.length() > 0 || channel.mpegTs) right.addView(smallBadge(channel.videoCodec.length() > 0 ? channel.videoCodec : "TS"));
        row.addView(right, new LinearLayout.LayoutParams(dp(48), dp(52)));
        card.addView(row);

        card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectedIndex = index; updatePlayerCard(); openSelectedPlayer(); } });
        card.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { channelDialog(channel); return true; } });
        star.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleFavorite(channel); } });
        if (channel.logoUrl.startsWith("http://") || channel.logoUrl.startsWith("https://")) loadLogo(logo, channel.logoUrl);
        return card;
    }

    private void updatePlayerCard() {
        if (playerTitle == null) return;
        if (channels.isEmpty()) {
            selectedIndex = -1;
            playerTitle.setText("Выберите канал");
            playerSubtitle.setText("Плейлист пока не загружен");
            playerPlay.setEnabled(false);
            playerPlay.setAlpha(0.55f);
            renderDiagnosticsSelected();
            return;
        }
        if (selectedIndex < 0 || selectedIndex >= channels.size()) selectedIndex = 0;
        Models.IptvChannel channel = channels.get(selectedIndex);
        playerTitle.setText(channel.name);
        String line = channel.group.length() > 0 ? channel.group : channel.sourceType;
        if (channel.playable) line += " · доступен";
        playerSubtitle.setText(line);
        playerPlay.setEnabled(true);
        playerPlay.setAlpha(1f);
        renderDiagnosticsSelected();
    }

    private void selectRelative(int delta) {
        if (channels.isEmpty()) return;
        if (selectedIndex < 0) selectedIndex = 0;
        else selectedIndex = (selectedIndex + delta + channels.size()) % channels.size();
        updatePlayerCard();
    }

    private void openSelectedPlayer() {
        if (selectedIndex < 0 || selectedIndex >= channels.size()) {
            Toast.makeText(this, "Выберите канал", Toast.LENGTH_SHORT).show();
            return;
        }
        openPlayer(selectedIndex);
    }

    private void openPlayer(int index) {
        Models.IptvChannel channel = channels.get(index);
        try {
            String base = udpxyBase == null ? "" : udpxyBase.getText().toString().trim();
            String playback = IptvEngine.buildPlaybackUrl(base, channel.sourceUrl);
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("url", playback);
            intent.putExtra("source_url", channel.sourceUrl);
            intent.putExtra("name", channel.name);
            intent.putExtra("details", technicalLine(channel));
            if (channels.size() > 1) {
                Models.IptvChannel previous = channels.get((index - 1 + channels.size()) % channels.size());
                Models.IptvChannel next = channels.get((index + 1) % channels.size());
                intent.putExtra("previous_url", IptvEngine.buildPlaybackUrl(base, previous.sourceUrl));
                intent.putExtra("previous_source_url", previous.sourceUrl);
                intent.putExtra("previous_name", previous.name);
                intent.putExtra("previous_details", technicalLine(previous));
                intent.putExtra("next_url", IptvEngine.buildPlaybackUrl(base, next.sourceUrl));
                intent.putExtra("next_source_url", next.sourceUrl);
                intent.putExtra("next_name", next.name);
                intent.putExtra("next_details", technicalLine(next));
            }
            startActivity(intent);
        } catch (Exception error) { showError("Просмотр", error.getMessage()); }
    }

    private String technicalLine(Models.IptvChannel channel) {
        StringBuilder line = new StringBuilder();
        if (channel.container.length() > 0) line.append(channel.container);
        if (channel.videoCodec.length() > 0) { if (line.length() > 0) line.append(" · "); line.append(channel.videoCodec); }
        if (channel.audioCodec.length() > 0) { if (line.length() > 0) line.append(" · "); line.append(channel.audioCodec); }
        if (channel.bitrateKbps > 0) { if (line.length() > 0) line.append(" · "); line.append(channel.bitrateKbps).append(" кбит/с"); }
        return line.length() > 0 ? line.toString() : channel.sourceType;
    }

    private void toggleFavorite(Models.IptvChannel channel) {
        String key = channelKey(channel);
        if (favorites.contains(key)) favorites.remove(key); else favorites.add(key);
        getSharedPreferences("sitescope_iptv", MODE_PRIVATE).edit().putStringSet("favorites", new HashSet<String>(favorites)).apply();
        renderChannels();
    }

    private String channelKey(Models.IptvChannel channel) {
        if (channel.sourceUrl.length() > 0) return channel.sourceUrl;
        if (channel.id.length() > 0) return channel.id;
        return channel.name;
    }

    private void renderDiagnostics() {
        if (diagnosticsSummary == null) return;
        diagnosticsSummary.removeAllViews();
        int playable = 0;
        int errors = 0;
        int tested = 0;
        for (Models.IptvChannel channel : channels) {
            if (channel.playable) playable++;
            if (channel.status.equals("ошибка") || channel.error.length() > 0) errors++;
            if (!channel.status.equals("не проверен")) tested++;
        }
        LinearLayout metrics = row();
        metrics.addView(metricBox(String.valueOf(channels.size()), "Каналы", PRIMARY, PRIMARY_SOFT), new LinearLayout.LayoutParams(0, dp(68), 1f));
        metrics.addView(space(7));
        metrics.addView(metricBox(String.valueOf(playable), "Доступны", GREEN, GREEN_SOFT), new LinearLayout.LayoutParams(0, dp(68), 1f));
        metrics.addView(space(7));
        metrics.addView(metricBox(String.valueOf(errors), "Ошибки", RED, Color.rgb(255, 234, 236)), new LinearLayout.LayoutParams(0, dp(68), 1f));
        diagnosticsSummary.addView(metrics);
        diagnosticsSummary.addView(text("Проверено: " + tested + " из " + channels.size(), 12, MUTED, false), params(-1, -2, 2, 8, 2, 8));
        renderDiagnosticsSelected();
        renderDiagnosticsList();
    }

    private void renderDiagnosticsSelected() {
        if (diagnosticsSelected == null) return;
        diagnosticsSelected.removeAllViews();
        diagnosticsSelected.addView(section("Выбранный канал", "Технические данные без перегрузки списка"));
        if (selectedIndex < 0 || selectedIndex >= channels.size()) {
            LinearLayout empty = card();
            empty.addView(text("Канал не выбран", 15, NAVY, true));
            empty.addView(text("Выберите канал на вкладке «Каналы».", 12, MUTED, false));
            diagnosticsSelected.addView(empty);
            return;
        }
        Models.IptvChannel channel = channels.get(selectedIndex);
        LinearLayout detail = card();
        detail.addView(text(channel.name, 16, NAVY, true));
        detail.addView(keyValue("Источник", channel.sourceType));
        detail.addView(keyValue("Статус", channel.playable ? "Доступен" : channel.status));
        detail.addView(keyValue("Контейнер", value(channel.container)));
        detail.addView(keyValue("Видео", value(channel.videoCodec)));
        detail.addView(keyValue("Аудио", value(channel.audioCodec)));
        detail.addView(keyValue("Bitrate", channel.bitrateKbps > 0 ? channel.bitrateKbps + " кбит/с" : "—"));
        detail.addView(keyValue("Задержка", channel.latencyMs > 0 ? channel.latencyMs + " мс" : "—"));
        diagnosticsSelected.addView(detail);
    }

    private void renderDiagnosticsList() {
        if (diagnosticsList == null) return;
        diagnosticsList.removeAllViews();
        diagnosticsList.addView(section("Результаты", channels.isEmpty() ? "Нет данных" : "Компактный технический журнал"));
        int shown = 0;
        for (final Models.IptvChannel channel : channels) {
            if (channel.status.equals("не проверен") && channel.videoCodec.length() == 0 && channel.error.length() == 0) continue;
            LinearLayout row = card(10);
            LinearLayout top = row();
            top.addView(text(channel.playable ? "●" : "●", 13, channel.playable ? GREEN : RED, true), new LinearLayout.LayoutParams(dp(20), -2));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(channel.name, 13, NAVY, true));
            labels.addView(text(technicalLine(channel), 11, MUTED, false));
            top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView detail = compactButton("i", false);
            top.addView(detail, new LinearLayout.LayoutParams(dp(38), dp(34)));
            row.addView(top);
            detail.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { channelDialog(channel); } });
            diagnosticsList.addView(row, params(-1, -2, 0, 0, 0, 6));
            shown++;
            if (shown >= 150) break;
        }
        if (shown == 0) {
            LinearLayout empty = card();
            empty.addView(text("Проверок пока нет", 15, NAVY, true));
            empty.addView(text("Нажмите «Проверить» после загрузки плейлиста.", 12, MUTED, false));
            diagnosticsList.addView(empty);
        }
    }

    private void manualPlaylistDialog() {
        final EditText editor = input("#EXTM3U\n#EXTINF:-1,Канал\nudp://@239.1.1.1:1234");
        editor.setMinLines(9);
        editor.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this)
            .setTitle("Вставить M3U")
            .setView(editor)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Распознать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { parsePlaylist(editor.getText().toString()); }
            }).show();
    }

    private void clearChannels() {
        channels.clear();
        favorites.clear();
        selectedIndex = -1;
        getSharedPreferences("sitescope_iptv", MODE_PRIVATE).edit().remove("favorites").apply();
        saveChannels();
        renderChannels();
        renderDiagnostics();
        sourceStatus.setText("Каталог очищен");
    }

    private void discoverUdpxy() {
        if (!checkAuthorization()) return;
        final String targetExpr = udpxyTargets.getText().toString().trim();
        final String portExpr = udpxyPorts.getText().toString().trim();
        if (targetExpr.length() == 0) { udpxyTargets.setError("Укажите локальный IP или диапазон"); return; }
        sourceStatus.setText("Поиск udpxy…");
        endpointArea.removeAllViews();
        endpoints.clear();
        workers.execute(new Runnable() {
            @Override public void run() {
                try {
                    final IptvTargets hosts = IptvTargets.parse(targetExpr);
                    final List<Integer> ports = ScannerEngine.parsePorts(portExpr);
                    final List<Models.UdpxyEndpoint> found = IptvEngine.discoverUdpxy(hosts, ports, 900, SettingsStore.scannerConcurrency(IptvActivity.this), SettingsStore.scannerDelayMs(IptvActivity.this), new IptvEngine.DiscoveryListener() {
                        @Override public void onProgress(final long completed, final long total, final String current) {
                            runOnUiThread(new Runnable() { @Override public void run() { sourceStatus.setText("Проверено " + completed + "/" + total + " · " + current); } });
                        }
                        @Override public void onFound(final Models.UdpxyEndpoint endpoint) {
                            runOnUiThread(new Runnable() { @Override public void run() { addEndpoint(endpoint); } });
                        }
                    });
                    runOnUiThread(new Runnable() { @Override public void run() { sourceStatus.setText(found.isEmpty() ? "udpxy не найден. Можно указать base URL вручную." : "Найдено udpxy: " + found.size()); } });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() { @Override public void run() { showError("Поиск udpxy", error.getMessage()); sourceStatus.setText("Ошибка обнаружения"); } });
                }
            }
        });
    }

    private void addEndpoint(final Models.UdpxyEndpoint endpoint) {
        endpoints.add(endpoint);
        LinearLayout box = card(10);
        LinearLayout line = row();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(endpoint.displayLine(), 13, GREEN, true));
        labels.addView(text(endpoint.evidence + " · " + endpoint.latencyMs + " мс", 10, MUTED, false));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView select = compactButton("Выбрать", false);
        line.addView(select, new LinearLayout.LayoutParams(dp(82), dp(36)));
        box.addView(line);
        select.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { udpxyBase.setText(endpoint.baseUrl); Toast.makeText(IptvActivity.this, "udpxy выбран", Toast.LENGTH_SHORT).show(); } });
        endpointArea.addView(box, params(-1, -2, 0, 6, 0, 0));
    }

    private void discoverSap() {
        if (!checkAuthorization()) return;
        acquireMulticastLock();
        sourceStatus.setText("Прослушивание SAP/SDP…");
        workers.execute(new Runnable() {
            @Override public void run() {
                try {
                    IptvEngine.discoverSap(8, new IptvEngine.SapListener() {
                        @Override public void onChannel(final Models.IptvChannel channel) {
                            runOnUiThread(new Runnable() { @Override public void run() { channels.add(channel); if (selectedIndex < 0) selectedIndex = 0; renderChannels(); renderDiagnostics(); } });
                        }
                        @Override public void onStatus(final String value) { runOnUiThread(new Runnable() { @Override public void run() { sourceStatus.setText(value); } }); }
                    });
                    runOnUiThread(new Runnable() { @Override public void run() { saveChannels(); } });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() { @Override public void run() { showError("SAP/SDP", error.getMessage()); } });
                } finally { releaseMulticastLock(); }
            }
        });
    }

    private void loadPlaylistUrl() {
        if (!checkAuthorization()) return;
        final String url = playlistUrl.getText().toString().trim();
        if (url.length() == 0) { playlistUrl.setError("Укажите URL"); return; }
        sourceStatus.setText("Загрузка M3U…");
        workers.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String content = IptvEngine.downloadText(url, 3500);
                    runOnUiThread(new Runnable() { @Override public void run() { parsePlaylist(content); } });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() { @Override public void run() { showError("Загрузка плейлиста", error.getMessage()); } });
                }
            }
        });
    }

    private void parsePlaylist(String content) {
        if (!checkAuthorization()) return;
        try {
            List<Models.IptvChannel> parsed = IptvEngine.parseM3u(content);
            channels.clear();
            channels.addAll(parsed);
            selectedIndex = channels.isEmpty() ? -1 : 0;
            sourceStatus.setText("Распознано каналов: " + channels.size());
            saveChannels();
            renderChannels();
            renderDiagnostics();
            showTab(TAB_CHANNELS);
        } catch (Exception error) { showError("M3U", error.getMessage()); }
    }

    private void probeChannels() {
        if (!checkAuthorization()) return;
        if (channels.isEmpty()) { showError("IPTV", "Сначала загрузите M3U или выполните SAP/SDP discovery"); return; }
        final int count = Math.min(500, channels.size());
        if (channels.size() > 500) Toast.makeText(this, "За один запуск проверяются первые 500 каналов", Toast.LENGTH_LONG).show();
        cancelProbe.set(false);
        probeButton.setEnabled(false);
        stopButton.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final AtomicInteger next = new AtomicInteger(0);
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicInteger available = new AtomicInteger(0);
        final String base = udpxyBase.getText().toString().trim();
        final boolean directMulticast = base.length() == 0;
        diagnosticStatus.setText(directMulticast ? "Прямой multicast: последовательная проверка IGMP join" : "Проверка через udpxy/HTTP");
        final int workerCount = directMulticast ? 1 : 4;
        for (int worker = 0; worker < workerCount; worker++) {
            workers.execute(new Runnable() {
                @Override public void run() {
                    while (!cancelProbe.get()) {
                        int index = next.getAndIncrement();
                        if (index >= count) break;
                        final Models.IptvChannel channel = channels.get(index);
                        try {
                            channel.playbackUrl = IptvEngine.buildPlaybackUrl(base, channel.sourceUrl);
                            IptvEngine.ProbeResult result = directMulticast ? MulticastProbe.probe(IptvActivity.this, channel.playbackUrl, 3200) : IptvEngine.probeChannel(channel.playbackUrl, 2500);
                            IptvEngine.applyProbe(channel, result);
                            if (channel.playable) available.incrementAndGet();
                        } catch (Exception error) {
                            channel.playable = false;
                            channel.status = "ошибка";
                            channel.error = error.getMessage();
                        }
                        final int done = completed.incrementAndGet();
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                progress.setProgress(done * 100 / count);
                                diagnosticStatus.setText("Проверено " + done + "/" + count + " · доступно " + available.get());
                                if (done % 8 == 0 || done == count) { renderDiagnostics(); renderChannels(); }
                                if (done == count) finishProbe(false, available.get(), count);
                            }
                        });
                    }
                    if (cancelProbe.get()) runOnUiThread(new Runnable() { @Override public void run() { finishProbe(true, available.get(), completed.get()); } });
                }
            });
        }
    }

    private synchronized void finishProbe(boolean cancelled, int available, int checked) {
        if (probeButton.isEnabled()) return;
        probeButton.setEnabled(true);
        stopButton.setEnabled(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        diagnosticStatus.setText(cancelled ? "Проверка остановлена · обработано " + checked : "Готово · доступно " + available + " из " + checked);
        renderChannels();
        renderDiagnostics();
        saveChannels();
    }

    private void channelDialog(final Models.IptvChannel channel) {
        String body = "Название: " + channel.name + "\n" +
            "Группа: " + value(channel.group) + "\n" +
            "Источник: " + channel.sourceUrl + "\n" +
            "Playback: " + value(channel.playbackUrl) + "\n" +
            "Статус: " + channel.status + "\n" +
            "DVB service: " + value(channel.serviceName) + "\n" +
            "Провайдер: " + value(channel.providerName) + "\n" +
            "Контейнер: " + value(channel.container) + "\n" +
            "Видео: " + value(channel.videoCodec) + "\n" +
            "Аудио: " + value(channel.audioCodec) + "\n" +
            "Задержка: " + channel.latencyMs + " мс\n" +
            "Оценка bitrate: " + channel.bitrateKbps + " кбит/с\n" +
            "Ошибка: " + value(channel.error);
        new AlertDialog.Builder(this).setTitle(channel.name).setMessage(body).setNegativeButton("Закрыть", null)
            .setPositiveButton("Копировать URL", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { copy(channel.playbackUrl.length() > 0 ? channel.playbackUrl : channel.sourceUrl); } }).show();
    }

    private void loadLogo(final ImageView image, final String url) {
        workers.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(1800);
                    connection.setReadTimeout(2200);
                    connection.setInstanceFollowRedirects(true);
                    if (connection.getResponseCode() < 400) {
                        byte[] data = readBytes(connection.getInputStream(), 512 * 1024);
                        final Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bitmap != null) runOnUiThread(new Runnable() { @Override public void run() { image.setImageBitmap(bitmap); } });
                    }
                } catch (Exception ignored) { }
                finally { if (connection != null) connection.disconnect(); }
            }
        });
    }

    private void pickFile(int request, String[] types) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(types.length == 1 ? types[0] : "*/*");
        if (types.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(final int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        workers.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String content = readUri(uri, IptvEngine.MAX_PLAYLIST_BYTES);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (requestCode == PICK_M3U) parsePlaylist(content);
                            else {
                                try {
                                    IptvEngine.applyXmlTv(content, channels);
                                    sourceStatus.setText("XMLTV применён к " + channels.size() + " каналам");
                                    renderChannels();
                                    saveChannels();
                                } catch (Exception error) { showError("XMLTV", error.getMessage()); }
                            }
                        }
                    });
                } catch (final Exception error) { runOnUiThread(new Runnable() { @Override public void run() { showError("Файл", error.getMessage()); } }); }
            }
        });
    }

    private String readUri(Uri uri, int max) throws Exception {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new Exception("Файл недоступен");
        byte[] data = readBytes(input, max);
        input.close();
        return new String(data, "UTF-8");
    }

    private byte[] readBytes(InputStream input, int max) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() < max) {
            int read = input.read(buffer, 0, Math.min(buffer.length, max - output.size()));
            if (read < 0) break;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void acquireMulticastLock() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager != null) {
                multicastLock = manager.createMulticastLock("sitescope-iptv-sap");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseMulticastLock() {
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); }
        catch (Exception ignored) { }
        multicastLock = null;
    }

    private boolean checkAuthorization() {
        if (authorized != null && authorized.isChecked()) return true;
        showError("Требуется подтверждение", "На вкладке «Источники» подтвердите владение сетью/потоками или наличие разрешения.");
        return false;
    }

    private void copyTable() { copy(buildTable()); }

    private void shareTable() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "SiteScope Pro IPTV inventory");
        intent.putExtra(Intent.EXTRA_TEXT, buildTable());
        startActivity(Intent.createChooser(intent, "Отправить IPTV-отчёт"));
    }

    private String buildTable() {
        StringBuilder out = new StringBuilder("SiteScope Pro IPTV\n");
        for (Models.IptvChannel channel : channels) {
            out.append(channel.playable ? "OK " : "-- ").append(channel.name).append(" | ")
                .append(channel.playbackUrl.length() > 0 ? channel.playbackUrl : channel.sourceUrl).append(" | ")
                .append(value(channel.container)).append(" | ").append(value(channel.videoCodec)).append(" | ")
                .append(value(channel.audioCodec)).append('\n');
        }
        return out.toString();
    }

    private void copy(String value) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        manager.setPrimaryClip(ClipData.newPlainText("SiteScope", value == null ? "" : value));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private void saveChannels() {
        JSONArray array = new JSONArray();
        int max = Math.min(1000, channels.size());
        int playable = 0;
        for (int i = 0; i < max; i++) {
            Models.IptvChannel channel = channels.get(i);
            array.put(channel.toJson());
            if (channel.playable) playable++;
        }
        String base = udpxyBase == null ? "" : udpxyBase.getText().toString();
        getSharedPreferences("sitescope_iptv", MODE_PRIVATE).edit().putString("channels", array.toString()).putString("udpxy", base).apply();
        DataStore store = new DataStore(this);
        if (max == 0) store.removeProject("IPTV", "iptv://catalog");
        else store.saveIptvSnapshot(max, playable, base.length() > 0 ? base : "Прямой multicast / плейлист");
    }

    private void restoreChannels() {
        try {
            String raw = getSharedPreferences("sitescope_iptv", MODE_PRIVATE).getString("channels", "[]");
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) channels.add(Models.IptvChannel.fromJson(array.getJSONObject(i)));
            String base = getSharedPreferences("sitescope_iptv", MODE_PRIVATE).getString("udpxy", "");
            udpxyBase.setText(base);
            Set<String> stored = getSharedPreferences("sitescope_iptv", MODE_PRIVATE).getStringSet("favorites", new HashSet<String>());
            favorites.addAll(stored);
        } catch (Exception ignored) { }
        selectedIndex = channels.isEmpty() ? -1 : 0;
        renderChannels();
        renderDiagnostics();
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message == null ? "Неизвестная ошибка" : message).setPositiveButton("Понятно", null).show();
    }

    private String value(String value) { return value == null || value.trim().length() == 0 ? "—" : value.trim(); }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(6), dp(14), dp(28));
        return page;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(child, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout card() { return card(14); }

    private LinearLayout card(int padding) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        box.setBackground(round(SURFACE, 16, BORDER, 1));
        box.setElevation(dp(1));
        box.setLayoutParams(params(-1, -2, 0, 0, 0, 8));
        return box;
    }

    private LinearLayout titleRow(String icon, String title, String subtitle) {
        LinearLayout row = row();
        TextView iconView = text(icon, 20, PRIMARY, false);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(round(Color.WHITE, 12, Color.TRANSPARENT, 0));
        row.addView(iconView, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(title, 15, NAVY, true));
        labels.addView(text(subtitle, 11, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private TextView section(String title, String subtitle) {
        TextView view = text(title + "\n" + subtitle, 16, NAVY, true);
        view.setLineSpacing(0f, 1.1f);
        view.setPadding(dp(2), dp(14), dp(2), dp(8));
        return view;
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);
        edit.setTextSize(14);
        edit.setTextColor(NAVY);
        edit.setHintTextColor(Color.rgb(150, 160, 176));
        edit.setHint(hint);
        edit.setPadding(dp(12), dp(7), dp(12), dp(7));
        edit.setBackground(round(Color.WHITE, 11, BORDER, 1));
        return edit;
    }

    private TextView tabButton(String title) {
        TextView view = text(title, 13, MUTED, true);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private void styleTab(TextView view, boolean selected) {
        view.setTextColor(selected ? PRIMARY : MUTED);
        view.setBackground(round(selected ? PRIMARY_SOFT : Color.rgb(247, 249, 252), 11, selected ? PRIMARY_SOFT : BORDER, 1));
    }

    private TextView filterChip(String title, boolean selected) {
        TextView view = text(title, 12, selected ? Color.WHITE : NAVY, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(13), 0, dp(13), 0);
        view.setBackground(round(selected ? PRIMARY : Color.rgb(247, 249, 252), 999, selected ? PRIMARY : BORDER, 1));
        return view;
    }

    private TextView compactButton(String title, boolean primary) {
        TextView view = text(title, 12, primary ? Color.WHITE : PRIMARY, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(primary ? PRIMARY : Color.WHITE, 10, primary ? PRIMARY : BORDER, 1));
        view.setPadding(dp(7), 0, dp(7), 0);
        return view;
    }

    private TextView darkControl(String title) {
        TextView view = text(title, 12, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(Color.rgb(35, 46, 65), 10, Color.rgb(65, 80, 104), 1));
        return view;
    }

    private TextView iconButton(String title) {
        TextView view = text(title, 19, NAVY, false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(Color.rgb(247, 249, 252), 11, BORDER, 1));
        return view;
    }

    private TextView statusBox(String value) {
        TextView view = text(value, 12, Color.rgb(49, 78, 123), false);
        view.setPadding(dp(12), dp(11), dp(12), dp(11));
        view.setBackground(round(PRIMARY_SOFT, 11, Color.TRANSPARENT, 0));
        return view;
    }

    private TextView smallBadge(String value) {
        TextView view = text(value, 9, PRIMARY, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(5), dp(2), dp(5), dp(2));
        view.setBackground(round(PRIMARY_SOFT, 999, Color.TRANSPARENT, 0));
        return view;
    }

    private TextView badge(String value, int color, int background) {
        TextView view = text(value, 11, color, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(9), dp(5), dp(9), dp(5));
        view.setBackground(round(background, 999, Color.TRANSPARENT, 0));
        return view;
    }

    private TextView metricBox(String value, String caption, int color, int background) {
        TextView view = text(value + "\n" + caption, 13, color, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(background, 12, Color.TRANSPARENT, 0));
        return view;
    }

    private LinearLayout keyValue(String key, String value) {
        LinearLayout row = row();
        row.setPadding(0, dp(7), 0, dp(7));
        row.addView(text(key, 12, MUTED, true), new LinearLayout.LayoutParams(dp(92), -2));
        TextView val = text(value, 12, NAVY, false);
        val.setTextIsSelectable(true);
        row.addView(val, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.06f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private View space(int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1));
        return v;
    }

    private LinearLayout.LayoutParams params(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeWidth > 0 && stroke != Color.TRANSPARENT) d.setStroke(dp(strokeWidth), stroke);
        return d;
    }

    private GradientDrawable letterDrawable(String name) {
        int hash = Math.abs((name == null ? "" : name).hashCode());
        int[] colors = {Color.rgb(217, 83, 43), Color.rgb(20,145,93), Color.rgb(124,74,184), Color.rgb(210,108,31)};
        return round(colors[hash % colors.length], 12, Color.TRANSPARENT, 0);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
