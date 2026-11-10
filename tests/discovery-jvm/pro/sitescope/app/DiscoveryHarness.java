package pro.sitescope.app;
import java.lang.reflect.Method;
public final class DiscoveryHarness {
  public static void main(String[] args) throws Exception {
    String ssdp="HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:1900/root.xml\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nSERVER: Linux/5 UPnP/1.1 RouterOS\r\nUSN: uuid:router::urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
    check("http://192.168.1.1:1900/root.xml".equals(callString("header",new Class[]{String.class,String.class},ssdp,"location")),"SSDP LOCATION");
    check("Маршрутизатор / шлюз".equals(callString("classifySsdp",new Class[]{String.class},ssdp)),"SSDP router classification");
    String onvif="<d:ProbeMatches><d:ProbeMatch><d:Types>dn:NetworkVideoTransmitter</d:Types><d:Scopes>onvif://www.onvif.org/name/Front%20Door onvif://www.onvif.org/hardware/IPC-42</d:Scopes><d:XAddrs>http://192.168.1.50/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches>";
    check("http://192.168.1.50/onvif/device_service".equals(callString("xmlValue",new Class[]{String.class,String.class},onvif,"XAddrs")),"ONVIF XAddrs");
    String scopes=callString("xmlValue",new Class[]{String.class,String.class},onvif,"Scopes");
    check("Front Door".equals(callString("scopeValue",new Class[]{String.class,String.class},scopes,"name")),"ONVIF scope name");
    check("Сетевой принтер".equals(callString("classifyMdns",new Class[]{String.class},"_ipp._tcp.local")),"mDNS printer classification");
    Method query=NetworkDiscoveryEngine.class.getDeclaredMethod("dnsPtrQuery",String.class); query.setAccessible(true);
    byte[] bytes=(byte[])query.invoke(null,"_services._dns-sd._udp.local");
    check(bytes.length>30 && bytes[5]==1,"DNS-SD PTR query");
    System.out.println("DISCOVERY_HARNESS_PASS bytes="+bytes.length);
  }
  private static String callString(String name,Class[] types,Object... args)throws Exception{Method m=NetworkDiscoveryEngine.class.getDeclaredMethod(name,types);m.setAccessible(true);return (String)m.invoke(null,args);}
  private static void check(boolean ok,String name){if(!ok)throw new RuntimeException("FAIL: "+name);System.out.println("PASS: "+name);}
}
