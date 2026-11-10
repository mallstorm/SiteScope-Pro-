package org.json;
public class JSONObject {
 public JSONObject(){} public JSONObject put(String k,Object v){return this;}
 public long optLong(String k){return 0;} public int optInt(String k){return 0;} public String optString(String k){return "";}
 public String optString(String k,String d){return d;} public boolean optBoolean(String k,boolean d){return d;}
 public JSONArray optJSONArray(String k){return null;}
}
