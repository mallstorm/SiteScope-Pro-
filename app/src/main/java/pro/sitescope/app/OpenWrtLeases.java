package pro.sitescope.app;

import org.json.*;
import java.util.*;

/** Whitelisted IPv4 lease projections; no file reads or generic commands. */
public final class OpenWrtLeases {
    private OpenWrtLeases() { }
    public static final String LUCI="luci-rpc.getDHCPLeases", ODHCPD="dhcp.ipv4leases";
    public static JSONArray luci(JSONObject response) throws JSONException {
        JSONArray list=response.getJSONArray("dhcp_leases"), out=new JSONArray();
        for(int i=0;i<list.length();i++)out.put(project(list.getJSONObject(i),LUCI,"ipaddr"));
        return out;
    }
    public static JSONArray odhcpd(JSONObject response) throws JSONException {
        JSONObject devices=response.getJSONObject("device");JSONArray out=new JSONArray();Iterator<String> keys=devices.keys();
        while(keys.hasNext()){
            JSONArray list=devices.getJSONObject(keys.next()).getJSONArray("leases");
            for(int i=0;i<list.length();i++){JSONObject row=list.getJSONObject(i);out.put(project(row,ODHCPD,row.has("address")?"address":"ipaddr"));}
        }
        return out;
    }
    private static JSONObject project(JSONObject row,String source,String addressKey)throws JSONException {
        JSONObject out=new JSONObject().put("kind","lease").put("lease_source",source);
        for(String key:Arrays.asList("hostname","mac",addressKey)){
            if(!row.has(key)||row.isNull(key))continue;
            if(!(row.get(key) instanceof String))throw new JSONException("Invalid lease field");
            out.put(key.equals("hostname")?"name":key.equals("mac")?"mac":"address",row.getString(key));
        }
        return out;
    }
}
