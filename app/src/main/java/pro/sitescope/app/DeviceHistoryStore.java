package pro.sitescope.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

/** Measurement changes, never an assertion that a whole device is offline. */
public final class DeviceHistoryStore {
    private DeviceHistoryStore() { }
    static final long DAY = 86400000L;
    static final int MAX_EVENTS = 20000;
    public static final class Event {
        public String id, observationId, field, before, after, source, address;
        public long time;
        public String title() {
            switch(field) {
                case "availability": return "Результат проверки сервиса";
                case "model": return "Изменилась модель";
                case "manufacturer": return "Изменился производитель";
                case "certificate": return "Изменился сертификат TLS (SHA-256)";
                default: return "Изменился протокол сервиса";
            }
        }
    }
    static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS device_history(id TEXT PRIMARY KEY, observation_id TEXT NOT NULL, time INTEGER NOT NULL, json TEXT NOT NULL, FOREIGN KEY(observation_id) REFERENCES device_observations(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_history_time ON device_history(time DESC,id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_history_observation ON device_history(observation_id,time DESC)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_history_state(observation_id TEXT PRIMARY KEY,time INTEGER NOT NULL,json TEXT NOT NULL,FOREIGN KEY(observation_id) REFERENCES device_observations(id) ON DELETE CASCADE)");
    }
    private static JSONObject facts(DeviceRegistryStore.Observation o) throws Exception {
        JSONObject f=new JSONObject();f.put("availability","Сервис ответил");
        if(!o.protocol.equals("UNKNOWN")&&(!o.kind.equals("scan")||!o.data.optString("confidence","low").equals("low")))f.put("protocol",o.protocol);
        DeviceIdentity d=o.identity();
        if(!d.conflicting){if(!d.manufacturer.isEmpty())f.put("manufacturer",d.manufacturer);if(!d.model.isEmpty())f.put("model",d.model);}
        String cert=o.data.optString("tlsFingerprint");if(cert.matches("[a-fA-F0-9]{64}"))f.put("certificate",cert.toLowerCase(Locale.ROOT));
        return f;
    }
    static void observed(SQLiteDatabase db,DeviceRegistryStore.Observation o,DeviceRegistryStore.Observation previous) {
        if(o.kind.equals("profile"))return;
        try { JSONObject next=facts(o); update(db,o,next,previous==null?null:facts(previous),previous==null?0:previous.seenAt); }
        catch(Exception e){throw new IllegalStateException("Не удалось записать историю",e);}
    }
    private static void update(SQLiteDatabase db,DeviceRegistryStore.Observation o,JSONObject next,JSONObject seed,long seedTime) throws Exception {
        JSONObject old=seed;long time=seedTime;
        Cursor c=db.query("device_history_state",new String[]{"time","json"},"observation_id=?",new String[]{o.id},null,null,null);
        try{if(c.moveToFirst()){time=c.getLong(0);old=new JSONObject(c.getString(1));}}finally{c.close();}
        if(o.seenAt<=time)return;
        boolean first=old==null;if(first)old=new JSONObject();
        Iterator<String> keys=next.keys();while(keys.hasNext()) {
            String field=keys.next(),value=next.getString(field),before=old.optString(field);
            if(!value.equals(before)&&(field.equals("availability")||!first)) add(db,o,field,before.isEmpty()&&!field.equals("availability")?"Не определено":before,value);
            old.put(field,value);
        }
        ContentValues v=new ContentValues();v.put("observation_id",o.id);v.put("time",o.seenAt);v.put("json",old.toString());
        db.insertWithOnConflict("device_history_state",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static void add(SQLiteDatabase db,DeviceRegistryStore.Observation o,String field,String before,String after) throws Exception {
        JSONObject e=new JSONObject();String id=NetworkScope.digest(o.id+"|"+o.seenAt+"|"+field+"|"+after);
        e.put("id",id);e.put("observationId",o.id);e.put("time",o.seenAt);e.put("field",field);e.put("before",before);e.put("after",after);
        String source=o.sourceLabel()+" · "+o.scope.label;
        if(field.equals("manufacturer")||field.equals("model"))for(DeviceIdentity.Finding finding:o.identity().findings)if(finding.field.equals(field))source+=" · "+finding.source+" ("+finding.confidence+")";
        e.put("source",source);e.put("address",o.host+":"+o.port+" · "+o.protocol);
        ContentValues v=new ContentValues();v.put("id",id);v.put("observation_id",o.id);v.put("time",o.seenAt);v.put("json",e.toString());
        db.insertWithOnConflict("device_history",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    /** Absence is evaluated only for numeric targets actually covered by this run. */
    static void scanFinished(SQLiteDatabase db,Models.ScanSession session,NetworkScope scope,List<DeviceRegistryStore.Observation> open) {
        if(session.totalProbes<=0)return;
        try {
            Set<String> hosts=new HashSet<String>(ScannerEngine.parseTargets(session.targetExpression,ScannerEngine.EXTENDED_MAX_HOSTS));
            Set<Integer> ports=new HashSet<Integer>(ScannerEngine.parsePorts(session.portExpression,ScannerEngine.EXTENDED_MAX_PORTS));
            Set<String> seen=new HashSet<String>();for(DeviceRegistryStore.Observation o:open)seen.add(o.id);
            boolean complete=!session.recovered&&!session.cancelled&&session.error.isEmpty()&&session.completedProbes==session.totalProbes&&(long)hosts.size()*ports.size()==session.totalProbes;
            Cursor c=db.query("device_observations",new String[]{"json","host"},"kind='scan' AND scope=?",new String[]{scope.id},null,null,null);
            int checked=0;
            try{while(c.moveToNext()){
                if(!hosts.contains(c.getString(1)))continue;
                DeviceRegistryStore.Observation o=DeviceRegistryStore.Observation.fromJson(new JSONObject(c.getString(0)));
                String target=o.data.optString("target");if(target.isEmpty())target=o.host;
                if(!ports.contains(o.port)||seen.contains(o.id)||!hosts.contains(target)||!numeric(target))continue;
                long previousTime=o.seenAt;o.seenAt=session.timestamp+Math.max(0,session.durationMs);
                JSONObject next=new JSONObject();next.put("availability",complete?"Ответ не подтверждён полной проверкой":"Проверка не завершена или завершилась ошибкой");
                update(db,o,next,facts(o),previousTime);
                if(++checked%500==0)prune(db,System.currentTimeMillis());
            }}finally{c.close();}
            prune(db,System.currentTimeMillis());
        }catch(ScannerEngine.ScanException invalid){/* No trustworthy coverage for legacy/invalid target expressions. */}
        catch(Exception e){throw new IllegalStateException("Не удалось сравнить результаты сканирования",e);}
    }
    private static boolean numeric(String target) { return target.contains(":") ? target.matches("[0-9a-fA-F:%]+") : target.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"); }
    public static int retention(Context context) {return retention(AppDatabase.get(context).read());}
    private static int retention(SQLiteDatabase db) {
        Cursor c=db.rawQuery("SELECT value FROM app_meta WHERE key='device_history_days'",null);
        try{if(c.moveToFirst()){int n=Integer.parseInt(c.getString(0));if(Arrays.asList(7,30,90,365).contains(n))return n;}}finally{c.close();}return 90;
    }
    public static void setRetention(Context context,int days) {
        if(!Arrays.asList(7,30,90,365).contains(days))throw new IllegalArgumentException("Недопустимый срок хранения");
        SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();try{
            ContentValues v=new ContentValues();v.put("key","device_history_days");v.put("value",Integer.toString(days));db.insertWithOnConflict("app_meta",null,v,SQLiteDatabase.CONFLICT_REPLACE);prune(db,System.currentTimeMillis());db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    static void prune(SQLiteDatabase db,long now) {
        db.delete("device_history","time<?",new String[]{Long.toString(now-retention(db)*DAY)});
        db.execSQL("DELETE FROM device_history WHERE id NOT IN (SELECT id FROM device_history ORDER BY time DESC,id DESC LIMIT "+MAX_EVENTS+")");
    }
    public static void clear(Context context) {AppDatabase.get(context).write().delete("device_history",null,null);}
    public static List<Event> list(Context context,String cardId,int limit) {
        SQLiteDatabase db=AppDatabase.get(context).write();prune(db,System.currentTimeMillis());
        Cursor c=db.rawQuery("SELECT h.json FROM device_history h JOIN device_observations o ON o.id=h.observation_id WHERE ?='' OR o.card_id=? ORDER BY h.time DESC,h.id DESC LIMIT ?",new String[]{cardId,cardId,Integer.toString(Math.max(1,limit))});
        List<Event> out=new ArrayList<Event>();try{while(c.moveToNext()){JSONObject x=new JSONObject(c.getString(0));Event e=new Event();e.id=x.getString("id");e.observationId=x.getString("observationId");e.time=x.getLong("time");e.field=x.getString("field");e.before=x.optString("before");e.after=x.getString("after");e.source=x.getString("source");e.address=x.getString("address");out.add(e);}}catch(Exception e){throw new IllegalStateException(e);}finally{c.close();}return out;
    }
    static JSONObject exportState(SQLiteDatabase db) throws Exception {
        prune(db,System.currentTimeMillis());JSONObject root=new JSONObject();root.put("days",retention(db));
        for(String table:Arrays.asList("device_history","device_history_state")) {
            JSONArray rows=new JSONArray();Cursor c=db.query(table,null,null,null,null,null,null);
            try{while(c.moveToNext()){JSONObject r=new JSONObject(c.getString(c.getColumnIndexOrThrow("json")));
                if(table.endsWith("_state")){JSONObject wrapper=new JSONObject();wrapper.put("observationId",c.getString(c.getColumnIndexOrThrow("observation_id")));wrapper.put("time",c.getLong(c.getColumnIndexOrThrow("time")));wrapper.put("facts",r);r=wrapper;}rows.put(r);}}finally{c.close();}root.put(table,rows);
        }return root;
    }
    static void validate(JSONObject root,Set<String> observations) throws Exception {
        if(root==null)return;
        if(!Arrays.asList(7,30,90,365).contains(root.getInt("days")))throw new IllegalArgumentException("Неверный срок истории");
        for(String table:Arrays.asList("device_history","device_history_state")){
            JSONArray rows=root.getJSONArray(table);Set<String> ids=new HashSet<String>();
            for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String obs=r.getString("observationId"),id=table.endsWith("_state")?obs:r.getString("id");
                if(!observations.contains(obs)||!ids.add(id)||id.isEmpty()||r.getLong("time")<0)throw new IllegalArgumentException("Неверная связь истории");
                if(table.endsWith("_state"))r.getJSONObject("facts");else{r.getString("field");r.getString("after");r.getString("source");r.getString("address");}
            }
        }
    }
    static void restore(SQLiteDatabase db,JSONObject root) throws Exception {
        db.delete("device_history",null,null);db.delete("device_history_state",null,null);
        ContentValues setting=new ContentValues();setting.put("key","device_history_days");setting.put("value",root==null?"90":Integer.toString(root.getInt("days")));db.insertWithOnConflict("app_meta",null,setting,SQLiteDatabase.CONFLICT_REPLACE);
        if(root==null)return;
        for(String table:Arrays.asList("device_history","device_history_state")){
            JSONArray rows=root.getJSONArray(table);for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);ContentValues v=new ContentValues();v.put("observation_id",r.getString("observationId"));v.put("time",r.getLong("time"));
                if(table.endsWith("_state"))v.put("json",r.getJSONObject("facts").toString());else{v.put("id",r.getString("id"));v.put("json",r.toString());}db.insertOrThrow(table,null,v);
            }
        }prune(db,System.currentTimeMillis());
    }
}
