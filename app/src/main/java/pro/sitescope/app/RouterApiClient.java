package pro.sitescope.app;

import android.net.Network;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;

/** Fixed, read-only API operations. Response bodies and credentials never enter diagnostics. */
public final class RouterApiClient implements AutoCloseable {
    public static final class Failure extends IOException {
        public final int status;
        Failure(String text,int status){super(text);this.status=status;}
    }
    private final Network network;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile HttpURLConnection connection;
    private long deadline;
    private String base,user,password,token="",basic="";
    private JSONArray rows,warnings;
    private int bytes;
    public RouterApiClient(Network network){this.network=network;}
    @Override public void close(){cancelled.set(true);HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    private void check()throws IOException{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new Failure("Запрос отменён",0);if(System.nanoTime()>deadline)throw new Failure("Истекло время опроса роутера",0);}
    public JSONObject read(RouterStore.Config config,String username,String secret,String source)throws Exception {
        base=RouterStore.address(config.base,config.allowHttp).toASCIIString();user=username;password=secret;
        if(user.isEmpty()||user.length()>512||password.length()>4096||user.contains(":"))throw new Failure("Проверьте учётные данные владельца",0);
        rows=new JSONArray();warnings=new JSONArray();bytes=0;deadline=System.nanoTime()+30_000_000_000L;
        basic=Base64.encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
        try {
            if(config.kind.equals("routeros"))routeros();else if(config.kind.equals("openwrt"))openwrt();else throw new Failure("Неизвестный адаптер",0);
            check();return new JSONObject().put("id",UUID.randomUUID().toString()).put("time",System.currentTimeMillis()).put("adapter",config.kind).put("source",safe(source)).put("name",safe(config.name)).put("host",new URI(base).getHost()).put("partial",warnings.length()>0).put("warnings",warnings).put("rows",rows);
        } catch(Failure e){throw e;}catch(SSLException e){throw new Failure("Не удалось проверить TLS-сертификат роутера. Установите доверенный сертификат",0);}catch(SocketTimeoutException e){throw new Failure("Роутер не ответил вовремя",0);}catch(JSONException e){throw new Failure("Некорректный ответ API роутера",0);}catch(IOException e){throw new Failure(cancelled.get()?"Запрос отменён":"Нет связи с API роутера",0);}
        finally{user="";password="";token="";basic="";}
    }
    private String safe(String s){String v=ReportText.known(s,password,token,basic);return v.length()>512?v.substring(0,512)+"…":v;}
    private JSONObject row(String kind)throws Exception{return new JSONObject().put("kind",kind);}
    private void put(JSONObject r,String key,Object value)throws Exception{if(value!=null&&value!=JSONObject.NULL)r.put(key,safe(String.valueOf(value)));}
    private void add(JSONObject row)throws Exception{int n=row.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes+n>524288){if(!warnings.toString().contains("Объём"))warnings.put("Объём сведений превышает 512 КиБ: снимок частичный");return;}rows.put(row);bytes+=n;}
    private Object request(String path,JSONObject payload,boolean auth)throws Exception {
        check();URL url=new URL(base+path);HttpURLConnection c=(HttpURLConnection)(network==null?url.openConnection():network.openConnection(url));connection=c;
        try {
            check();c.setInstanceFollowRedirects(false);c.setUseCaches(false);int remaining=(int)Math.max(1,(deadline-System.nanoTime())/1_000_000);c.setConnectTimeout(Math.min(6000,remaining));c.setReadTimeout(Math.min(6000,remaining));c.setRequestProperty("Accept","application/json");
            if(auth)c.setRequestProperty("Authorization","Basic "+basic);
            if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] b=payload.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream out=c.getOutputStream()){check();out.write(b);}}
            int status=c.getResponseCode();check();if(status==401||status==403)throw new Failure("API отклонил авторизацию или права доступа",status);if(status<200||status>=300)throw new Failure("API вернул HTTP "+status+". Проверьте поддержку и доступ к API",status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1){check();total+=n;if(total>1048576)throw new Failure("Ответ API превышает 1 МиБ",0);out.write(buf,0,n);}JSONTokener parser=new JSONTokener(out.toString("UTF-8"));Object value=parser.nextValue();if(!(value instanceof JSONObject)&&!(value instanceof JSONArray)||parser.nextClean()!=0)throw new JSONException("format");return value;}
        }finally{c.disconnect();if(connection==c)connection=null;}
    }
    private interface Read {void run()throws Exception;}
    private void optional(String title,Read read)throws Exception {try{read.run();}catch(Failure e){check();if(e.status==403||e.status==404||e.status==405||e.status==501||e.status==-1)warnings.put(title+": API недоступен или недостаточно прав");else throw e;}}
    private JSONObject singleton(Object value)throws Exception {
        if(value instanceof JSONObject)return (JSONObject)value;
        if(value instanceof JSONArray&&((JSONArray)value).length()==1)return ((JSONArray)value).getJSONObject(0);
        throw new Failure("API вернул неоднозначный ответ о системе",0);
    }
    private void routeros()throws Exception {
        JSONObject identity=singleton(request("/rest/system/identity",null,true));JSONObject system=row("system");put(system,"name",identity.opt("name"));put(system,"manufacturer","MikroTik");add(system);
        optional("Система",()->{JSONObject info=singleton(request("/rest/system/resource",null,true));JSONObject r=row("system");put(r,"model",info.opt("board-name"));put(r,"software",info.opt("version"));put(r,"uptime",info.opt("uptime"));add(r);});
        optional("Интерфейсы",()->{JSONArray list=(JSONArray)request("/rest/interface",null,true);for(int i=0;i<list.length();i++){check();JSONObject o=list.getJSONObject(i),r=row("interface");put(r,"name",o.opt("name"));put(r,"interface_type",o.opt("type"));put(r,"status",o.opt("running"));add(r);}});
        optional("DHCP",()->{JSONArray list=(JSONArray)request("/rest/ip/dhcp-server/lease",null,true);for(int i=0;i<list.length();i++){check();JSONObject o=list.getJSONObject(i),r=row("lease");put(r,"name",o.opt("host-name"));put(r,"address",o.opt("address"));put(r,"mac",o.opt("mac-address"));put(r,"status",o.opt("status"));add(r);}});
    }
    private JSONObject rpc(String object,String method,JSONObject args)throws Exception {
        String session=token.isEmpty()?"00000000000000000000000000000000":token;
        JSONObject body=new JSONObject().put("jsonrpc","2.0").put("id",1).put("method","call").put("params",new JSONArray().put(session).put(object).put(method).put(args));
        JSONObject response=(JSONObject)request("/ubus",body,false);JSONArray result=response.optJSONArray("result");
        if(result==null||result.length()==0)throw new Failure("API ubus вернул неподдерживаемый ответ",-1);
        if(result.getInt(0)!=0)throw new Failure("API ubus отклонил операцию или права доступа",-1);
        return result.optJSONObject(1)==null?new JSONObject():result.getJSONObject(1);
    }
    private void openwrt()throws Exception {
        JSONObject login=rpc("session","login",new JSONObject().put("username",user).put("password",password).put("timeout",60));token=login.optString("ubus_rpc_session");if(!token.matches("[a-fA-F0-9]{32}"))throw new Failure("API ubus отклонил вход",401);
        try {
            JSONObject b=rpc("system","board",new JSONObject()),r=row("system");put(r,"name",b.opt("hostname"));put(r,"model",b.opt("model"));put(r,"manufacturer",b.opt("system"));JSONObject release=b.optJSONObject("release");if(release!=null)put(r,"software",release.optString("distribution")+" "+release.optString("version"));add(r);
            optional("Состояние системы",()->{JSONObject info=rpc("system","info",new JSONObject()),v=row("system");put(v,"uptime",info.opt("uptime"));add(v);});
            optional("Интерфейсы",()->{JSONArray list=rpc("network.interface","dump",new JSONObject()).optJSONArray("interface");if(list==null)throw new JSONException("interface");for(int i=0;i<list.length();i++){check();JSONObject o=list.getJSONObject(i),v=row("interface");put(v,"name",o.opt("interface"));put(v,"interface_type",o.opt("proto"));put(v,"status",o.opt("up"));StringBuilder addresses=new StringBuilder();for(String family:Arrays.asList("ipv4-address","ipv6-address")){JSONArray a=o.optJSONArray(family);if(a!=null)for(int j=0;j<a.length();j++){if(addresses.length()>0)addresses.append(", ");addresses.append(a.getJSONObject(j).optString("address"));}}put(v,"address",addresses.toString());add(v);}});
            optional("DHCP odhcpd",()->{JSONObject leases=rpc("dhcp","ipv4leases",new JSONObject());JSONObject devices=leases.optJSONObject("device");if(devices==null)throw new Failure("Нет данных odhcpd",-1);Iterator<String> keys=devices.keys();while(keys.hasNext()){JSONObject device=devices.getJSONObject(keys.next());JSONArray list=device.optJSONArray("leases");if(list==null)continue;for(int i=0;i<list.length();i++){check();JSONObject o=list.getJSONObject(i),v=row("lease");put(v,"name",o.opt("hostname"));put(v,"address",o.opt("ipaddr"));put(v,"mac",o.opt("mac"));add(v);}}});
        }finally{if(!cancelled.get()&&System.nanoTime()<deadline)try{rpc("session","destroy",new JSONObject().put("ubus_rpc_session",token));}catch(Exception ignored){warnings.put("Сессия завершится по таймауту сервера (60 с)");}}
    }
}
