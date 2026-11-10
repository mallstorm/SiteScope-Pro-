package pro.sitescope.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Focused IP Engineering hub. Each engineering task opens in its own workspace. */
public final class IpEngineeringActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout page = UiKit.page(this);
        page.addView(UiKit.header(this, "🧭", "IP Engineering", "Сети, адреса и обнаруженные сервисы"));
        page.addView(tile("🌍", "IP Intelligence", "География, ASN, провайдеры и диапазоны", IpIntelligenceActivity.class));
        page.addView(tile("⌁", "Сканер сети", "TCP-сервисы, сигнатуры и результаты в реальном времени", ScannerActivity.class));
        page.addView(tile("📡", "LAN Discovery", "SSDP/UPnP, ONVIF и mDNS/DNS-SD", DiscoveryActivity.class));
        page.addView(tile("▦", "Инвентаризация", "Снимки сети, новые и исчезнувшие устройства", InventoryActivity.class));
        page.addView(tile("⇄", "Подключения", "Web, SSH, SFTP, VNC и сохранённые endpoints", ConnectionsActivity.class));
        page.addView(tile("H", "Hosts Manager", "Системные доменные правила, профили и безопасное применение", HostsActivity.class));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        setContentView(scroll);
    }

    private View tile(String icon, String title, String subtitle, final Class<?> target) {
        LinearLayout card = UiKit.card(this, 16);
        card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
        TextView iconView = UiKit.text(this, icon, 26, UiKit.NAVY, false); iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(UiKit.round(Color.WHITE,16,UiKit.BORDER,1,this));
        card.addView(iconView, new LinearLayout.LayoutParams(UiKit.dp(this,62),UiKit.dp(this,62)));
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(UiKit.dp(this,14),0,0,0);
        text.addView(UiKit.text(this,title,17,UiKit.NAVY,true)); text.addView(UiKit.text(this,subtitle,12,UiKit.MUTED,false));
        card.addView(text,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView arrow=UiKit.text(this,"›",28,UiKit.PRIMARY,true); arrow.setGravity(Gravity.CENTER); card.addView(arrow,new LinearLayout.LayoutParams(UiKit.dp(this,28),UiKit.dp(this,42)));
        card.setClickable(true); card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ startActivity(new Intent(IpEngineeringActivity.this,target)); } });
        card.setLayoutParams(UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,6,0,6));
        return card;
    }
}
