package pro.sitescope.app;
import java.util.*;
public class IptvHarness {
 public static void main(String[] args) throws Exception {
  String m3u="#EXTM3U\n#EXTINF:-1 tvg-id=\"news\" tvg-name=\"News\" tvg-logo=\"http://logo\" group-title=\"TV\",News HD\nudp://@239.1.1.1:1234\n";
  List<Models.IptvChannel> list=IptvEngine.parseM3u(m3u);
  check(list.size()==1,"M3U count");
  check("News HD".equals(list.get(0).name),"M3U name");
  check("TV".equals(list.get(0).group),"M3U group");
  String url=IptvEngine.buildPlaybackUrl("http://192.168.1.1:4022/",list.get(0).sourceUrl);
  check("http://192.168.1.1:4022/udp/239.1.1.1:1234".equals(url),"udpxy URL "+url);
  Models.UdpxyEndpoint ep=IptvEngine.probeUdpxy("127.0.0.1",28999,1000);
  check(ep!=null && ep.confirmed,"udpxy probe");
  IptvEngine.ProbeResult probe=IptvEngine.probeChannel("http://127.0.0.1:28999/stream",1500);
  check(probe.playable && probe.mpegTs,"MPEG-TS probe error="+probe.error);
  check("News HD".equals(probe.serviceName),"DVB-SDT name="+probe.serviceName);
  check("H.264/AVC".equals(probe.videoCodec),"PMT video="+probe.videoCodec);
  check("AAC".equals(probe.audioCodec),"PMT audio="+probe.audioCodec);
  System.out.println("IPTV_HARNESS_PASS service="+probe.serviceName+" video="+probe.videoCodec+" audio="+probe.audioCodec);
 }
 static void check(boolean ok,String msg){if(!ok)throw new RuntimeException("FAIL "+msg);System.out.println("PASS "+msg);}
}
