package pro.sitescope.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQLite repository for hosts profiles, entries, backups and apply history. */
public final class HostsStore {
    public static final String DEFAULT_PROFILE_ID = "default";
    private final AppDatabase database;

    public static final class Profile {
        public String id;
        public String name;
        public boolean active;
        public long createdAt;
        public long updatedAt;
        public String mode;
    }

    public static final class Backup {
        public String id;
        public long timestamp;
        public String mode;
        public String path;
        public String sha256;
        public long size;
        public String sourcePath;
    }

    public HostsStore(Context context) {
        database = AppDatabase.get(context);
        ensureDefault();
    }

    private void ensureDefault() {
        Cursor c = database.read().query("hosts_profiles", new String[]{"id"}, null, null, null, null, null, "1");
        try { if (c.moveToFirst()) return; } finally { c.close(); }
        long now = System.currentTimeMillis(); ContentValues v = new ContentValues();
        v.put("id", DEFAULT_PROFILE_ID); v.put("name", "Основной"); v.put("active", 1); v.put("created_at", now); v.put("updated_at", now); v.put("mode", "AUTO");
        database.write().insertWithOnConflict("hosts_profiles", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<Profile> profiles() {
        List<Profile> out = new ArrayList<Profile>();
        Cursor c = database.read().query("hosts_profiles", new String[]{"id","name","active","created_at","updated_at","mode"}, null, null, null, null, "active DESC, updated_at DESC");
        try { while (c.moveToNext()) { Profile p = new Profile(); p.id=c.getString(0); p.name=c.getString(1); p.active=c.getInt(2)!=0; p.createdAt=c.getLong(3); p.updatedAt=c.getLong(4); p.mode=c.getString(5); out.add(p); } }
        finally { c.close(); }
        return out;
    }

    public Profile activeProfile() {
        List<Profile> profiles = profiles(); for (Profile p : profiles) if (p.active) return p; return profiles.isEmpty() ? null : profiles.get(0);
    }

    public Profile createProfile(String name) {
        long now=System.currentTimeMillis(); Profile p=new Profile(); p.id=UUID.randomUUID().toString(); p.name=name==null||name.trim().length()==0?"Новый профиль":name.trim(); p.active=false; p.createdAt=now; p.updatedAt=now; p.mode="AUTO";
        ContentValues v=new ContentValues(); v.put("id",p.id);v.put("name",p.name);v.put("active",0);v.put("created_at",now);v.put("updated_at",now);v.put("mode",p.mode);
        database.write().insert("hosts_profiles",null,v); return p;
    }

    public void renameProfile(String id,String name) { ContentValues v=new ContentValues();v.put("name",name.trim());v.put("updated_at",System.currentTimeMillis());database.write().update("hosts_profiles",v,"id=?",new String[]{id}); }
    public void setProfileMode(String id,String mode) { ContentValues v=new ContentValues();v.put("mode",mode);v.put("updated_at",System.currentTimeMillis());database.write().update("hosts_profiles",v,"id=?",new String[]{id}); }
    public void activate(String id) { SQLiteDatabase db=database.write();db.beginTransaction();try{ContentValues off=new ContentValues();off.put("active",0);db.update("hosts_profiles",off,null,null);ContentValues on=new ContentValues();on.put("active",1);on.put("updated_at",System.currentTimeMillis());db.update("hosts_profiles",on,"id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();} }
    public void deleteProfile(String id) { if(DEFAULT_PROFILE_ID.equals(id)) return; database.write().delete("hosts_profiles","id=?",new String[]{id}); }

    public List<HostsEntry> entries(String profileId) {
        List<HostsEntry> out=new ArrayList<HostsEntry>(); Cursor c=database.read().query("hosts_entries",new String[]{"id","position","enabled","address","hostnames","comment","source"},"profile_id=?",new String[]{profileId},null,null,"position,id");
        try { while(c.moveToNext()){HostsEntry e=new HostsEntry();e.id=c.getLong(0);e.profileId=profileId;e.position=c.getInt(1);e.enabled=c.getInt(2)!=0;e.address=c.getString(3);String[] names=c.getString(4).split("\\s+");for(String n:names)if(n.trim().length()>0)e.hostnames.add(n);e.comment=c.getString(5);e.source=c.getString(6);out.add(e);} }
        finally{c.close();} return out;
    }

    public void replaceEntries(String profileId,List<HostsEntry> entries) {
        SQLiteDatabase db=database.write();db.beginTransaction();try{db.delete("hosts_entries","profile_id=?",new String[]{profileId});int pos=0;for(HostsEntry e:entries){e.profileId=profileId;e.position=pos++;insertEntry(db,e);}touch(db,profileId);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public long saveEntry(HostsEntry e) {
        SQLiteDatabase db=database.write(); if(e.id<=0){e.position=nextPosition(e.profileId);e.id=insertEntry(db,e);}else{ContentValues v=entryValues(e);db.update("hosts_entries",v,"id=?",new String[]{String.valueOf(e.id)});}touch(db,e.profileId);return e.id;
    }
    public void deleteEntry(long id,String profileId){database.write().delete("hosts_entries","id=?",new String[]{String.valueOf(id)});touch(database.write(),profileId);}

    private long insertEntry(SQLiteDatabase db,HostsEntry e){ContentValues v=entryValues(e);v.put("profile_id",e.profileId);return db.insertOrThrow("hosts_entries",null,v);}
    private ContentValues entryValues(HostsEntry e){ContentValues v=new ContentValues();v.put("position",e.position);v.put("enabled",e.enabled?1:0);v.put("address",e.address);StringBuilder names=new StringBuilder();for(String n:e.hostnames){if(names.length()>0)names.append(' ');names.append(n);}v.put("hostnames",names.toString());v.put("comment",e.comment==null?"":e.comment);v.put("source",e.source==null?"manual":e.source);return v;}
    private int nextPosition(String profileId){Cursor c=database.read().rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM hosts_entries WHERE profile_id=?",new String[]{profileId});try{return c.moveToFirst()?c.getInt(0):0;}finally{c.close();}}
    private void touch(SQLiteDatabase db,String profileId){ContentValues v=new ContentValues();v.put("updated_at",System.currentTimeMillis());db.update("hosts_profiles",v,"id=?",new String[]{profileId});}

    public void recordBackup(Backup b){ContentValues v=new ContentValues();v.put("id",b.id);v.put("timestamp",b.timestamp);v.put("mode",b.mode);v.put("path",b.path);v.put("sha256",b.sha256);v.put("size",b.size);v.put("source_path",b.sourcePath);database.write().insertWithOnConflict("hosts_backups",null,v,SQLiteDatabase.CONFLICT_REPLACE);trimBackups();}
    public List<Backup> backups(){List<Backup> out=new ArrayList<Backup>();Cursor c=database.read().query("hosts_backups",new String[]{"id","timestamp","mode","path","sha256","size","source_path"},null,null,null,null,"timestamp DESC","20");try{while(c.moveToNext()){Backup b=new Backup();b.id=c.getString(0);b.timestamp=c.getLong(1);b.mode=c.getString(2);b.path=c.getString(3);b.sha256=c.getString(4);b.size=c.getLong(5);b.sourcePath=c.getString(6);out.add(b);}}finally{c.close();}return out;}
    private void trimBackups(){database.write().execSQL("DELETE FROM hosts_backups WHERE id NOT IN (SELECT id FROM hosts_backups ORDER BY timestamp DESC LIMIT 20)");}

    public void recordApply(String profileId,String mode,boolean success,String message,String beforeHash,String afterHash){ContentValues v=new ContentValues();v.put("timestamp",System.currentTimeMillis());v.put("profile_id",profileId);v.put("mode",mode);v.put("success",success?1:0);v.put("message",message);v.put("before_hash",beforeHash);v.put("after_hash",afterHash);database.write().insert("hosts_apply_events",null,v);database.write().execSQL("DELETE FROM hosts_apply_events WHERE row_id NOT IN (SELECT row_id FROM hosts_apply_events ORDER BY timestamp DESC LIMIT 100)");}

    public JSONArray exportProfiles() throws Exception {JSONArray array=new JSONArray();for(Profile p:profiles()){JSONObject o=new JSONObject();o.put("id",p.id);o.put("name",p.name);o.put("active",p.active);o.put("createdAt",p.createdAt);o.put("updatedAt",p.updatedAt);o.put("mode",p.mode);JSONArray rows=new JSONArray();for(HostsEntry e:entries(p.id))rows.put(e.toJson());o.put("entries",rows);array.put(o);}return array;}
    public void restoreProfiles(JSONArray array) throws Exception {SQLiteDatabase db=database.write();db.beginTransaction();try{db.delete("hosts_entries",null,null);db.delete("hosts_profiles",null,null);if(array!=null)for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);ContentValues v=new ContentValues();String id=o.optString("id",UUID.randomUUID().toString());v.put("id",id);v.put("name",o.optString("name","Профиль"));v.put("active",o.optBoolean("active")?1:0);v.put("created_at",o.optLong("createdAt",System.currentTimeMillis()));v.put("updated_at",o.optLong("updatedAt",System.currentTimeMillis()));v.put("mode",o.optString("mode","AUTO"));db.insertOrThrow("hosts_profiles",null,v);JSONArray rows=o.optJSONArray("entries");if(rows!=null)for(int j=0;j<rows.length();j++){HostsEntry e=HostsEntry.fromJson(rows.getJSONObject(j));e.profileId=id;insertEntry(db,e);}}db.setTransactionSuccessful();}finally{db.endTransaction();}ensureDefault();}
}
