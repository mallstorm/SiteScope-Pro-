package pro.sitescope.app;
public final class Models {
  public static final class DiscoveredDevice {
    public String ip=""; public int port; public String protocol=""; public String deviceType="Сетевое устройство";
    public String name=""; public String hostname=""; public String manufacturer=""; public String model=""; public String location="";
    public String services=""; public String evidence=""; public String confidence="medium"; public long latencyMs;
    public String key(){return ip+"|"+protocol+"|"+(location.length()>0?location:name);}
  }
}
