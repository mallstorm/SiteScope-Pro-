package pro.sitescope.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends BaseSiteScopeActivity {
    private DataStore store;
    private HistoryAdapter adapter;
    private String filter = "ALL";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DataStore(this);
        setRootScreen(buildScreen(), "HISTORY");
    }

    @Override protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.reload(filter);
    }

    private View buildScreen() {
        LinearLayout root = page();
        root.addView(appHeader("История", "Последние анализы и сканирования", true));

        LinearLayout filters = row();
        final TextView all = smallOutlineButton("Все");
        final TextView seo = smallOutlineButton("SEO");
        final TextView ip = smallOutlineButton("IP");
        final TextView iptv = smallOutlineButton("IPTV");
        TextView[] buttons = {all, seo, ip, iptv};
        for (int i = 0; i < buttons.length; i++) {
            filters.addView(buttons[i], new LinearLayout.LayoutParams(0, dp(40), 1f));
            if (i < buttons.length - 1) filters.addView(space(6));
        }
        root.addView(filters, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 8));

        ListView list = new ListView(this);
        list.setDividerHeight(dp(8));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(12));
        adapter = new HistoryAdapter();
        adapter.reload(filter);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) { open(adapter.getItem(position)); }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View.OnClickListener choose = new View.OnClickListener() {
            @Override public void onClick(View view) {
                filter = view == seo ? "SEO" : view == ip ? "IP" : view == iptv ? "IPTV" : "ALL";
                adapter.reload(filter);
            }
        };
        all.setOnClickListener(choose); seo.setOnClickListener(choose); ip.setOnClickListener(choose); iptv.setOnClickListener(choose);

        TextView clear = outlineButton("Очистить историю");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new AlertDialog.Builder(HistoryActivity.this).setTitle("Очистить историю?")
                    .setMessage("SEO-аудиты, IP-сканирования и IPTV-снимки будут удалены. Проекты останутся.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Очистить", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            store.clearHistory(); store.clearScans(); store.clearIptvHistory(); adapter.reload(filter);
                        }
                    }).show();
            }
        });
        root.addView(clear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        return root;
    }

    private View auditCard(Models.AuditResult audit) {
        LinearLayout card = card(15);
        LinearLayout top = row();
        TextView score = label(String.valueOf(audit.score), 22, scoreColor(audit.score), true);
        score.setGravity(Gravity.CENTER);
        score.setBackground(round(scoreSoftColor(audit.score), 12, Color.TRANSPARENT, 0));
        top.addView(score, new LinearLayout.LayoutParams(dp(58), dp(54)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        text.addView(label(audit.host.length() > 0 ? audit.host : audit.target, 16, NAVY, true));
        text.addView(label(formatDate(audit.timestamp) + " · " + audit.issues.size() + " проблем", 13, MUTED, false));
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(badge("SEO", PRIMARY, PRIMARY_SOFT));
        card.addView(top);
        return card;
    }

    private View scanCard(Models.ScanSession session) {
        LinearLayout card = card(15);
        LinearLayout top = row();
        TextView icon = label("IP", 15, GREEN, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(GREEN_SOFT, 12, Color.TRANSPARENT, 0));
        top.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        text.addView(label(session.targetExpression, 15, NAVY, true));
        text.addView(label(formatDate(session.timestamp) + " · " + session.openResults.size() + " открытых сервисов", 12, MUTED, false));
        text.addView(label("Порты: " + session.portExpression, 12, MUTED, false));
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(label("›", 26, MUTED, false));
        card.addView(top);
        return card;
    }

    private View iptvCard(Models.IptvSnapshot snapshot) {
        LinearLayout card = card(15);
        LinearLayout top = row();
        TextView icon = label("TV", 14, Color.rgb(109, 67, 190), true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Color.rgb(241, 233, 255), 12, Color.TRANSPARENT, 0));
        top.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        text.addView(label("IPTV-каталог", 15, NAVY, true));
        text.addView(label(formatDate(snapshot.timestamp) + " · " + snapshot.channelCount + " каналов", 12, MUTED, false));
        text.addView(label("Доступно: " + snapshot.playableCount + " · " + value(snapshot.source), 12, MUTED, false));
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(label("›", 26, MUTED, false));
        card.addView(top);
        return card;
    }

    private void open(HistoryItem item) {
        if ("SEO".equals(item.type)) {
            Models.AuditResult audit = (Models.AuditResult) item.value;
            Intent intent = new Intent(this, SeoActivity.class);
            intent.putExtra("audit_timestamp", audit.timestamp);
            startActivity(intent);
        } else if ("IP".equals(item.type)) {
            showScanDialog((Models.ScanSession) item.value);
        } else {
            startActivity(new Intent(this, IptvActivity.class));
        }
    }

    private void showScanDialog(Models.ScanSession session) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(12));
        body.addView(keyValue("Цель", session.targetExpression));
        body.addView(keyValue("Порты", session.portExpression));
        body.addView(keyValue("Проверено", session.completedProbes + " / " + session.totalProbes));
        body.addView(keyValue("Найдено", String.valueOf(session.openResults.size())));
        int count = Math.min(50, session.openResults.size());
        for (int i = 0; i < count; i++) {
            Models.PortResult result = session.openResults.get(i);
            LinearLayout row = card(12);
            row.addView(label(result.host + ":" + result.port, 15, GREEN, true));
            row.addView(label(value(result.service) + " · " + result.latencyMs + " мс", 12, MUTED, false));
            body.addView(row);
        }
        if (session.openResults.size() > count) body.addView(infoStrip("Показаны первые " + count + " результатов из " + session.openResults.size() + "."));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("IP-сканирование").setView(scroll).setPositiveButton("Закрыть", null).show();
    }

    private static final class HistoryItem {
        final String type;
        final Object value;
        HistoryItem(String type, Object value) { this.type = type; this.value = value; }
    }

    private final class HistoryAdapter extends BaseAdapter {
        private final List<HistoryItem> rows = new ArrayList<HistoryItem>();
        void reload(String currentFilter) {
            rows.clear();
            if ("ALL".equals(currentFilter) || "SEO".equals(currentFilter)) for (Models.AuditResult a : store.audits()) rows.add(new HistoryItem("SEO", a));
            if ("ALL".equals(currentFilter) || "IP".equals(currentFilter)) for (Models.ScanSession s : store.scans()) rows.add(new HistoryItem("IP", s));
            if ("ALL".equals(currentFilter) || "IPTV".equals(currentFilter)) for (Models.IptvSnapshot s : store.iptvHistory()) rows.add(new HistoryItem("IPTV", s));
            notifyDataSetChanged();
        }
        @Override public int getCount() { return rows.size(); }
        @Override public HistoryItem getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            HistoryItem item = getItem(position);
            return "SEO".equals(item.type) ? auditCard((Models.AuditResult) item.value) : "IP".equals(item.type) ? scanCard((Models.ScanSession) item.value) : iptvCard((Models.IptvSnapshot) item.value);
        }
    }
}
