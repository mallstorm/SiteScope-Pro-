package pro.sitescope.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.net.*;
import java.util.*;
import org.json.*;

/** API connection metadata and curated snapshots; secrets belong only to SecureStore. */
public final class RouterStore {
    private RouterStore() { }
    public static final class Config {
        public String id="",name="",kind="routeros",base="";
        public boolean allowHttp;
        public JSONObject toJson(){JSONObject o=new JSONObject();try{o.put("id",id);o.put("name",name);o.put("kind",kind);o.put("base",base);o.put("allowHttp",allowHttp);}catch(Exception ignored){}return o;}
        public static Config fromJson(JSONObject o)throws Exception{Config c=new Config();c.id=o.getString("id");c.name=o.optString("name");c.kind=o.getString("kind");c.base=o.getString("base");c.allowHttp=o.optBoolean("allowHttp");if(c.id.isEmpty()||c.id.length()>256||c.name.length()>256||!Arrays.asList("routeros","openwrt").contains(c.kind))throw new IllegalArgumentException("Неверное подключение роутера");c.base=address(c.base,c.allowHttp).toASCIIString();return c;}
    }
    public static URI address(String raw,boolean allowHttp)throws Exception {
        URI u=new URI(raw.trim());String scheme=u.getScheme();
        if(!Arrays.asList("https","http").contains(scheme)||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getPort()==0||u.getPort()>65535||u.getRawPath()!=null&&!u.getRawPath().isEmpty()&&!u.getRawPath().equals("/"))throw new IllegalArgumentException("Укажите https://адрес:порт без пути и данных входа");
        if(scheme.equals("http")) {
            String host=u.getHost().replace("[","").replace("]","");if(!allowHttp||!ExposureStore.numeric(host))throw new IllegalArgumentException("HTTP разрешается отдельно только для числового локального адреса");
            InetAddress a=InetAddress.getByName(host);byte[] b=a.getAddress();boolean local=a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||b.length==16&&(b[0]&254)==252;
            if(!local||a.isAnyLocalAddress()||a.isMulticastAddress())throw new IllegalArgumentException("HTTP допускается только в доверенной локальной сети");
        }
        return new URI(scheme+"://"+u.getRawAuthority());
    }
    static void createTables(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS router_connections(id TEXT PRIMARY KEY,json TEXT NOT NULL)");db.execSQL("CREATE TABLE IF NOT EXISTS router_snapshots(id TEXT PRIMARY KEY,time INTEGER NOT NULL,json TEXT NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS router_snapshots_time ON router_snapshots(time DESC)");}
    public static List<Config> configs(Context context){List<Config> out=new ArrayList<>();Cursor c=AppDatabase.get(context).read().query("router_connections",new String[]{"json"},null,null,null,null,"id");try{while(c.moveToNext())out.add(Config.fromJson(new JSONObject(c.getString(0))));}catch(Exception e){throw new IllegalStateException("Неверные настройки роутера",e);}finally{c.close();}return out;}
    public static void save(Context context,Config config)throws Exception{if(config.id.isEmpty())config.id=UUID.randomUUID().toString();Config c=Config.fromJson(config.toJson());ContentValues v=new ContentValues();v.put("id",c.id);v.put("json",c.toJson().toString());AppDatabase.get(context).write().insertWithOnConflict("router_connections",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public static void saveCredentials(Context context,String id,String user,String password,boolean keep)throws Exception{SecureStore store=new SecureStore(context);String key="router_credentials_"+id;if(keep){Config config=null;for(Config candidate:configs(context))if(candidate.id.equals(id))config=candidate;if(config==null)throw new IllegalArgumentException("Сначала сохраните подключение");store.put(key,new JSONObject().put("user",user).put("password",password).put("base",config.base).put("kind",config.kind).toString());}else store.clear(key);}
    public static String[] credentials(Context context,String id){try{String raw=new SecureStore(context).get("router_credentials_"+id);JSONObject o=new JSONObject(raw);Config config=null;for(Config candidate:configs(context))if(candidate.id.equals(id))config=candidate;if(config==null||!config.base.equals(o.optString("base"))||!config.kind.equals(o.optString("kind")))return new String[]{"",""};return new String[]{o.optString("user"),o.optString("password")};}catch(Exception e){return new String[]{"",""};}}
    public static void remove(Context context,String id){AppDatabase.get(context).write().delete("router_connections","id=?",new String[]{id});new SecureStore(context).clear("router_credentials_"+id);}
    public static List<JSONObject> snapshots(Context context){List<JSONObject> out=new ArrayList<>();Cursor c=AppDatabase.get(context).read().query("router_snapshots",new String[]{"json"},null,null,null,null,"time DESC","20");try{while(c.moveToNext())out.add(new JSONObject(c.getString(0)));}catch(Exception e){throw new IllegalStateException(e);}finally{c.close();}return out;}
    public static void snapshot(Context context,JSONObject snapshot)throws Exception{validateSnapshot(snapshot);SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();try{ContentValues v=new ContentValues();v.put("id",snapshot.getString("id"));v.put("time",snapshot.getLong("time"));v.put("json",snapshot.toString());db.insertOrThrow("router_snapshots",null,v);db.execSQL("DELETE FROM router_snapshots WHERE id NOT IN(SELECT id FROM router_snapshots ORDER BY time DESC LIMIT 20)");db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public static void clearSnapshots(Context context){AppDatabase.get(context).write().delete("router_snapshots",null,null);}
    static final Set<String> ROW_FIELDS=new HashSet<>(Arrays.asList("kind","name","address","mac","model","manufacturer","software","uptime","interface_type","status"));
    static void validateSnapshot(JSONObject s)throws Exception{if(s.getString("id").isEmpty()||s.getLong("time")<0||!Arrays.asList("routeros","openwrt").contains(s.getString("adapter")))throw new IllegalArgumentException("Неверный снимок роутера");s.getString("source");
        Set<String> allowed=new HashSet<>(Arrays.asList("id","time","adapter","source","name","host","partial","warnings","rows"));
        Iterator<String> rootKeys=s.keys();while(rootKeys.hasNext())if(!allowed.contains(rootKeys.next()))throw new IllegalArgumentException("Недопустимое поле снимка");
        for(String key:Arrays.asList("source","name","host"))if(s.has(key)&&(!(s.get(key) instanceof String)||s.getString(key).length()>1024))throw new IllegalArgumentException("Неверное поле снимка");
        JSONArray warnings=s.optJSONArray("warnings");if(s.has("warnings")&&warnings==null)throw new IllegalArgumentException("Неверные пояснения");if(warnings!=null){if(warnings.length()>16)throw new IllegalArgumentException("Слишком много пояснений");for(int i=0;i<warnings.length();i++)if(!(warnings.get(i) instanceof String)||warnings.getString(i).length()>1024)throw new IllegalArgumentException("Неверное пояснение");}
        JSONArray rows=s.getJSONArray("rows");if(s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576)throw new IllegalArgumentException("Слишком большой снимок");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);Iterator<String> keys=r.keys();while(keys.hasNext()){String key=keys.next();if(!ROW_FIELDS.contains(key)||!(r.get(key) instanceof String)||r.getString(key).length()>1024)throw new IllegalArgumentException("Недопустимое поле снимка");}if(!Arrays.asList("system","interface","lease").contains(r.getString("kind")))throw new IllegalArgumentException("Неизвестный тип сведений");}}
    static JSONObject exportState(SQLiteDatabase db)throws Exception{JSONObject root=new JSONObject();for(String table:Arrays.asList("router_connections","router_snapshots")){JSONArray rows=new JSONArray();Cursor c=db.query(table,new String[]{"json"},null,null,null,null,null);try{while(c.moveToNext())rows.put(new JSONObject(c.getString(0)));}finally{c.close();}root.put(table,rows);}return root;}
    static void validate(JSONObject root)throws Exception{if(root==null)return;for(String table:Arrays.asList("router_connections","router_snapshots")){Set<String> ids=new HashSet<>();JSONArray rows=root.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject o=rows.getJSONObject(i);if(table.endsWith("connections"))Config.fromJson(o);else validateSnapshot(o);if(!ids.add(o.getString("id")))throw new IllegalArgumentException("Повтор ID роутера");}}}
    static void restore(SQLiteDatabase db,JSONObject root)throws Exception{validate(root);db.delete("router_connections",null,null);db.delete("router_snapshots",null,null);if(root==null)return;for(String table:Arrays.asList("router_connections","router_snapshots")){JSONArray rows=root.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject o=rows.getJSONObject(i);ContentValues v=new ContentValues();v.put("id",o.getString("id"));v.put("json",table.endsWith("connections")?Config.fromJson(o).toJson().toString():o.toString());if(table.endsWith("snapshots"))v.put("time",o.getLong("time"));db.insertOrThrow(table,null,v);}}db.execSQL("DELETE FROM router_snapshots WHERE id NOT IN(SELECT id FROM router_snapshots ORDER BY time DESC LIMIT 20)");}
}
