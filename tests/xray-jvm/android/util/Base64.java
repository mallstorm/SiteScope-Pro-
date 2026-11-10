package android.util;

/** JVM test shim for the Android Base64 methods used by XrayProfileParser. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;
    private Base64() { }
    public static byte[] decode(String value, int flags) {
        if ((flags & URL_SAFE) != 0) return java.util.Base64.getUrlDecoder().decode(value);
        return java.util.Base64.getDecoder().decode(value);
    }
}
