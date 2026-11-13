package pro.sitescope.app;

import org.json.JSONObject;

/** Output-only redaction. Live configuration and original observations are not changed. */
public final class ReportText {
    private ReportText() { }
    public static String clean(String value) {
        if(value==null)return "";
        try {value=BackupSanitizer.clean(new JSONObject().put("value",value)).optString("value");}catch(Exception ignored){return "[REDACTED]";}
        return value.replaceAll("(?i)\\b(password|passwd|pwd|token|access_token|refresh_token|api_?key|secret|authorization|cookie)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)", "$1=[REDACTED]")
            .replaceAll("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]");
    }
    static String known(String value,String... secrets) {if(value==null)return "";for(String secret:secrets)if(secret!=null&&!secret.isEmpty())value=value.replace(secret,"[REDACTED]");return clean(value);}
    static String html(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    static String csv(String s){String trimmed=s.replaceFirst("^[\\s\\p{Cntrl}]+","");if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
}
