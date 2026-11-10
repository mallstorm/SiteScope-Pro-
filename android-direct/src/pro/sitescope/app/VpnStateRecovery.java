package pro.sitescope.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Repairs a stale UI state after Android or a native library terminated the VPN process. */
public final class VpnStateRecovery {
    private static final long STALE_SERVICE_MS = 9000L;

    private VpnStateRecovery() { }

    public static boolean recoverIfStale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE);
        String state = prefs.getString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_OFF);
        boolean managed = HostsVpnService.STATE_STARTING.equals(state)
            || HostsVpnService.STATE_ACTIVE.equals(state)
            || HostsVpnService.STATE_STOPPING.equals(state);
        if (!managed) return false;
        long heartbeat = prefs.getLong("serviceHeartbeatAt", 0L);
        long age = heartbeat <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - heartbeat;
        if (age <= STALE_SERVICE_MS) return false;
        String previousMessage = prefs.getString("message", "");
        String message = "VPN-служба была прервана Android или нативным core; состояние восстановлено"
            + (previousMessage.length() == 0 ? "" : " · предыдущая фаза: " + previousMessage);
        prefs.edit()
            .putString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_ERROR)
            .putBoolean("active", false)
            .putString("mode", "")
            .putString("message", message)
            .putString("profile", "")
            .putString("profileId", "")
            .putString("endpoint", "")
            .putString("coreVersion", "")
            .putLong("changedAt", System.currentTimeMillis())
            .putLong("heartbeatAt", 0L)
            .commit();
        VpnDiagnostics.log(context, "VPN_STALE_RECOVERED", "state=" + state + ", serviceHeartbeatAge=" + age + "ms");
        return true;
    }
}
