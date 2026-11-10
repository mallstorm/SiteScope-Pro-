package pro.sitescope.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.Locale;

/** Exact-certificate pin store for explicitly trusted Web Client endpoints. */
public final class TlsTrustStore {
    private static final String PREFS = "sitescope_tls_pins_v1";

    private TlsTrustStore() { }

    public static String authority(String url) {
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            String host = uri.getHost();
            if (host == null || host.trim().length() == 0) return "unknown:443";
            int port = uri.getPort();
            if (port < 0) port = "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
            return host.toLowerCase(Locale.US) + ":" + port;
        } catch (Exception ignored) {
            return "unknown:443";
        }
    }

    public static String pinnedFingerprint(Context context, String authority) {
        return prefs(context).getString("pin." + authority, "");
    }

    public static boolean matches(Context context, String authority, String fingerprint) {
        String pinned = pinnedFingerprint(context, authority);
        return pinned.length() > 0 && pinned.equalsIgnoreCase(fingerprint == null ? "" : fingerprint);
    }

    public static void trust(Context context, String authority, SslCertificate certificate) {
        String fingerprint = fingerprint(certificate);
        if (fingerprint.length() == 0) throw new IllegalArgumentException("Не удалось получить отпечаток сертификата");
        prefs(context).edit()
            .putString("pin." + authority, fingerprint)
            .putString("subject." + authority, certificate == null ? "" : certificate.getIssuedTo().getDName())
            .putString("issuer." + authority, certificate == null ? "" : certificate.getIssuedBy().getDName())
            .putLong("saved." + authority, System.currentTimeMillis())
            .apply();
    }

    public static void clear(Context context, String authority) {
        prefs(context).edit()
            .remove("pin." + authority)
            .remove("subject." + authority)
            .remove("issuer." + authority)
            .remove("saved." + authority)
            .apply();
    }

    public static String fingerprint(SslCertificate certificate) {
        X509Certificate x509 = x509(certificate);
        if (x509 == null) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(x509.getEncoded());
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) out.append(':');
                out.append(String.format(Locale.US, "%02X", digest[i] & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String details(SslCertificate certificate) {
        if (certificate == null) return "Сертификат недоступен";
        StringBuilder out = new StringBuilder();
        out.append("Кому: ").append(certificate.getIssuedTo().getDName()).append('\n');
        out.append("Кем: ").append(certificate.getIssuedBy().getDName()).append('\n');
        DateFormat format = DateFormat.getDateTimeInstance();
        if (certificate.getValidNotBeforeDate() != null) out.append("Действует с: ").append(format.format(certificate.getValidNotBeforeDate())).append('\n');
        if (certificate.getValidNotAfterDate() != null) out.append("Действует до: ").append(format.format(certificate.getValidNotAfterDate())).append('\n');
        String fingerprint = fingerprint(certificate);
        out.append("SHA-256:\n").append(fingerprint.length() == 0 ? "недоступен" : fingerprint);
        X509Certificate x509 = x509(certificate);
        if (x509 != null) {
            out.append("\nСерийный номер: ").append(x509.getSerialNumber().toString(16).toUpperCase(Locale.US));
            out.append("\nАлгоритм подписи: ").append(x509.getSigAlgName());
        }
        return out.toString();
    }

    private static X509Certificate x509(SslCertificate certificate) {
        if (certificate == null) return null;
        try {
            Bundle state = SslCertificate.saveState(certificate);
            byte[] encoded = state == null ? null : state.getByteArray("x509-certificate");
            if (encoded == null || encoded.length == 0) return null;
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(encoded));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
