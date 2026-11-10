package pro.sitescope.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Прежний плеер на VideoView заменён на pro.sitescope.app.tv.TvPlayerActivity (Media3).
 * Класс оставлен как переходник: ProtocolRouter, DLNA и инженерный экран IPTV открывают поток по-старому.
 */
public class PlayerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent().setClassName(this, "pro.sitescope.app.tv.TvPlayerActivity");
        Intent source = getIntent();
        if (source != null) {
            String sourceUrl = source.getStringExtra("source_url");
            String url = source.getStringExtra("url");
            // Для udp/rtp новому плееру нужен исходный адрес: он сам поднимает локальный мост.
            intent.putExtra("url", sourceUrl != null && (sourceUrl.startsWith("udp://") || sourceUrl.startsWith("rtp://")) && (url == null || url.equals(sourceUrl)) ? sourceUrl : url);
            intent.putExtra("name", source.getStringExtra("name"));
            intent.putExtra("details", source.getStringExtra("details"));
            intent.putExtra("previous_url", source.getStringExtra("previous_url"));
            intent.putExtra("previous_name", source.getStringExtra("previous_name"));
            intent.putExtra("next_url", source.getStringExtra("next_url"));
            intent.putExtra("next_name", source.getStringExtra("next_name"));
        }
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }
}
