#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d /tmp/sitescope-protocol-test-XXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/pro/sitescope/app" "$TMP/classes"
cp "$PROJ/android-direct/src/pro/sitescope/app/ServiceEndpoint.java" "$TMP/src/pro/sitescope/app/"
cat > "$TMP/src/pro/sitescope/app/Models.java" <<'JAVA'
package pro.sitescope.app;
public final class Models {
  public static final class PortResult { public String target="", ip="", service="", resourceType="", evidence="", banner="", tlsSubject="", confidence="", description=""; public int port; public long latencyMs; }
  public static final class DiscoveredDevice { public String ip="", protocol="", deviceType="", name="", hostname="", location="", services="", evidence="", confidence=""; public int port; public long latencyMs; }
}
JAVA
cat > "$TMP/src/pro/sitescope/app/ProtocolHarness.java" <<'JAVA'
package pro.sitescope.app;
public final class ProtocolHarness {
  private static void eq(String expected, String actual) { if (!expected.equals(actual)) throw new AssertionError(expected+" != "+actual); }
  public static void main(String[] args) {
    eq("HTTPS", ServiceEndpoint.detect("HTTPS", 443, "TLS", ""));
    eq("SSH", ServiceEndpoint.detect("SSH", 22, "SSH-2.0", ""));
    eq("RDP", ServiceEndpoint.detect("Unknown TCP", 3389, "", ""));
    eq("VNC", ServiceEndpoint.detect("VNC", 5901, "RFB", ""));
    eq("RTSP", ServiceEndpoint.detect("RTSP", 554, "RTSP/1.0", ""));
    eq("UPNP", ServiceEndpoint.detect("SSDP/UPnP", 1900, "rootdevice", "http://192.168.1.1/device.xml"));
    eq("MDNS", ServiceEndpoint.detect("mDNS/DNS-SD", 5353, "_http._tcp.local", ""));
    eq("ONVIF", ServiceEndpoint.detect("ONVIF WS-Discovery", 3702, "ProbeMatches", ""));
    eq("https://192.0.2.1:8443/", ServiceEndpoint.buildUri("HTTPS", "192.0.2.1", 8443, ""));
    eq("rtsp://[2001:db8::1]:554/", ServiceEndpoint.buildUri("RTSP", "2001:db8::1", 554, ""));
    System.out.println("Protocol Router harness PASS");
  }
}
JAVA
# Minimal Android stubs required by ServiceEndpoint's Intent/Uri imports.
mkdir -p "$TMP/src/android/content" "$TMP/src/android/net"
cat > "$TMP/src/android/content/Intent.java" <<'JAVA'
package android.content;
public class Intent { public Intent putExtra(String k,String v){return this;} public Intent putExtra(String k,int v){return this;} public Intent putExtra(String k,long v){return this;} public String getStringExtra(String k){return null;} public int getIntExtra(String k,int d){return d;} public long getLongExtra(String k,long d){return d;} }
JAVA
cat > "$TMP/src/android/net/Uri.java" <<'JAVA'
package android.net; public class Uri { }
JAVA
javac -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" $(find "$TMP/src" -name '*.java')
java -cp "$TMP/classes" pro.sitescope.app.ProtocolHarness
