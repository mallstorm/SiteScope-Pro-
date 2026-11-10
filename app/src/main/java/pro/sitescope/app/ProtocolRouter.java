package pro.sitescope.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

public final class ProtocolRouter {
    private ProtocolRouter() { }

    public static void open(Activity activity, Models.PortResult result) {
        dispatch(activity, ServiceEndpoint.from(result));
    }

    public static void open(Activity activity, Models.DiscoveredDevice device) {
        dispatch(activity, ServiceEndpoint.from(device));
    }

    public static void dispatch(Activity activity, ServiceEndpoint endpoint) {
        String handler = ProtocolPreferences.handler(activity, endpoint.protocol);
        if (ProtocolPreferences.BUILTIN.equals(handler) && supportsBuiltIn(endpoint.protocol)) {
            openBuiltIn(activity, endpoint);
            return;
        }
        if (ProtocolPreferences.EXTERNAL.equals(handler)) {
            openExternal(activity, endpoint);
            return;
        }
        openActionPanel(activity, endpoint);
    }

    public static boolean supportsBuiltIn(String protocol) {
        return "HTTP".equals(protocol) || "HTTPS".equals(protocol) || "RTSP".equals(protocol)
            || "SSH".equals(protocol) || "SFTP".equals(protocol)
            || "FTP".equals(protocol) || "FTPS".equals(protocol) || "WEBDAV".equals(protocol)
            || "VNC".equals(protocol) || "SNMP".equals(protocol) || "MQTT".equals(protocol)
            || "ONVIF".equals(protocol);
    }

    public static void openBuiltIn(Activity activity, ServiceEndpoint endpoint) {
        ConnectionProfileStore.recordOpened(activity, endpoint);
        ConnectionSessionStore.recordOpen(activity, endpoint, "LAUNCHED", "Встроенный клиент");
        if ("HTTP".equals(endpoint.protocol) || "HTTPS".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, WebClientActivity.class);
            endpoint.put(intent);
            activity.startActivity(intent);
            return;
        }
        if ("RTSP".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, PlayerActivity.class);
            String uri = endpoint.uri.length() > 0 ? endpoint.uri : ServiceEndpoint.buildUri("RTSP", endpoint.host, endpoint.port, "");
            intent.putExtra("url", uri);
            intent.putExtra("source_url", uri);
            intent.putExtra("name", endpoint.displayName.length() > 0 ? endpoint.displayName : endpoint.endpointText());
            intent.putExtra("details", "RTSP · " + endpoint.resourceType);
            activity.startActivity(intent);
            return;
        }
        if ("SSH".equals(endpoint.protocol) || "SFTP".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, SshClientActivity.class);
            endpoint.put(intent);
            intent.putExtra("mode", endpoint.protocol);
            activity.startActivity(intent);
            return;
        }
        if ("FTP".equals(endpoint.protocol) || "FTPS".equals(endpoint.protocol) || "WEBDAV".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, NativeFileClientActivity.class);
            endpoint.put(intent);
            activity.startActivity(intent);
            return;
        }
        if ("VNC".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, VncClientActivity.class);
            endpoint.put(intent);
            activity.startActivity(intent);
            return;
        }
        if ("SNMP".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, SnmpClientActivity.class);
            endpoint.put(intent);
            activity.startActivity(intent);
            return;
        }
        if ("MQTT".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, MqttClientActivity.class);
            endpoint.put(intent);
            activity.startActivity(intent);
            return;
        }
        if ("ONVIF".equals(endpoint.protocol)) {
            Intent intent = new Intent(activity, OnvifClientActivity.class);
            endpoint.put(intent);
            activity.startActivity(intent);
            return;
        }
        openActionPanel(activity, endpoint);
    }

    public static void openActionPanel(Activity activity, ServiceEndpoint endpoint) {
        Intent intent = new Intent(activity, ServiceActionActivity.class);
        endpoint.put(intent);
        activity.startActivity(intent);
    }

    public static void openExternal(Activity activity, ServiceEndpoint endpoint) {
        ConnectionProfileStore.recordOpened(activity, endpoint);
        ConnectionSessionStore.recordOpen(activity, endpoint, "LAUNCHED", "Внешний Android-клиент");
        String uriText = endpoint.uri.length() > 0 ? endpoint.uri : ServiceEndpoint.buildUri(endpoint.protocol, endpoint.host, endpoint.port, endpoint.location);
        if (uriText.length() == 0) {
            Toast.makeText(activity, "Не удалось сформировать адрес сервиса", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(uriText));
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(Intent.createChooser(view, "Открыть " + endpoint.protocol));
        } catch (ActivityNotFoundException error) {
            copy(activity, uriText);
            Toast.makeText(activity, "Совместимый клиент не найден. Адрес скопирован.", Toast.LENGTH_LONG).show();
        }
    }

    public static void copy(Context context, String value) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Service endpoint", value));
    }
}
