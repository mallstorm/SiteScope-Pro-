package pro.sitescope.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.net.URI;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent cards and source observations. Names/models are never identity keys. */
public final class DeviceRegistryStore {
    private DeviceRegistryStore() { }
    public static final class Observation {
        public String id = "", kind = "", sourceId = "", host = "", protocol = "", stableId = "", name = "", endpointKey = "";
        public int port;
        public long seenAt;
        public boolean ambiguous, manual;
        public NetworkScope scope = new NetworkScope("legacy:unknown", "Сеть не определена", false);
        public JSONObject data = new JSONObject();
        public String sourceLabel() { return "scan".equals(kind) ? "Сканирование" : "profile".equals(kind) ? "Подключение" : "Обнаружение"; }
        public DeviceIdentity identity() {
            return DeviceIdentity.fromJson(data.optJSONObject("identity"));
        }
        public ServiceEndpoint endpoint(Context context) {
            if (kind.equals("profile")) {
                for (ConnectionProfileStore.Profile p : ConnectionProfileStore.list(context)) if (sourceId.equals(p.deviceRef)) return p.endpoint();
                return null;
            }
            ServiceEndpoint result = kind.equals("scan") ? ServiceEndpoint.from(Models.PortResult.fromJson(data)) : ServiceEndpoint.from(DeviceInventoryStore.deviceFromJson(data));
            result.scope = scope; return result;
        }
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("kind", kind); o.put("sourceId", sourceId); o.put("host", host); o.put("port", port);
                o.put("protocol", protocol); o.put("stableId", stableId); o.put("name", name); o.put("endpointKey", endpointKey);
                o.put("seenAt", seenAt); o.put("ambiguous", ambiguous); o.put("manual", manual); o.put("scope", scope.toJson()); o.put("data", data);
            } catch (Exception ignored) { }
            return o;
        }
        public static Observation fromJson(JSONObject o) {
            Observation x = new Observation();
            x.id=o.optString("id"); x.kind=o.optString("kind"); x.sourceId=o.optString("sourceId"); x.host=o.optString("host");
            x.port=o.optInt("port"); x.protocol=o.optString("protocol"); x.stableId=o.optString("stableId"); x.name=o.optString("name");
            x.endpointKey=o.optString("endpointKey"); x.seenAt=o.optLong("seenAt"); x.ambiguous=o.optBoolean("ambiguous"); x.manual=o.optBoolean("manual");
            x.scope=NetworkScope.fromJson(o.optJSONObject("scope"), x.id); x.data=o.optJSONObject("data"); if(x.data==null)x.data=new JSONObject();
            return x;
        }
    }
    public static final class Card {
        public String id = "", alias = "", title = "", addresses = "", protocols = "";
        public final List<String> priorNames = new ArrayList<String>();
        public long createdAt, updatedAt;
        public int memberCount;
        public final List<Observation> observations = new ArrayList<Observation>();
    }

    static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS device_cards(id TEXT PRIMARY KEY, alias TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, names TEXT NOT NULL DEFAULT '[]',search TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_observations(id TEXT PRIMARY KEY,card_id TEXT NOT NULL,kind TEXT NOT NULL,source_id TEXT NOT NULL,scope TEXT NOT NULL,stable_id TEXT NOT NULL,endpoint_key TEXT NOT NULL,host TEXT NOT NULL,protocol TEXT NOT NULL,seen_at INTEGER NOT NULL,manual INTEGER NOT NULL DEFAULT 0,json TEXT NOT NULL,search TEXT NOT NULL,FOREIGN KEY(card_id) REFERENCES device_cards(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_observation_card ON device_observations(card_id,seen_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_observation_stable ON device_observations(scope,stable_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_observation_endpoint ON device_observations(scope,endpoint_key)");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_cards_updated ON device_cards(updated_at DESC)");
    }

    public static Observation discovery(Models.DiscoveredDevice d, NetworkScope scope, long time) {
        Observation o = new Observation(); o.kind="discovery"; o.host=d.ip; o.port=d.port;
        o.protocol=ServiceEndpoint.from(d).protocol; o.scope=scope; o.stableId=d.stableId; o.name=d.name; o.seenAt=time;
        o.data=DeviceInventoryStore.deviceToJson(d);
        String path="";
        try {
            URI u=new URI(d.location.split("\\s+",2)[0]);
            if (o.host.equalsIgnoreCase(u.getHost())) { o.port=u.getPort()>0?u.getPort():"https".equalsIgnoreCase(u.getScheme())?443:80; path=u.getRawPath(); }
        } catch(Exception ignored) { }
        o.endpointKey=endpointKey(o.host,o.port,o.protocol,path);
        o.id=NetworkScope.digest("discovery|"+scope.id+"|"+d.ip+"|"+o.port+"|"+d.protocol+"|"+o.stableId+"|"+path+"|"+d.services);
        o.sourceId=o.id; return o;
    }
    public static Observation scan(Models.PortResult r, NetworkScope scope, long time) {
        Observation o=new Observation();o.kind="scan";o.host=r.ip.isEmpty()?r.host.isEmpty()?r.target:r.host:r.ip;o.port=r.port;
        o.protocol=ServiceEndpoint.from(r).protocol;o.scope=scope;o.seenAt=time;o.name=r.hostname;o.data=r.toJson();
        if(!"low".equals(r.confidence))o.endpointKey=endpointKey(o.host,o.port,o.protocol,"");
        o.id=NetworkScope.digest("scan|"+scope.id+"|"+o.host+"|"+o.port);o.sourceId=o.id;return o;
    }
    private static Observation profile(ConnectionProfileStore.Profile p) {
        Observation o=new Observation();o.kind="profile";o.host=p.host;o.port=p.port;o.protocol=p.protocol;o.name=p.name;o.seenAt=p.savedAt;
        o.scope=p.scope==null?new NetworkScope("legacy:profile:"+p.deviceRef,"Сеть сохранённого подключения не определена",false):p.scope;
        o.sourceId=p.deviceRef;o.id=NetworkScope.digest("profile|"+p.deviceRef);
        try { URI u=new URI(p.uri); if(u.getRawUserInfo()==null&&u.getRawQuery()==null)o.endpointKey=endpointKey(o.host,o.port,o.protocol,u.getRawPath()); }catch(Exception ignored){}
        // No copy of profile URI, credentials, notes, or credential-derived profile ID.
        return o;
    }
    private static String endpointKey(String host,int port,String protocol,String path) {
        return NetworkScope.digest(host.toLowerCase(Locale.US)+"|"+port+"|"+protocol+"|"+(path==null||path.isEmpty()?"/":path));
    }

    /** Copies are collected outside the registry lock to avoid cross-store lock inversion. */
    public static void sync(Context context) {
        List<DeviceInventoryStore.Snapshot> snapshots=DeviceInventoryStore.list(context);
        Collections.reverse(snapshots);
        List<ConnectionProfileStore.Profile> profiles=ConnectionProfileStore.ensureDeviceRefs(context);
        synchronized(DeviceRegistryStore.class) {
            SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();
            try {
                for(DeviceInventoryStore.Snapshot s:snapshots) {
                    List<Observation> batch=new ArrayList<Observation>();
                    for(Models.DiscoveredDevice d:s.devices)batch.add(discovery(d,d.scope==null?new NetworkScope("legacy:discovery:"+s.id,"Сеть прежнего поиска не определена",false):d.scope,s.timestamp));
                    markAmbiguous(batch);
                    for(Observation o:batch)observe(db,o,true);DeviceHistoryStore.prune(db,System.currentTimeMillis());
                }
                Cursor scans=db.rawQuery("SELECT s.json,r.json FROM scan_results r JOIN scan_sessions s ON s.row_id=r.session_id ORDER BY s.timestamp ASC",null);
                try { while(scans.moveToNext()) {
                    JSONObject session=new JSONObject(scans.getString(0));
                    NetworkScope scope=NetworkScope.fromJson(session.optJSONObject("scope"),"scan:"+session.optLong("timestamp")+":"+NetworkScope.digest(session.optString("targetExpression")));
                    Models.PortResult r=Models.PortResult.fromJson(new JSONObject(scans.getString(1)));
                    if(r.open)observe(db,scan(r,scope,session.optLong("timestamp")));
                }}finally{scans.close();}
                Set<String> refs=new HashSet<String>();
                for(ConnectionProfileStore.Profile p:profiles){refs.add(p.deviceRef);observe(db,profile(p));}
                Cursor stale=db.query("device_observations",new String[]{"id","source_id"},"kind='profile'",null,null,null,null);
                List<String> remove=new ArrayList<String>();try{while(stale.moveToNext())if(!refs.contains(stale.getString(1)))remove.add(stale.getString(0));}finally{stale.close();}
                for(String id:remove)db.delete("device_observations","id=?",new String[]{id});
                db.execSQL("DELETE FROM device_cards WHERE NOT EXISTS(SELECT 1 FROM device_observations o WHERE o.card_id=device_cards.id)");
                db.setTransactionSuccessful();
            }catch(Exception e){throw new IllegalStateException("Не удалось обновить карточки устройств",e);}finally{db.endTransaction();}
        }
    }
    static void markAmbiguous(List<Observation> batch) {
        Map<String,Set<String>> hosts=new HashMap<String,Set<String>>();
        for(Observation o:batch)if(!o.stableId.isEmpty())hosts.computeIfAbsent(o.scope.id+"|"+o.stableId,k->new HashSet<String>()).add(o.host);
        for(Observation o:batch)if(!o.stableId.isEmpty()&&hosts.get(o.scope.id+"|"+o.stableId).size()>1)o.ambiguous=true;
    }
    public static void recordBatch(Context context,List<Observation> batch) {
        SQLiteDatabase db=AppDatabase.get(context).write();recordBatch(db,batch);
    }
    static void recordBatch(SQLiteDatabase db,List<Observation> batch) {
        db.beginTransaction();
        try{markAmbiguous(batch);int count=0;for(Observation o:batch){observe(db,o,true);if(++count%500==0)DeviceHistoryStore.prune(db,System.currentTimeMillis());}DeviceHistoryStore.prune(db,System.currentTimeMillis());db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    private static String observe(SQLiteDatabase db,Observation o) { return observe(db,o,false); }
    private static String observe(SQLiteDatabase db,Observation o,boolean history) {
        if(o.host.isEmpty()||o.id.isEmpty())return "";
        String card="";boolean manual=false;Observation previous=null;
        Cursor old=db.query("device_observations",new String[]{"card_id","seen_at","manual","json"},"id=?",new String[]{o.id},null,null,null);
        try{if(old.moveToFirst()){try{previous=Observation.fromJson(new JSONObject(old.getString(3)));}catch(Exception e){throw new IllegalStateException(e);}card=old.getString(0);manual=old.getInt(2)!=0;if(old.getLong(1)>o.seenAt)return card;}}finally{old.close();}
        if(card.isEmpty()&&!o.stableId.isEmpty()&&o.scope.reliable&&!o.ambiguous) {
            Set<String> owners=owners(db,"scope=? AND stable_id=?",new String[]{o.scope.id,o.stableId});
            if(owners.size()==1)card=owners.iterator().next();else if(owners.size()>1)o.ambiguous=true;
        }
        if(card.isEmpty()&&!o.endpointKey.isEmpty()&&!o.ambiguous) {
            Set<String> owners=owners(db,"scope=? AND endpoint_key=?",new String[]{o.scope.id,o.endpointKey});
            if(owners.size()==1) {
                String candidate=owners.iterator().next();
                if(o.stableId.isEmpty()||!hasOtherStable(db,candidate,o.stableId))card=candidate;else o.ambiguous=true;
            }else if(owners.size()>1)o.ambiguous=true;
        }
        if(card.isEmpty()) {
            card=UUID.randomUUID().toString();ContentValues c=new ContentValues();c.put("id",card);String alias=legacyAlias(db,o);c.put("alias",alias);c.put("search",alias.toLowerCase(Locale.ROOT));c.put("created_at",o.seenAt);c.put("updated_at",o.seenAt);db.insertOrThrow("device_cards",null,c);
        }
        o.manual=manual;
        writeObservation(db,card,o);
        if(history)DeviceHistoryStore.observed(db,o,previous);
        db.execSQL("UPDATE device_cards SET updated_at=MAX(updated_at,?) WHERE id=?",new Object[]{o.seenAt,card});return card;
    }
    private static String legacyAlias(SQLiteDatabase db,Observation o) {
        if(!o.kind.equals("discovery")||!o.scope.id.startsWith("legacy:discovery:"))return "";
        Cursor c=db.query("inventory_aliases",new String[]{"alias"},"ip=?",new String[]{o.host},null,null,null);
        try{return c.moveToFirst()?c.getString(0):"";}finally{c.close();}
    }
    private static Set<String> owners(SQLiteDatabase db,String where,String[] args) {
        Set<String> out=new LinkedHashSet<String>();Cursor c=db.query(true,"device_observations",new String[]{"card_id"},where,args,null,null,null,"3");
        try{while(c.moveToNext())out.add(c.getString(0));}finally{c.close();}return out;
    }
    private static boolean hasOtherStable(SQLiteDatabase db,String card,String stable) {
        Cursor c=db.rawQuery("SELECT 1 FROM device_observations WHERE card_id=? AND stable_id!='' AND stable_id!=? LIMIT 1",new String[]{card,stable});try{return c.moveToFirst();}finally{c.close();}
    }
    private static void writeObservation(SQLiteDatabase db,String card,Observation o) {
        ContentValues v=new ContentValues();v.put("id",o.id);v.put("card_id",card);v.put("kind",o.kind);v.put("source_id",o.sourceId);v.put("scope",o.scope.id);v.put("stable_id",o.stableId);
        v.put("host",o.host);v.put("protocol",o.protocol);v.put("endpoint_key",o.endpointKey);v.put("seen_at",o.seenAt);v.put("manual",o.manual?1:0);v.put("json",o.toJson().toString());v.put("search",o.toJson().toString().toLowerCase(Locale.ROOT));if(db.update("device_observations",v,"id=?",new String[]{o.id})==0&&db.insert("device_observations",null,v)<0)throw new IllegalStateException("Не удалось сохранить запись устройства");
    }
    public static synchronized List<Card> list(Context context,String query,int limit) {
        SQLiteDatabase db=AppDatabase.get(context).read();String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        Cursor c=db.rawQuery("SELECT id FROM device_cards c WHERE ?='' OR instr(c.search,?)>0 OR EXISTS(SELECT 1 FROM device_observations o WHERE o.card_id=c.id AND instr(o.search,?)>0) ORDER BY updated_at DESC,id LIMIT ?",new String[]{q,q,q,Integer.toString(Math.max(1,limit))});
        List<Card> out=new ArrayList<Card>();try{while(c.moveToNext())out.add(readCard(db,c.getString(0),1));}finally{c.close();}return out;
    }
    public static synchronized int count(Context context) { Cursor c=AppDatabase.get(context).read().rawQuery("SELECT COUNT(*) FROM device_cards",null);try{c.moveToFirst();return c.getInt(0);}finally{c.close();} }
    public static synchronized Card get(Context context,String id,int limit) {return readCard(AppDatabase.get(context).read(),id,limit);}
    private static Card readCard(SQLiteDatabase db,String id,int limit) {
        Cursor c=db.query("device_cards",null,"id=?",new String[]{id},null,null,null);Card card=new Card();
        try{if(!c.moveToFirst())return null;card.id=id;card.alias=c.getString(c.getColumnIndexOrThrow("alias"));card.createdAt=c.getLong(c.getColumnIndexOrThrow("created_at"));card.updatedAt=c.getLong(c.getColumnIndexOrThrow("updated_at"));
            JSONArray names=new JSONArray(c.getString(c.getColumnIndexOrThrow("names")));for(int i=0;i<names.length();i++)card.priorNames.add(names.getString(i));}catch(Exception e){throw new IllegalStateException(e);}finally{c.close();}
        c=db.rawQuery("SELECT COUNT(*),group_concat(DISTINCT host),group_concat(DISTINCT protocol) FROM device_observations WHERE card_id=?",new String[]{id});
        try{c.moveToFirst();card.memberCount=c.getInt(0);card.addresses=c.isNull(1)?"":c.getString(1);card.protocols=c.isNull(2)?"":c.getString(2);}finally{c.close();}
        c=db.query("device_observations",new String[]{"json"},"card_id=?",new String[]{id},null,null,"seen_at DESC,id",Integer.toString(Math.max(1,limit)));
        try{while(c.moveToNext())card.observations.add(Observation.fromJson(new JSONObject(c.getString(0))));}catch(Exception invalid){throw new IllegalStateException(invalid);}finally{c.close();}
        card.title=card.alias.isEmpty()?card.observations.isEmpty()?card.id:card.observations.get(0).name.isEmpty()?card.addresses:card.observations.get(0).name:card.alias;return card;
    }
    public static synchronized void rename(Context context,String id,String alias) {ContentValues v=new ContentValues();v.put("alias",DeviceIdentity.text(alias));SQLiteDatabase db=AppDatabase.get(context).write();db.update("device_cards",v,"id=?",new String[]{id});refreshSearch(db,id);}
    private static void refreshSearch(SQLiteDatabase db,String id) {
        Cursor c=db.query("device_cards",new String[]{"alias","names"},"id=?",new String[]{id},null,null,null);
        try{if(c.moveToFirst()){ContentValues v=new ContentValues();v.put("search",(c.getString(0)+" "+c.getString(1)).toLowerCase(Locale.ROOT));db.update("device_cards",v,"id=?",new String[]{id});}}finally{c.close();}
    }
    public static synchronized void merge(Context context,String target,String other) {
        if(target.equals(other))throw new IllegalArgumentException("Выберите другую карточку");SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();
        try {
            Card a=readCard(db,target,1),b=readCard(db,other,1);if(a==null||b==null)throw new IllegalArgumentException("Карточка удалена");
            move(db,other,target,null);markManual(db,target);
            Set<String> names=new LinkedHashSet<String>(a.priorNames);names.addAll(b.priorNames);
            if(!b.alias.isEmpty()&&!b.alias.equals(a.alias))names.add(b.alias);
            ContentValues namesValue=new ContentValues();namesValue.put("names",new JSONArray(names).toString());db.update("device_cards",namesValue,"id=?",new String[]{target});
            if(a.alias.isEmpty()&&!b.alias.isEmpty()){ContentValues v=new ContentValues();v.put("alias",b.alias);db.update("device_cards",v,"id=?",new String[]{target});}
            refreshSearch(db,target);
            db.delete("device_cards","id=?",new String[]{other});db.execSQL("UPDATE device_cards SET updated_at=? WHERE id=?",new Object[]{Math.max(a.updatedAt,b.updatedAt),target});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public static synchronized String split(Context context,String cardId,List<String> ids,String alias) {
        SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();
        try {
            Card card=readCard(db,cardId,1);Set<String> selected=new HashSet<String>(ids);
            if(card==null||selected.isEmpty()||selected.size()>=card.memberCount)throw new IllegalArgumentException("Выберите часть записей карточки");
            for(String id:selected){Cursor c=db.query("device_observations",new String[]{"card_id"},"id=?",new String[]{id},null,null,null);try{if(!c.moveToFirst()||!cardId.equals(c.getString(0)))throw new IllegalArgumentException("Запись относится к другой карточке");}finally{c.close();}}
            String next=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",next);v.put("alias",DeviceIdentity.text(alias));v.put("search",DeviceIdentity.text(alias).toLowerCase(Locale.ROOT));v.put("created_at",card.createdAt);v.put("updated_at",card.updatedAt);db.insertOrThrow("device_cards",null,v);
            move(db,cardId,next,selected);markManual(db,cardId);db.setTransactionSuccessful();return next;
        }finally{db.endTransaction();}
    }
    private static void move(SQLiteDatabase db,String from,String to,Set<String> ids) {
        Cursor c=db.query("device_observations",new String[]{"id","json"},"card_id=?",new String[]{from},null,null,null);List<Observation> rows=new ArrayList<Observation>();
        try{while(c.moveToNext())if(ids==null||ids.contains(c.getString(0)))rows.add(Observation.fromJson(new JSONObject(c.getString(1))));}catch(Exception e){throw new IllegalStateException(e);}finally{c.close();}
        for(Observation o:rows){o.manual=true;writeObservation(db,to,o);}
    }
    private static void markManual(SQLiteDatabase db,String card) {move(db,card,card,null);}

    static JSONObject exportGraph(SQLiteDatabase db) throws Exception {
        JSONObject root=new JSONObject();JSONArray cards=new JSONArray(),observations=new JSONArray();db.beginTransactionNonExclusive();
        try {
            Cursor c=db.query("device_cards",null,null,null,null,null,"id");try{while(c.moveToNext()){JSONObject o=new JSONObject();o.put("id",c.getString(c.getColumnIndexOrThrow("id")));o.put("alias",c.getString(c.getColumnIndexOrThrow("alias")));o.put("createdAt",c.getLong(c.getColumnIndexOrThrow("created_at")));o.put("updatedAt",c.getLong(c.getColumnIndexOrThrow("updated_at")));o.put("names",new JSONArray(c.getString(c.getColumnIndexOrThrow("names"))));cards.put(o);}}finally{c.close();}
            c=db.query("device_observations",new String[]{"card_id","json"},null,null,null,null,"id");try{while(c.moveToNext()){JSONObject o=new JSONObject(c.getString(1));o.put("cardId",c.getString(0));observations.put(o);}}finally{c.close();}
            root.put("history",DeviceHistoryStore.exportState(db));root.put("cards",cards);root.put("observations",observations);db.setTransactionSuccessful();return root;
        }finally{db.endTransaction();}
    }
    static void validateGraph(JSONObject graph) throws Exception {
        Set<String> cards=new HashSet<String>(),ids=new HashSet<String>();JSONArray a=graph.getJSONArray("cards"),b=graph.getJSONArray("observations");
        for(int i=0;i<a.length();i++){String id=a.getJSONObject(i).getString("id");if(id.isEmpty()||!cards.add(id))throw new IllegalArgumentException("Повтор ID карточки");}
        for(int i=0;i<b.length();i++){JSONObject o=b.getJSONObject(i);if(!cards.contains(o.getString("cardId"))||o.getString("id").isEmpty()||!ids.add(o.getString("id")))throw new IllegalArgumentException("Некорректная связь карточки");if(o.getString("host").isEmpty()||o.getJSONObject("scope").getString("id").isEmpty()||!Arrays.asList("scan","discovery","profile").contains(o.getString("kind")))throw new IllegalArgumentException("Некорректная запись устройства");o.getJSONObject("data");}
        DeviceHistoryStore.validate(graph.optJSONObject("history"),ids);
    }
    static void restoreGraph(SQLiteDatabase db,JSONObject graph) throws Exception {
        if(graph!=null)validateGraph(graph);db.delete("device_observations",null,null);db.delete("device_cards",null,null);if(graph==null){DeviceHistoryStore.restore(db,null);return;}
        JSONArray cards=graph.getJSONArray("cards"),rows=graph.getJSONArray("observations");
        for(int i=0;i<cards.length();i++){JSONObject o=cards.getJSONObject(i);ContentValues v=new ContentValues();v.put("id",o.getString("id"));v.put("alias",o.optString("alias"));v.put("names",o.optJSONArray("names")==null?"[]":o.optJSONArray("names").toString());v.put("created_at",o.optLong("createdAt"));v.put("updated_at",o.optLong("updatedAt"));db.insertOrThrow("device_cards",null,v);refreshSearch(db,o.getString("id"));}
        for(int i=0;i<rows.length();i++){JSONObject o=rows.getJSONObject(i);writeObservation(db,o.getString("cardId"),Observation.fromJson(o));}
        DeviceHistoryStore.restore(db,graph.optJSONObject("history"));
    }
}
