import pro.sitescope.app.HostsEntry;
import pro.sitescope.app.HostsParser;
import pro.sitescope.app.HostsVpnPacket;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.*;

public final class HostsHarness {
 public static void main(String[] args) throws Exception {
  HostsParser.Result normalized=HostsParser.parse("128.00.03.22 sige.ru www.sige.ru # test", "p");
  require(!normalized.hasErrors(),"normalization should be accepted"); require(normalized.entries.size()==1,"one entry");
  require("128.0.3.22".equals(normalized.entries.get(0).address),"IPv4 canonicalization"); require(!normalized.issues.isEmpty(),"normalization warning");
  HostsParser.Result conflict=HostsParser.parse("1.1.1.1 same.test\n2.2.2.2 same.test", "p"); require(!conflict.hasErrors()&&!conflict.issues.isEmpty(),"multiple addresses should warn but remain valid");
  List<HostsEntry> entries=normalized.entries;
  String merged=HostsParser.mergeManagedBlock("127.0.0.1 localhost\n# vendor\n",entries);
  require(merged.contains(HostsParser.BEGIN)&&merged.contains("128.0.3.22 sige.ru www.sige.ru"),"managed block merge");
  String replaced=HostsParser.mergeManagedBlock(merged,Collections.<HostsEntry>emptyList());
  require(count(replaced,HostsParser.BEGIN)==1,"managed block replaced, not duplicated");

  byte[] dns=query("sige.ru",1); byte[] packet=ipv4Udp(dns);
  HostsVpnPacket.QueryPacket parsed=HostsVpnPacket.parse(packet,packet.length); require(parsed!=null,"VPN packet parse"); require("sige.ru".equals(parsed.qname),"qname");
  Map<String,List<InetAddress>> map=new LinkedHashMap<String,List<InetAddress>>(); map.put("sige.ru",Arrays.asList(InetAddress.getByName("128.0.3.22")));
  byte[] response=HostsVpnPacket.localDnsResponse(parsed,map); require(response!=null&&u16(response,6)==1,"one local DNS answer");
  byte[] wrapped=HostsVpnPacket.responsePacket(parsed,response); require((wrapped[0]>>>4)==4&&u16(wrapped,22)==parsed.sourcePort,"response UDP ports swapped");
  System.out.println("Hosts Manager harness PASS");
 }
 static byte[] query(String host,int type)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();put16(o,0x1234);put16(o,0x0100);put16(o,1);put16(o,0);put16(o,0);put16(o,0);for(String label:host.split("\\.")){byte[] b=label.getBytes("US-ASCII");o.write(b.length);o.write(b);}o.write(0);put16(o,type);put16(o,1);return o.toByteArray();}
 static byte[] ipv4Udp(byte[] dns){int len=28+dns.length;byte[] p=new byte[len];p[0]=0x45;put16(p,2,len);put16(p,4,7);p[8]=64;p[9]=17;p[12]=10;p[13]=(byte)203;p[14]=0;p[15]=2;p[16]=10;p[17]=(byte)203;p[18]=0;p[19]=1;put16(p,20,53000);put16(p,22,53);put16(p,24,8+dns.length);System.arraycopy(dns,0,p,28,dns.length);return p;}
 static void put16(ByteArrayOutputStream o,int v){o.write(v>>>8);o.write(v);}
 static void put16(byte[] b,int p,int v){b[p]=(byte)(v>>>8);b[p+1]=(byte)v;}
 static int u16(byte[] b,int p){return((b[p]&255)<<8)|(b[p+1]&255);}
 static int count(String s,String needle){int n=0,p=0;while((p=s.indexOf(needle,p))>=0){n++;p+=needle.length();}return n;}
 static void require(boolean ok,String m){if(!ok)throw new AssertionError(m);}
}
