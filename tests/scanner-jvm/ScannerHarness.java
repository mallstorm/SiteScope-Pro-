package pro.sitescope.app;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public class ScannerHarness {
  public static void main(String[] args) throws Exception {
    check(ScannerEngine.parseTargets("192.168.1.0/30").equals(Arrays.asList("192.168.1.1","192.168.1.2")), "CIDR /30");
    check(ScannerEngine.parseTargets("192.168.1.0/255.255.255.252").size()==2, "dotted mask");
    check(ScannerEngine.parseTargets("192.168.1.10-12").equals(Arrays.asList("192.168.1.10","192.168.1.11","192.168.1.12")), "short range");
    check(ScannerEngine.parseTargets("192.168.1.*").size()==254, "wildcard");
    List<Integer> ports=ScannerEngine.parsePorts("22,80,440-443,1-3");
    check(ports.equals(Arrays.asList(1,2,3,22,80,440,441,442,443)), "port expressions");
    check(ScannerEngine.parsePorts("1-65535", ScannerEngine.EXTENDED_MAX_PORTS).size()==65535, "extended full port range");
    List<String> privateRange=ScannerEngine.parseTargets("192.168.0.0/16", ScannerEngine.EXTENDED_MAX_HOSTS);
    check(privateRange.size()==65534, "extended private /16");
    check(ScannerEngine.allTargetsPrivateOrLocal(privateRange.subList(0, Math.min(16, privateRange.size()))), "private target policy");
    check(!ScannerEngine.allTargetsPrivateOrLocal(Arrays.asList("192.0.2.1")), "public target policy");
    boolean standardRejected=false;
    try { ScannerEngine.parseTargets("192.168.0.0/16"); } catch (ScannerEngine.ScanException expected) { standardRejected=true; }
    check(standardRejected, "standard profile remains bounded");
    final CountDownLatch latch=new CountDownLatch(1);
    final List<Models.PortResult>[] rows=new List[]{null};
    ScannerEngine.Config config=new ScannerEngine.Config(); config.timeoutMs=650; config.concurrency=8; config.detectServices=true;
    ScannerEngine.Task stateTask=new ScannerEngine.Task(); stateTask.pause(); check(stateTask.isPaused(), "task pause state"); stateTask.resume(); check(!stateTask.isPaused(), "task resume state");
    final List<Integer> testPorts=Arrays.asList(22022,22025,23390,28080,28554,25060,21883,21211,25672,24022);
    ScannerEngine.start(Arrays.asList("127.0.0.1"), testPorts, config, new ScannerEngine.Listener(){
      public void onStarted(int h,int p,int t){}
      public void onProgress(int c,int t,int o,String x){}
      public void onOpen(Models.PortResult r){}
      public void onComplete(List<Models.PortResult> r,boolean c,long d,String e){rows[0]=r;latch.countDown();}
    });
    check(latch.await(25, TimeUnit.SECONDS), "scan completion");
    Map<Integer,String> services=new HashMap<Integer,String>();
    for(Models.PortResult r: rows[0]) services.put(r.port,r.service);
    check("SSH".equals(services.get(22022)), "SSH custom port: "+services);
    check("SMTP".equals(services.get(22025)), "SMTP custom port: "+services);
    check("RDP".equals(services.get(23390)), "RDP custom port: "+services);
    check("HTTP".equals(services.get(28080)), "HTTP custom port: "+services);
    check("RTSP".equals(services.get(28554)), "RTSP custom port: "+services);
    check("SIP".equals(services.get(25060)), "SIP custom port: "+services);
    check("MQTT".equals(services.get(21883)), "MQTT custom port: "+services);
    check("Memcached".equals(services.get(21211)), "Memcached custom port: "+services);
    check("AMQP".equals(services.get(25672)), "AMQP custom port: "+services);
    check("udpxy".equals(services.get(24022)), "udpxy custom port: "+services);
    System.out.println("SCANNER_HARNESS_PASS rows="+rows[0].size()+" services="+services);
  }
  private static void check(boolean ok,String name){if(!ok) throw new RuntimeException("FAIL: "+name); System.out.println("PASS: "+name);}
}
