package pro.sitescope.app;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.*;

/** Owner-supplied HTTP Digest credentials; a single same-address challenge retry. */
final class CameraDigest {
    private CameraDigest() { }
    static String authorization(String challenge,URI url,String user,String password)throws CameraClient.Failure {
        try {
            if(!challenge.startsWith("Digest ")||challenge.indexOf('\r')>=0||challenge.indexOf('\n')>=0)throw new Exception();
            Map<String,String> values=new HashMap<>();
            Matcher matcher=Pattern.compile("([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^,\\s]+))").matcher(challenge.substring(7));
            int end=0;while(matcher.find()){
                if(!challenge.substring(7+end,7+matcher.start()).trim().replace(",","").isEmpty())throw new Exception();
                String key=matcher.group(1).toLowerCase(Locale.ROOT),v=matcher.group(2)==null?matcher.group(3):matcher.group(2).replaceAll("\\\\(.)","$1");
                if(values.put(key,v)!=null)throw new Exception();end=matcher.end();
            }
            if(!challenge.substring(7+end).trim().isEmpty())throw new Exception();
            String realm=values.get("realm"),nonce=values.get("nonce"),algorithm=values.getOrDefault("algorithm","MD5"),qop=values.get("qop");
            if(realm==null||nonce==null||nonce.isEmpty()||!Arrays.asList("MD5","MD5-sess","SHA-256","SHA-256-sess").contains(algorithm))throw new Exception();
            if(qop!=null&&!Arrays.asList(qop.toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("auth"))throw new Exception();
            Charset charset="UTF-8".equalsIgnoreCase(values.get("charset"))?StandardCharsets.UTF_8:StandardCharsets.ISO_8859_1;
            String hash=algorithm.startsWith("SHA-256")?"SHA-256":"MD5",uri=url.getRawPath();if(uri==null||uri.isEmpty())uri="/";if(url.getRawQuery()!=null)uri+="?"+url.getRawQuery();
            byte[] random=new byte[16];new SecureRandom().nextBytes(random);String cnonce=hex(random),nc="00000001";
            String a1=h(hash,user+":"+realm+":"+password,charset);if(algorithm.endsWith("-sess"))a1=h(hash,a1+":"+nonce+":"+cnonce,charset);
            String a2=h(hash,"POST:"+uri,charset),response=h(hash,a1+":"+nonce+":"+(qop==null?"":nc+":"+cnonce+":auth:")+a2,charset);
            String result="Digest username="+quote(user)+", realm="+quote(realm)+", nonce="+quote(nonce)+", uri="+quote(uri)+", response="+quote(response)+", algorithm="+algorithm;
            if(qop!=null)result+=", qop=auth, nc="+nc+", cnonce="+quote(cnonce);else if(algorithm.endsWith("-sess"))result+=", cnonce="+quote(cnonce);
            if(values.containsKey("opaque"))result+=", opaque="+quote(values.get("opaque"));return result;
        }catch(Exception invalid){throw new CameraClient.Failure(CameraClient.Code.AUTH);}
    }
    private static String quote(String value)throws Exception {if(value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new Exception();return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String h(String algorithm,String value,Charset charset)throws Exception{return hex(MessageDigest.getInstance(algorithm).digest(value.getBytes(charset)));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
