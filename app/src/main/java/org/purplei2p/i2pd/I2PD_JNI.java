package org.purplei2p.i2pd;

/** JNI contract required by the official PurpleI2P Android library. */
public final class I2PD_JNI {
    private static volatile boolean loaded;
    private I2PD_JNI() {}

    public static synchronized void loadLibraries() {
        if (!loaded) {
            System.loadLibrary("i2pd");
            loaded = true;
        }
    }

    public static native String getABICompiledWith();
    public static native int getBOBState();
    public static native String getDataDir();
    public static native int getHTTPProxyState();
    public static native int getI2CPState();
    public static native int getSAMState();
    public static native int getSOCKSProxyState();
    public static native int getTransitTunnelsCount();
    public static native String getWebConsAddr();
    public static native void onNetworkStateChanged(boolean available);
    public static native void reloadTunnelsConfigs();
    public static native void setDataDir(String dataDir);
    public static native void setLanguage(String language);
    public static native void startAcceptingTunnels();
    public static native String startDaemon();
    public static native void stopAcceptingTunnels();
    public static native void stopDaemon();
}
