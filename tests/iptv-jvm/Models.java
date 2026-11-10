package pro.sitescope.app;
public final class Models {
 public static final class IptvChannel {
  public String id="",name="",group="",logoUrl="",sourceUrl="",playbackUrl="",sourceType="",status="",serviceName="",providerName="",videoCodec="",audioCodec="",container="",evidence="",error="";
  public long latencyMs,bitrateKbps; public boolean playable,mpegTs;
 }
 public static final class UdpxyEndpoint {
  public String host="",baseUrl="",statusUrl="",server="",version="",evidence=""; public int port; public long latencyMs; public boolean confirmed;
 }
}
