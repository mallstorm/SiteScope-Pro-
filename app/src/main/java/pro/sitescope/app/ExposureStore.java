package pro.sitescope.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.net.*;
import java.util.*;
import org.json.*;

/** Owner-configured endpoints and bounded measurements; no credentials or URL paths. */
public final class ExposureStore {
    private ExposureStore() { }
    public static final class Target {
        public String id="",name="",host="",ports="80,443";
        public JSONObject toJson(){JSONObject o=new JSONObject();try{o.put("id",id);o.put("name",name);o.put("host",host);o.put("ports",ports);}catch(Exception ignored){}return o;}
        public static Target fromJson(JSONObject o)throws Exception{Target t=new Target();t.id=o.getString("id");t.name=o.optString("name");if(t.name.length()>256)throw new IllegalArgumentException("Слишком длинное название");t.host=host(o.getString("host"));t.ports=o.getString("ports");ScannerEngine.parsePorts(t.ports,64);if(t.id.isEmpty())throw new IllegalArgumentException("Нет ID цели");return t;}
    }
    public static String host(String raw)throws Exception {
        String s=raw.trim();if(s.startsWith("[")&&s.endsWith("]"))s=s.substring(1,s.length()-1);
        if(s.isEmpty()||s.length()>253||s.matches(".*[\\s/@?#].*")||s.contains("*"))throw new IllegalArgumentException("Укажите один IP или домен без URL, диапазона и данных входа");
        List<String> parsed=ScannerEngine.parseTargets(s,1);if(parsed.size()!=1)throw new IllegalArgumentException("Нужна одна собственная цель");return parsed.get(0);
    }
    public static boolean publicAddress(String raw) {
        if(!numeric(raw))return false;
        try {
            InetAddress a=InetAddress.getByName(raw);if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;
            byte[] b=a.getAddress();int x=b[0]&255,y=b[1]&255,z=b[2]&255;
            if(b.length==4)return x!=0&&x!=10&&x!=127&&x<224&&!(x==100&&y>=64&&y<=127)&&!(x==169&&y==254)&&!(x==172&&y>=16&&y<=31)&&!(x==192&&(y==168||y==0&&(z==0||z==2)||y==88&&z==99))&&!(x==198&&(y==18||y==19||y==51&&z==100))&&!(x==203&&y==0&&(b[2]&255)==113);
            return (x&224)==32&&!(x==63&&y==255&&(z&240)==0)&&!(x==32&&y==1&&(b[2]&255)==13&&(b[3]&255)==184)&&!(x==32&&y==1&&(b[2]&255)==0&&(b[3]&255)==2);
        }catch(Exception e){return false;}
    }
    static boolean numeric(String raw) {String[] parts=raw.split("%",-1);return parts.length<=2&&(parts.length==1||parts[1].matches("[a-zA-Z0-9_.-]+"))&&(parts[0].contains(":")?parts[0].matches("[0-9a-fA-F:]+")&&parts[0].length()>1:parts.length==1&&parts[0].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"));}
    static void createTables(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS exposure_targets(id TEXT PRIMARY KEY,json TEXT NOT NULL)");db.execSQL("CREATE TABLE IF NOT EXISTS exposure_runs(id TEXT PRIMARY KEY,time INTEGER NOT NULL,json TEXT NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS exposure_runs_time ON exposure_runs(time DESC)");}
    public static List<Target> targets(Context context){List<Target> out=new ArrayList<>();Cursor c=AppDatabase.get(context).read().query("exposure_targets",new String[]{"json"},null,null,null,null,"id");try{while(c.moveToNext())out.add(Target.fromJson(new JSONObject(c.getString(0))));}catch(Exception e){throw new IllegalStateException(e);}finally{c.close();}return out;}
    public static void save(Context context,Target t)throws Exception{if(t.id.isEmpty())t.id=UUID.randomUUID().toString();Target checked=Target.fromJson(t.toJson());ContentValues v=new ContentValues();v.put("id",checked.id);v.put("json",checked.toJson().toString());AppDatabase.get(context).write().insertWithOnConflict("exposure_targets",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public static void remove(Context context,String id){AppDatabase.get(context).write().delete("exposure_targets","id=?",new String[]{id});}
    public static List<JSONObject> runs(Context context){List<JSONObject> out=new ArrayList<>();Cursor c=AppDatabase.get(context).read().query("exposure_runs",new String[]{"json"},null,null,null,null,"time DESC","100");try{while(c.moveToNext())out.add(new JSONObject(c.getString(0)));}catch(Exception e){throw new IllegalStateException(e);}finally{c.close();}return out;}
    public static void saveRun(Context context,JSONObject run)throws Exception{validateRun(run);SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();try{ContentValues v=new ContentValues();v.put("id",run.getString("id"));v.put("time",run.getLong("time"));v.put("json",run.toString());db.insertWithOnConflict("exposure_runs",null,v,SQLiteDatabase.CONFLICT_REPLACE);db.execSQL("DELETE FROM exposure_runs WHERE id NOT IN(SELECT id FROM exposure_runs ORDER BY time DESC LIMIT 100)");db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public static void clearRuns(Context context){AppDatabase.get(context).write().delete("exposure_runs",null,null);}
    private static void validateRun(JSONObject run)throws Exception{if(run.getString("id").isEmpty()||run.getLong("time")<0)throw new IllegalArgumentException("Неверное измерение");host(run.getString("host"));ScannerEngine.parsePorts(run.getString("ports"),64);run.getString("source");boolean external=run.getBoolean("externalPoint");String transport=run.getString("transport");if(!Arrays.asList("cellular","wifi","ethernet","vpn","other","none").contains(transport)||external&&(!transport.equals("cellular")||run.optBoolean("cancelled")||!run.optString("error").isEmpty()))throw new IllegalArgumentException("Неверная точка измерения");JSONArray rows=run.getJSONArray("results");if(rows.length()>4096)throw new IllegalArgumentException("Слишком большое измерение");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String ip=r.getString("address");if(!numeric(ip))throw new IllegalArgumentException("Неверный адрес измерения");if(r.getInt("port")<1||r.getInt("port")>65535||!Arrays.asList("open","refused","timeout","network_error","unresolved").contains(r.getString("state")))throw new IllegalArgumentException("Неверный результат");}}
    static JSONObject exportState(SQLiteDatabase db)throws Exception{JSONObject root=new JSONObject();for(String table:Arrays.asList("exposure_targets","exposure_runs")){JSONArray rows=new JSONArray();Cursor c=db.query(table,new String[]{"json"},null,null,null,null,null);try{while(c.moveToNext())rows.put(new JSONObject(c.getString(0)));}finally{c.close();}root.put(table,rows);}return root;}
    static void validate(JSONObject root)throws Exception{if(root==null)return;for(String table:Arrays.asList("exposure_targets","exposure_runs")){Set<String> ids=new HashSet<>();JSONArray rows=root.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject o=rows.getJSONObject(i);if(table.endsWith("targets"))Target.fromJson(o);else validateRun(o);if(!ids.add(o.getString("id")))throw new IllegalArgumentException("Повтор ID измерений");}}}
    static void restore(SQLiteDatabase db,JSONObject root)throws Exception{validate(root);db.delete("exposure_targets",null,null);db.delete("exposure_runs",null,null);if(root==null)return;for(String table:Arrays.asList("exposure_targets","exposure_runs")){JSONArray rows=root.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject o=rows.getJSONObject(i);ContentValues v=new ContentValues();v.put("id",o.getString("id"));v.put("json",o.toString());if(table.endsWith("runs"))v.put("time",o.getLong("time"));db.insertOrThrow(table,null,v);}}db.execSQL("DELETE FROM exposure_runs WHERE id NOT IN(SELECT id FROM exposure_runs ORDER BY time DESC LIMIT 100)");}
}
