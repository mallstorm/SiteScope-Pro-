#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"
TMP="$(mktemp -d /tmp/sitescope-mdns-test-XXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/pro/sitescope/app" "$TMP/classes"
cp "$PROJ/android-direct/src/pro/sitescope/app/MdnsPacketParser.java" "$TMP/src/pro/sitescope/app/"
cp "$PROJ/android-direct/src/pro/sitescope/app/MdnsServiceMapper.java" "$TMP/src/pro/sitescope/app/"
cat > "$TMP/src/pro/sitescope/app/MdnsHarness.java" <<'JAVA'
package pro.sitescope.app;
import java.io.ByteArrayOutputStream;
import java.util.*;
public final class MdnsHarness {
  public static void main(String[] args) throws Exception {
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    write16(out,0); write16(out,0x8400); write16(out,0); write16(out,4); write16(out,0); write16(out,0);
    rrName(out,"_http._tcp.local",12,nameBytes("Router._http._tcp.local"));
    ByteArrayOutputStream srv=new ByteArrayOutputStream(); write16(srv,0); write16(srv,0); write16(srv,8080); srv.write(nameBytes("router.local"));
    rrName(out,"Router._http._tcp.local",33,srv.toByteArray());
    ByteArrayOutputStream txt=new ByteArrayOutputStream(); byte[] tv="path=/".getBytes("UTF-8"); txt.write(tv.length); txt.write(tv);
    rrName(out,"Router._http._tcp.local",16,txt.toByteArray());
    rrName(out,"router.local",1,new byte[]{(byte)192,(byte)168,1,1});
    byte[] packet=out.toByteArray();
    MdnsPacketParser.Result r=MdnsPacketParser.parse(packet,0,packet.length);
    check(r.srvByInstance.size()==1,"SRV parsed");
    MdnsPacketParser.Record s=r.srvByInstance.values().iterator().next();
    check(s.port==8080 && "router.local".equals(s.target),"SRV endpoint");
    check("192.168.1.1".equals(r.addressFor("router.local")),"A record");
    check(r.txtFor("Router._http._tcp.local").contains("path=/"),"TXT record");
    check("_http._tcp.local".equals(MdnsPacketParser.serviceTypeFromInstance("Router._http._tcp.local")),"service type");
    check(MdnsServiceMapper.protocols("_ssh._tcp.local _https._tcp.local").containsAll(Arrays.asList("SSH","HTTPS")),"protocol mapping");
    System.out.println("MDNS_PARSER_HARNESS_PASS");
  }
  static void rrName(ByteArrayOutputStream out,String name,int type,byte[] data)throws Exception{out.write(nameBytes(name));write16(out,type);write16(out,1);write32(out,120);write16(out,data.length);out.write(data);}
  static byte[] nameBytes(String n)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(String p:n.split("\\.")){byte[] b=p.getBytes("UTF-8");out.write(b.length);out.write(b);}out.write(0);return out.toByteArray();}
  static void write16(ByteArrayOutputStream o,int v){o.write((v>>>8)&255);o.write(v&255);} static void write32(ByteArrayOutputStream o,long v){o.write((int)(v>>>24)&255);o.write((int)(v>>>16)&255);o.write((int)(v>>>8)&255);o.write((int)v&255);}
  static void check(boolean ok,String n){if(!ok)throw new RuntimeException("FAIL "+n);System.out.println("PASS "+n);}
}
JAVA
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" $(find "$TMP/src" -name '*.java')
"$JAVA_HOME/bin/java" -cp "$TMP/classes" pro.sitescope.app.MdnsHarness
