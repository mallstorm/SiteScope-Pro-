package pro.sitescope.app;

import android.content.*;
import org.json.*;
import java.util.*;

/** Local-only hostname recovery journal. No credentials, session tokens or automatic replay. */
public final class RouterChangeJournal {
    private RouterChangeJournal() { }
    private static final Set<String> ACTIVE=new HashSet<>();
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("router_changes_v1",Context.MODE_PRIVATE);}
    public static synchronized List<JSONObject> list(Context c)throws Exception{
        JSONArray rows=new JSONArray(prefs(c).getString("rows","[]"));List<JSONObject> out=new ArrayList<>();for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i));return out;
    }
    public static RouterSettings.Journal writer(Context c,String source){return new RouterSettings.Journal(){private String id;
        public void beforeWrite(RouterSettings.Plan p)throws Exception{id=UUID.randomUUID().toString();begin(c,new JSONObject().put("id",id).put("endpoint",p.endpoint).put("kind",p.kind).put("scope",p.scope).put("source",ReportText.clean(source)).put("before",p.before).put("after",p.after).put("time",System.currentTimeMillis()).put("state","unverified"));}
        public void applied(RouterSettings.Plan p)throws Exception{mark(c,id);}
        public void finished(){synchronized(RouterChangeJournal.class){ACTIVE.remove(id);}}
    };}
    private static synchronized void begin(Context c,JSONObject row)throws Exception{
        List<JSONObject> rows=list(c);if(rows.size()>=100)throw new IllegalStateException("Журнал содержит 100 операций. Сначала проверьте и очистите его");rows.add(0,row);write(c,rows);ACTIVE.add(row.getString("id"));
    }
    private static synchronized void mark(Context c,String id)throws Exception{List<JSONObject> rows=list(c);boolean found=false;for(JSONObject row:rows)if(row.getString("id").equals(id)){row.put("state","applied");found=true;}if(!found)throw new IllegalStateException("Запись журнала отсутствует");write(c,rows);}
    private static void write(Context c,List<JSONObject> rows){if(!prefs(c).edit().putString("rows",new JSONArray(rows).toString()).commit())throw new IllegalStateException("Не удалось сохранить журнал: запись на роутер запрещена");}
    public static synchronized void clear(Context c){if(!ACTIVE.isEmpty())throw new IllegalStateException("Запись ещё выполняется: журнал нельзя очищать");write(c,Collections.emptyList());}
}
