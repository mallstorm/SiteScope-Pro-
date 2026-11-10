package pro.sitescope.app;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal IPv4/UDP/DNS packet codec used by the no-root local VPN mode. */
public final class HostsVpnPacket {
    public static final class QueryPacket {
        public byte[] source = new byte[4];
        public byte[] destination = new byte[4];
        public int sourcePort;
        public int destinationPort;
        public int ipId;
        public byte[] dns;
        public String qname;
        public int qtype;
        public int questionEnd;
    }

    private HostsVpnPacket() { }

    public static QueryPacket parse(byte[] packet, int length) {
        if (packet == null || length < 28 || (packet[0] >>> 4) != 4) return null;
        int ihl = (packet[0] & 0x0f) * 4; if (ihl < 20 || length < ihl + 8 || (packet[9] & 0xff) != 17) return null;
        int total = u16(packet, 2); if (total <= 0 || total > length) total = length;
        int udp = ihl; int srcPort = u16(packet, udp); int dstPort = u16(packet, udp + 2); if (dstPort != 53) return null;
        int udpLen = u16(packet, udp + 4); if (udpLen < 8 || udp + udpLen > total) return null;
        byte[] dns = Arrays.copyOfRange(packet, udp + 8, udp + udpLen); if (dns.length < 17 || u16(dns, 4) != 1) return null;
        int[] offset = new int[]{12}; String qname = readName(dns, offset); if (qname == null || offset[0] + 4 > dns.length) return null;
        QueryPacket q = new QueryPacket(); q.source=Arrays.copyOfRange(packet,12,16);q.destination=Arrays.copyOfRange(packet,16,20);q.sourcePort=srcPort;q.destinationPort=dstPort;q.ipId=u16(packet,4);q.dns=dns;q.qname=qname.toLowerCase(Locale.US);q.qtype=u16(dns,offset[0]);q.questionEnd=offset[0]+4;return q;
    }

    public static byte[] localDnsResponse(QueryPacket query, Map<String,List<InetAddress>> overrides) throws Exception {
        List<InetAddress> values = overrides.get(query.qname); if (values == null) return null;
        List<InetAddress> answers = new ArrayList<InetAddress>();
        for (InetAddress address : values) {
            if (query.qtype == 1 && address instanceof Inet4Address) answers.add(address);
            if (query.qtype == 28 && address instanceof Inet6Address) answers.add(address);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(query.dns,0,2); // transaction id
        int requestFlags=u16(query.dns,2); int flags=0x8080 | (requestFlags & 0x0100); put16(out,flags);put16(out,1);put16(out,answers.size());put16(out,0);put16(out,0);
        out.write(query.dns,12,query.questionEnd-12);
        for(InetAddress address:answers){out.write(0xc0);out.write(0x0c);put16(out,query.qtype);put16(out,1);put32(out,60);byte[] raw=address.getAddress();put16(out,raw.length);out.write(raw);}
        return out.toByteArray();
    }

    public static byte[] responsePacket(QueryPacket query, byte[] dnsResponse) {
        int total=20+8+dnsResponse.length;byte[] out=new byte[total];out[0]=0x45;out[1]=0;put16(out,2,total);put16(out,4,query.ipId);put16(out,6,0);out[8]=64;out[9]=17;
        System.arraycopy(query.destination,0,out,12,4);System.arraycopy(query.source,0,out,16,4);put16(out,10,checksum(out,0,20));
        int udp=20;put16(out,udp,query.destinationPort);put16(out,udp+2,query.sourcePort);put16(out,udp+4,8+dnsResponse.length);put16(out,udp+6,0);System.arraycopy(dnsResponse,0,out,udp+8,dnsResponse.length);return out;
    }

    private static String readName(byte[] dns,int[] offset){StringBuilder out=new StringBuilder();int p=offset[0];int guards=0;while(p<dns.length&&guards++<128){int len=dns[p++]&0xff;if(len==0){offset[0]=p;return out.toString();}if((len&0xc0)!=0||len>63||p+len>dns.length)return null;if(out.length()>0)out.append('.');for(int i=0;i<len;i++){int ch=dns[p++]&0xff;if(ch<33||ch>126)return null;out.append((char)ch);}}return null;}
    private static int checksum(byte[] data,int offset,int length){long sum=0;int i=offset;while(length>1){sum+=(data[i]&0xff)<<8|(data[i+1]&0xff);i+=2;length-=2;}if(length>0)sum+=(data[i]&0xff)<<8;while((sum>>16)!=0)sum=(sum&0xffff)+(sum>>16);return(int)(~sum)&0xffff;}
    private static int u16(byte[] b,int p){return((b[p]&0xff)<<8)|(b[p+1]&0xff);}
    private static void put16(byte[] b,int p,int v){b[p]=(byte)(v>>>8);b[p+1]=(byte)v;}
    private static void put16(ByteArrayOutputStream out,int v){out.write((v>>>8)&0xff);out.write(v&0xff);}
    private static void put32(ByteArrayOutputStream out,long v){out.write((int)(v>>>24)&0xff);out.write((int)(v>>>16)&0xff);out.write((int)(v>>>8)&0xff);out.write((int)v&0xff);}
}
