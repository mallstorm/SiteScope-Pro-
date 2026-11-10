package android.net.wifi;
public class WifiManager {
  public MulticastLock createMulticastLock(String tag){return new MulticastLock();}
  public static class MulticastLock {
    public void setReferenceCounted(boolean value){}
    public void acquire(){}
    public void release(){}
    public boolean isHeld(){return false;}
  }
}
