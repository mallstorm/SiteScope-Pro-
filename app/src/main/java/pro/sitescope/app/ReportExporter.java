package pro.sitescope.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Streaming projection of measured fields. Never opens profiles, raw banners or SecureStore. */
public final class ReportExporter {
    private ReportExporter() { }
    public static final List<String> SECTIONS=Collections.unmodifiableList(Arrays.asList("devices","services","changes","exposure","routers"));
    public static final List<String> FIELDS=Collections.unmodifiableList(Arrays.asList("section","time","source","device","address","port","protocol","manufacturer","model","confidence","field","before","after","status","point","latency_ms","external_tcp_confirmed","run_state","mac","software","uptime","interface_type"));
    public static final String NOTE="Время — Unix milliseconds UTC. Данные собраны из сохранённых измерений, не являются проверкой текущей доступности. Чтение отчёта не является атомарной резервной копией. TCP-доступ подтверждается только для указанной точки и времени.";
    public static final class Options {
        public final List<String> sections,fields; public final String format;public final int limit;
        public Options(Collection<String> sections,Collection<String> fields,String format,int limit){
            if(!Arrays.asList("csv","json","html").contains(format)||limit<0||limit>50000)throw new IllegalArgumentException("Неверные параметры отчёта");
            this.sections=select(SECTIONS,sections);List<String> selected=select(FIELDS,fields);for(String required:Arrays.asList("source","time","section"))if(!selected.contains(required))selected.add(0,required);this.fields=Collections.unmodifiableList(selected);this.format=format;this.limit=limit;
            if(this.sections.isEmpty())throw new IllegalArgumentException("Выберите разделы отчёта");
        }
        private static List<String> select(List<String> allowed,Collection<String> input){List<String> result=new ArrayList<>();for(String s:input)if(!allowed.contains(s))throw new IllegalArgumentException("Неизвестное поле отчёта");for(String s:allowed)if(input.contains(s))result.add(s);return result;}
    }
    public static final class Result {public int rows,shortenedValues;public boolean truncated;public long startedAt,finishedAt;}
    public static Result write(Context context,OutputStream output,Options options,CancellationSignal cancel)throws Exception {
        SQLiteDatabase db=AppDatabase.get(context).read();Result result=new Result();result.startedAt=System.currentTimeMillis();
        Writer out=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));Sink sink=new Sink(out,options,result,cancel);sink.start();
        for(String section:options.sections){cancel.throwIfCanceled();String last="";
            while(!result.truncated){cancel.throwIfCanceled();String table=section.equals("devices")?"device_cards":section.equals("services")?"device_observations":section.equals("changes")?"device_history":section.equals("exposure")?"exposure_runs":"router_snapshots";
                String columns=section.equals("devices")?"id,alias,names,updated_at,(SELECT group_concat(DISTINCT host) FROM device_observations WHERE card_id=device_cards.id) AS address":"id,json";
                Cursor c=db.rawQuery("SELECT "+columns+" FROM "+table+" WHERE id>? ORDER BY id LIMIT 1",new String[]{last},cancel);
                try {if(!c.moveToFirst())break;last=c.getString(0);
                    if(section.equals("devices")){String name=c.getString(1);JSONArray names=new JSONArray(c.getString(2));if(name.isEmpty()&&names.length()>0)name=names.optString(0);sink.row(new JSONObject().put("section",section).put("time",c.getLong(3)).put("source","Каталог карточек").put("device",name.isEmpty()?last:name).put("address",c.getString(4)));}
                    else {JSONObject o=new JSONObject(c.getString(1));if(section.equals("services"))service(o,sink);else if(section.equals("changes"))change(o,sink);else if(section.equals("exposure"))exposure(o,sink);else router(o,sink);}
                }finally{c.close();}
            }
            if(result.truncated)break;
        }
        result.finishedAt=System.currentTimeMillis();cancel.throwIfCanceled();sink.finish();out.flush();return result;
    }
    private static void service(JSONObject o,Sink sink)throws Exception {
        DeviceRegistryStore.Observation v=DeviceRegistryStore.Observation.fromJson(o);DeviceIdentity identity=v.identity();JSONObject r=new JSONObject().put("section","services").put("time",v.seenAt).put("source",v.sourceLabel()+" · "+v.scope.label).put("device",v.name).put("address",v.host).put("port",v.port).put("protocol",v.protocol).put("manufacturer",identity.manufacturer).put("model",identity.model).put("confidence",identity.confidence).put("status",v.kind.equals("profile")?"Сохранённое подключение; доступность не проверена":"Сервис отвечал в момент наблюдения");sink.row(r);
    }
    private static void change(JSONObject o,Sink sink)throws Exception {
        if(!Arrays.asList("availability","manufacturer","model","certificate","protocol").contains(o.optString("field")))return;
        JSONObject r=new JSONObject().put("section","changes");for(String k:Arrays.asList("time","source","address","field","before","after"))r.put(k,o.opt(k));sink.row(r);
    }
    private static void exposure(JSONObject o,Sink sink)throws Exception {
        JSONArray list=o.optJSONArray("results");int n=list==null?0:list.length();
        for(int i=0;i<Math.max(1,n)&&!sink.result.truncated;i++){JSONObject v=n==0?new JSONObject():list.getJSONObject(i);JSONObject r=new JSONObject().put("section","exposure").put("time",o.optLong("time")).put("source",o.optString("source")).put("point",o.optString("transport")).put("address",v.optString("address",o.optString("host"))).put("port",v.opt("port")).put("protocol","TCP").put("status",v.optString("state","Нет завершённых проверок")).put("latency_ms",v.opt("latencyMs")).put("external_tcp_confirmed",ExposureStore.externalConfirmed(o,v)).put("run_state",o.optBoolean("cancelled")?"cancelled":!o.optString("error").isEmpty()?"error":"completed");sink.row(r);}
    }
    private static void router(JSONObject o,Sink sink)throws Exception {
        JSONArray list=o.getJSONArray("rows");for(int i=0;i<list.length()&&!sink.result.truncated;i++){JSONObject v=list.getJSONObject(i),r=new JSONObject().put("section","routers").put("time",o.getLong("time")).put("source",o.getString("source")).put("device",o.optString("name")+" · "+v.optString("name")).put("field",v.optString("kind")).put("run_state",o.optBoolean("partial")?"partial":"completed");for(String key:Arrays.asList("address","mac","manufacturer","model","software","uptime","interface_type","status"))r.put(key,v.opt(key));sink.row(r);}
    }
    private static final class Sink {
        final Writer out;final Options options;final Result result;final CancellationSignal cancel;
        Sink(Writer out,Options options,Result result,CancellationSignal cancel){this.out=out;this.options=options;this.result=result;this.cancel=cancel;}
        void start()throws Exception {
            if(options.format.equals("json"))out.write("{\"schema\":1,\"startedAt\":"+result.startedAt+",\"note\":"+JSONObject.quote(NOTE)+",\"fields\":"+new JSONArray(options.fields)+",\"sections\":"+new JSONArray(options.sections)+",\"rows\":[");
            else if(options.format.equals("csv")){out.write('\ufeff');for(int i=0;i<options.fields.size();i++){if(i>0)out.write(',');out.write(ReportText.csv(options.fields.get(i)));}out.write("\r\n");}
            else {out.write("<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>SiteScope Pro — отчёт</title><style>body{font:14px sans-serif;margin:24px}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:6px;white-space:pre-wrap;overflow-wrap:anywhere}</style><h1>SiteScope Pro — отчёт</h1><p>"+ReportText.html(NOTE)+"</p><table><thead><tr>");for(String key:options.fields)out.write("<th>"+ReportText.html(key)+"</th>");out.write("</tr></thead><tbody>");}
        }
        void row(JSONObject raw)throws Exception {cancel.throwIfCanceled();if(options.limit>0&&result.rows>=options.limit){result.truncated=true;return;}JSONObject safe=new JSONObject();for(String key:options.fields){String value=raw.isNull(key)?"":ReportText.clean(raw.optString(key));if(value.length()>4096){value=value.substring(0,4096)+"…";result.shortenedValues++;}safe.put(key,value);}
            if(options.format.equals("json")){if(result.rows>0)out.write(',');out.write(safe.toString());}
            else if(options.format.equals("csv")){for(int i=0;i<options.fields.size();i++){if(i>0)out.write(',');out.write(ReportText.csv(safe.getString(options.fields.get(i))));}out.write("\r\n");}
            else {out.write("<tr>");for(String key:options.fields)out.write("<td>"+ReportText.html(safe.getString(key))+"</td>");out.write("</tr>");}result.rows++;
        }
        void finish()throws Exception {String notice="Строк: "+result.rows+"; ограничено: "+result.truncated+"; сокращённых значений: "+result.shortenedValues+"; сбор: "+result.startedAt+" — "+result.finishedAt+". "+NOTE;
            if(options.format.equals("json"))out.write("],\"finishedAt\":"+result.finishedAt+",\"rowCount\":"+result.rows+",\"truncated\":"+result.truncated+",\"shortenedValues\":"+result.shortenedValues+"}");
            else if(options.format.equals("html"))out.write("</tbody></table><p>"+ReportText.html(notice)+"</p></html>");
            else {for(int i=0;i<options.fields.size();i++){if(i>0)out.write(',');String k=options.fields.get(i);out.write(ReportText.csv(k.equals("section")?"report_metadata":k.equals("time")?String.valueOf(result.finishedAt):k.equals("source")?notice:""));}out.write("\r\n");}
        }
    }
}
