package pro.sitescope.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

/** Owner-confirmed associations. These are not service observations or online states. */
public final class RouterLeaseStore {
    private RouterLeaseStore() { }
    static final int MAX_LINKS = 2000;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("id","revision","cardId","origin","mac","time","observedAt","snapshotId","source","routerHost","endpoint","adapter","scope","leaseName","address"));
    static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS router_lease_links(id TEXT PRIMARY KEY,card_id TEXT NOT NULL,origin TEXT NOT NULL,json TEXT NOT NULL,FOREIGN KEY(card_id) REFERENCES device_cards(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS router_links_card ON router_lease_links(card_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS router_links_origin ON router_lease_links(origin)");
    }
    private static List<JSONObject> list(SQLiteDatabase db, String where, String[] args) {
        List<JSONObject> out = new ArrayList<>();
        Cursor c=db.query("router_lease_links",new String[]{"json"},where,args,null,null,"id");
        try { while(c.moveToNext()) out.add(new JSONObject(c.getString(0))); }
        catch(Exception e) { throw new IllegalStateException("Не удалось прочитать связи DHCP",e); }
        finally { c.close(); }
        return out;
    }
    public static List<JSONObject> forCard(Context context,String card) { return list(AppDatabase.get(context).read(),"card_id=?",new String[]{card}); }
    public static List<JSONObject> forOrigin(Context context,String origin) { return list(AppDatabase.get(context).read(),"origin=?",new String[]{origin}); }
    private static JSONObject current(SQLiteDatabase db,String id) throws Exception {
        Cursor c=db.query("router_lease_links",new String[]{"json"},"id=?",new String[]{id},null,null,null);
        try { return c.moveToFirst()?new JSONObject(c.getString(0)):null; } finally { c.close(); }
    }
    public static JSONObject link(Context context,String snapshotId,String rawMac,String cardId,String expectedRevision) throws Exception {
        SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();
        try {
            JSONObject snapshot;
            Cursor c=db.query("router_snapshots",new String[]{"json"},"id=?",new String[]{snapshotId},null,null,null);
            try { if(!c.moveToFirst()) throw new IllegalArgumentException("Снимок удалён: обновите экран"); snapshot=new JSONObject(c.getString(0)); } finally { c.close(); }
            JSONObject lease=RouterComparison.uniqueLease(snapshot,rawMac);String mac=RouterComparison.mac(rawMac),origin=RouterComparison.origin(snapshot),id=NetworkScope.digest(origin+"|"+mac);
            JSONObject old=current(db,id);
            if(!(old==null?"":old.getString("revision")).equals(expectedRevision))throw new IllegalArgumentException("Связь изменилась: обновите экран и подтвердите заново");
            c=db.rawQuery("SELECT 1 FROM device_cards WHERE id=?",new String[]{cardId});
            try { if(!c.moveToFirst())throw new IllegalArgumentException("Карточка удалена: выберите другую"); } finally { c.close(); }
            if(old==null){c=db.rawQuery("SELECT COUNT(*) FROM router_lease_links",null);try{c.moveToFirst();if(c.getInt(0)>=MAX_LINKS)throw new IllegalArgumentException("Сохранено 2000 связей: удалите ненужные перед добавлением");}finally{c.close();}}
            JSONObject link=new JSONObject().put("id",id).put("revision",UUID.randomUUID().toString()).put("cardId",cardId).put("origin",origin).put("mac",mac).put("time",System.currentTimeMillis()).put("observedAt",snapshot.getLong("time")).put("snapshotId",snapshotId).put("source",ReportText.clean(snapshot.getString("source"))).put("routerHost",snapshot.getString("host")).put("endpoint",snapshot.getString("endpoint")).put("adapter",snapshot.getString("adapter")).put("scope",snapshot.getJSONObject("scope")).put("leaseName",ReportText.clean(lease.optString("name"))).put("address",ReportText.clean(lease.optString("address")));
            validateLink(link);write(db,link);db.setTransactionSuccessful();return link;
        } finally { db.endTransaction(); }
    }
    public static void unlink(Context context,String id,String revision) throws Exception {
        SQLiteDatabase db=AppDatabase.get(context).write();db.beginTransaction();
        try { JSONObject old=current(db,id);if(old==null||!old.getString("revision").equals(revision))throw new IllegalArgumentException("Связь изменилась: обновите экран");db.delete("router_lease_links","id=?",new String[]{id});db.setTransactionSuccessful(); } finally { db.endTransaction(); }
    }
    private static void write(SQLiteDatabase db,JSONObject link) throws Exception {
        ContentValues v=new ContentValues();v.put("id",link.getString("id"));v.put("card_id",link.getString("cardId"));v.put("origin",link.getString("origin"));v.put("json",link.toString());
        if(db.insertWithOnConflict("router_lease_links",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Не удалось записать связь DHCP");
    }
    /** Called inside the registry merge transaction. A split leaves links on the original card. */
    static void moveCard(SQLiteDatabase db,String from,String to) {
        try { for(JSONObject link:list(db,"card_id=?",new String[]{from})) { link.put("cardId",to);link.put("revision",UUID.randomUUID().toString());write(db,link); } }
        catch(Exception e) { throw new IllegalStateException("Не удалось перенести связи DHCP",e); }
    }
    static void validateLink(JSONObject link) throws Exception {
        Iterator<String> keys=link.keys();while(keys.hasNext())if(!FIELDS.contains(keys.next()))throw new IllegalArgumentException("Неверное поле связи DHCP");
        for(String key:FIELDS)if(!Arrays.asList("scope","time","observedAt").contains(key)&&(!(link.get(key) instanceof String)||link.getString(key).length()>2048))throw new IllegalArgumentException("Неверная связь DHCP");
        if(link.getLong("time")<0||link.getLong("observedAt")<0||link.getString("cardId").isEmpty()||link.getString("revision").isEmpty()||link.getString("snapshotId").isEmpty()||!Arrays.asList("routeros","openwrt").contains(link.getString("adapter")))throw new IllegalArgumentException("Неверная связь DHCP");
        JSONObject metadata=new JSONObject().put("endpoint",link.getString("endpoint")).put("scope",link.getJSONObject("scope")).put("adapter",link.getString("adapter")).put("host",link.getString("routerHost"));
        String origin=RouterComparison.origin(metadata),mac=RouterComparison.mac(link.getString("mac"));
        if(origin.isEmpty()||!origin.equals(link.getString("origin"))||mac.isEmpty()||!mac.equals(link.getString("mac"))||!NetworkScope.digest(origin+"|"+mac).equals(link.getString("id")))throw new IllegalArgumentException("Неверная область связи DHCP");
    }
    static JSONArray exportState(SQLiteDatabase db) { return new JSONArray(list(db,null,null)); }
    static void validate(JSONArray links,JSONObject graph) throws Exception {
        if(links==null)return;if(links.length()>MAX_LINKS)throw new IllegalArgumentException("Слишком много связей DHCP");
        Set<String> cards=new HashSet<>(),ids=new HashSet<>();JSONArray rows=graph==null?new JSONArray():graph.getJSONArray("cards");
        for(int i=0;i<rows.length();i++)cards.add(rows.getJSONObject(i).getString("id"));
        for(int i=0;i<links.length();i++){JSONObject link=links.getJSONObject(i);validateLink(link);if(!ids.add(link.getString("id"))||!cards.contains(link.getString("cardId")))throw new IllegalArgumentException("Неверная карточка связи DHCP");}
    }
    static void restore(SQLiteDatabase db,JSONArray links) throws Exception {
        db.delete("router_lease_links",null,null);if(links!=null)for(int i=0;i<links.length();i++)write(db,links.getJSONObject(i));
    }
}
