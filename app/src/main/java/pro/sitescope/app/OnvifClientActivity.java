package pro.sitescope.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Compatibility entry point; all camera requests use the shared CameraClient. */
public final class OnvifClientActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ServiceEndpoint endpoint = ServiceEndpoint.from(getIntent());endpoint.protocol = "ONVIF";
        Intent intent = new Intent(this, pro.sitescope.app.client.ClientActivity.class);endpoint.put(intent);
        startActivity(intent);finish();
    }
}
