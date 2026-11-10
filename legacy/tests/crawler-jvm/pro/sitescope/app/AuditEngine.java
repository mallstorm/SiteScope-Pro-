package pro.sitescope.app;
public final class AuditEngine {
  public static String normalizeTarget(String raw) {
    String value=raw==null?"":raw.trim();
    if(value.length()==0) return "";
    if(!value.matches("(?i)^https?://.*")) value="http://"+value;
    return value;
  }
}
