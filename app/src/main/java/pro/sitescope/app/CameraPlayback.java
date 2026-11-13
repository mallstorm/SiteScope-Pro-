package pro.sitescope.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.Network;
import android.os.SystemClock;
import java.util.*;

/** Single-use playback handoff. Credentials never enter Intent extras or connection journals. */
public final class CameraPlayback {
    private CameraPlayback() { }
    public static final String EXTRA="camera_playback_token";
    private static final Map<String,Entry> pending=new LinkedHashMap<>();
    private static final class Entry { final String uri,user,password;final Network network;final long expires;Entry(String u,String n,String p,Network network){this.network=network;uri=u;user=n;password=p;expires=SystemClock.elapsedRealtime()+120000;} }
    public static final class Session { public final String uri;public final Network network;Session(String uri,Network network){this.uri=uri;this.network=network;} }
    public static String prepare(String base,String uri,String user,String password)throws CameraClient.Failure {return prepare(base,uri,user,password,null);}
    public static synchronized String prepare(String base,String uri,String user,String password,Network network)throws CameraClient.Failure {
        String clean=CameraClient.related(base,uri,true);
        pending.entrySet().removeIf(e->e.getValue().expires<SystemClock.elapsedRealtime());
        while(pending.size()>=8)pending.remove(pending.keySet().iterator().next());
        String token=UUID.randomUUID().toString();pending.put(token,new Entry(clean,user,password,network));return token;
    }
    public static String consume(String token) {Session s=consumeSession(token);return s==null?null:s.uri;}
    public static synchronized Session consumeSession(String token) {
        Entry e=pending.remove(token);if(e==null||e.expires<SystemClock.elapsedRealtime())return null;
        if(e.user.isEmpty())return new Session(e.uri,e.network);
        return new Session("rtsp://"+Uri.encode(e.user)+":"+Uri.encode(e.password)+"@"+e.uri.substring("rtsp://".length()),e.network);
    }
    public static synchronized void discard(String token){pending.remove(token);}
    public static void open(Activity activity,String base,String uri,String user,String password,String name)throws CameraClient.Failure {open(activity,base,uri,user,password,name,null);}
    public static void open(Activity activity,String base,String uri,String user,String password,String name,Network network)throws CameraClient.Failure {
        String token=prepare(base,uri,user,password,network);
        Intent intent=new Intent().setClassName(activity,"pro.sitescope.app.tv.TvPlayerActivity");intent.putExtra(EXTRA,token);intent.putExtra("name",name);
        try{activity.startActivity(intent);}catch(RuntimeException e){discard(token);throw e;}
    }
}
