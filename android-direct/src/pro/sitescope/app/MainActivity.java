package pro.sitescope.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends BaseSiteScopeActivity {
    @Override public void onCreate(Bundle state) {
        final boolean recoveredVpnProcess = VpnStateRecovery.recoverIfStale(this);
        super.onCreate(state);
        setRootScreen(homeScreen(), "HOME");
        if (recoveredVpnProcess) {
            getWindow().getDecorView().post(new Runnable() { @Override public void run() {
                startActivity(new Intent(MainActivity.this, HostsActivity.class));
            } });
        }
    }

    private View homeScreen() {
        LinearLayout page = page();
        page.setPadding(dp(16), dp(14), dp(16), dp(30));
        page.addView(homeHeader());

        LinearLayout section = sectionTitle("Рабочие пространства", "Выберите направление анализа");
        section.setPadding(0, dp(22), 0, dp(8));
        page.addView(section);

        page.addView(frontPageCard("S", "SEO-аудит", "Сайт, технологии, DNS и поисковые сигналы", Color.rgb(252, 237, 207), new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(new Intent(MainActivity.this, SeoActivity.class)); }
        }));
        page.addView(frontPageCard("IP", "IP Engineering", "Сети, домены, порты и сетевые сервисы", Color.rgb(225, 242, 214), new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(new Intent(MainActivity.this, IpEngineeringActivity.class)); }
        }));
        page.addView(frontPageCard("TV", "IPTV Engineering", "Потоки, multicast, udpxy и плейлисты", Color.rgb(236, 226, 255), new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(new Intent(MainActivity.this, IptvActivity.class)); }
        }));
        return scroll(page);
    }

    private LinearLayout homeHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(18));
        header.setBackground(headerGradient());
        header.setElevation(dp(5));

        LinearLayout top = row();
        TextView logo = label("S", 21, NAVY, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(Color.WHITE, 15, Color.TRANSPARENT, 0));
        top.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(13), 0, 0, 0);
        identity.addView(label("SiteScope Pro", 22, Color.WHITE, true));
        identity.addView(label("Инженерный центр анализа", 12, Color.rgb(198, 216, 244), false));
        top.addView(identity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = label("0.39 α", 11, Color.WHITE, true);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10), dp(6), dp(10), dp(6));
        version.setBackground(round(Color.argb(42, 255, 255, 255), 999, Color.argb(70, 255, 255, 255), 1));
        top.addView(version);

        TextView settings = label("⚙", 22, Color.WHITE, false);
        settings.setGravity(Gravity.CENTER);
        settings.setContentDescription("Настройки");
        settings.setBackground(round(Color.argb(42, 255, 255, 255), 14, Color.argb(70, 255, 255, 255), 1));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        top.addView(settings, marginParams(dp(42), dp(42), 9, 0, 0, 0));
        header.addView(top);

        header.addView(label("Единый центр анализа", 27, Color.WHITE, true),
                marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 22, 0, 0));
        header.addView(label("Аудит сайтов, диагностика сетей и работа с IPTV — в одном профессиональном пространстве.",
                        14, Color.rgb(214, 226, 246), false),
                marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 7, 0, 0));

        LinearLayout chips = row();
        chips.addView(headerChip("SEO", "Сайты"), new LinearLayout.LayoutParams(0, dp(42), 1f));
        chips.addView(space(7));
        chips.addView(headerChip("IP", "Сети"), new LinearLayout.LayoutParams(0, dp(42), 1f));
        chips.addView(space(7));
        chips.addView(headerChip("IPTV", "Потоки"), new LinearLayout.LayoutParams(0, dp(42), 1f));
        header.addView(chips, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18, 0, 0));
        return header;
    }

    private TextView headerChip(String title, String subtitle) {
        TextView chip = label(title + "  ·  " + subtitle, 11, Color.WHITE, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setBackground(round(Color.argb(34, 255, 255, 255), 13, Color.argb(62, 255, 255, 255), 1));
        return chip;
    }

    private GradientDrawable headerGradient() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(18, 31, 53), Color.rgb(17, 77, 151), Color.rgb(13, 99, 229)}
        );
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    private LinearLayout frontPageCard(String iconText, String title, String subtitle, int iconBackground, View.OnClickListener listener) {
        LinearLayout card = card(16);
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout row = row();
        TextView icon = label(iconText, iconText.length() > 1 ? 17 : 22, NAVY, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(iconBackground, 18, Color.TRANSPARENT, 0));
        row.addView(icon, new LinearLayout.LayoutParams(dp(82), dp(82)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(16), 0, 0, 0);
        text.addView(label(title, 18, NAVY, true));
        text.addView(label(subtitle, 13, MUTED, false), marginParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 7, 0, 0));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = label("›", 28, PRIMARY, true);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        card.addView(row);
        card.setOnClickListener(listener);
        return card;
    }
}
