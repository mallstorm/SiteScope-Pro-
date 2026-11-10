package pro.sitescope.app;
public final class Models {
  public static final class PortResult {
    public String target="", host="", ip="", service="", resourceType="", hostname="", banner="", tlsSubject="", evidence="", confidence="", description="";
    public int port; public boolean open; public long latencyMs;
  }
}
