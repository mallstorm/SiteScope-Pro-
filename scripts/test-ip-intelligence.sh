#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"
TMP="/tmp/sitescope-ip-intelligence-test-$$"
mkdir -p "$TMP/classes" "$TMP/src/pro/sitescope/app"
cp "$PROJ/android-direct/src/pro/sitescope/app/IpMath.java" "$TMP/src/pro/sitescope/app/"
cp "$PROJ/android-direct/src/pro/sitescope/app/IpDataStore.java" "$TMP/src/pro/sitescope/app/"
cat > "$TMP/src/pro/sitescope/app/IpIntelligenceHarness.java" <<'JAVA'
package pro.sitescope.app;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import java.util.Locale;
public final class IpIntelligenceHarness {
  private static void ok(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    System.out.println("PASS: " + name);
  }
  public static void main(String[] args) {
    IpMath.Range subnet = IpMath.cidrToRange("192.168.1.10/24");
    ok("192.168.1.0".equals(subnet.startIp()), "CIDR network");
    ok("192.168.1.255".equals(subnet.endIp()), "CIDR broadcast/end");
    List<String> cidrs = IpMath.rangeToCidrs("192.168.1.0", "192.168.1.255");
    ok(cidrs.size()==1 && "192.168.1.0/24".equals(cidrs.get(0)), "range to minimal CIDR");
    List<IpMath.Range> ranges = new ArrayList<IpMath.Range>();
    ranges.add(IpMath.parseRangeExpression("10.0.0.0/25"));
    ranges.add(IpMath.parseRangeExpression("10.0.0.128/25"));
    List<IpMath.Range> merged = IpMath.aggregate(ranges);
    List<String> mergedCidrs = IpMath.rangeToCidrs(merged.get(0).start, merged.get(0).end, 32);
    ok(merged.size()==1 && mergedCidrs.size()==1 && "10.0.0.0/24".equals(mergedCidrs.get(0)), "aggregate adjacent ranges");
    ok(IpMath.conversionReport("3232235777").contains("192.168.1.1"), "decimal to IPv4");
    ok("2001:0db8:0000:0000:0000:0000:0000:0001".equals(IpMath.expandIpv6("2001:db8::1")), "IPv6 expansion");
    List<String> csv = IpDataStore.parseCsvForTest("\"1.1.1.0\",\"1.1.1.255\",\"EU\",\"Europe\",\"DE\",\"Germany\",\"Berlin\",\"Berlin, Mitte\",52.52,13.4");
    ok(csv.size()==10 && "Berlin, Mitte".equals(csv.get(7)), "RFC4180 quoted CSV");
    ok("2026-08".equals(IpDataStore.sourceVersion("dbip-city-lite-2026-08.csv.gz")), "DB source version parsing");
    Calendar now = Calendar.getInstance();
    String current = String.format(Locale.US, "%04d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH)+1);
    ok(IpDataStore.versionCurrent(current), "current DB version freshness");
    System.out.println("IP_INTELLIGENCE_HARNESS_PASS");
  }
}
JAVA
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 \
  -cp "$ANDROID_HOME/platforms/android-36/android.jar" \
  -d "$TMP/classes" $(find "$TMP/src" -name '*.java' -print)
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$ANDROID_HOME/platforms/android-36/android.jar" pro.sitescope.app.IpIntelligenceHarness
rm -rf "$TMP"
