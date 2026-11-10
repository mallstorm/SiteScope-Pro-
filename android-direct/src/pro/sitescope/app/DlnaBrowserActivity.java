package pro.sitescope.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Native read-only DLNA ContentDirectory browser. */
public final class DlnaBrowserActivity extends Activity {
    private ServiceEndpoint endpoint;
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private String controlUrl="";
    private String currentId="0";
    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);endpoint=ServiceEndpoint.from(getIntent());build();loadDescription();}
    private void build(){LinearLayout root=ClientUi.root(this);root.addView(ClientUi.header(this,"DLNA Media Browser","UPnP ContentDirectory · read-only"));LinearLayout page=ClientUi.page(this);ScrollView s=new ScrollView(this);s.addView(page);root.addView(s,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));LinearLayout top=ClientUi.card(this,14);status=ClientUi.text(this,"Загрузка описания медиасервера…",13,ClientUi.MUTED,false);progress=new ProgressBar(this);top.addView(progress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,34)));top.addView(status);TextView rootButton=ClientUi.button(this,"Корень каталога",false);rootButton.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){browse("0");}});top.addView(rootButton,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,44),0,8,0,0));page.addView(top);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);page.addView(list,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));setContentView(root);}
    private void loadDescription(){new Thread(new Runnable(){@Override public void run(){try{HttpURLConnection c=(HttpURLConnection)new URL(endpoint.location).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);InputStream in=c.getInputStream();UpnpDescriptionParser.Description d=UpnpDescriptionParser.parse(in);in.close();c.disconnect();for(UpnpDescriptionParser.ServiceInfo s:d.serviceInfos)if(s.serviceType.contains("ContentDirectory")){controlUrl=resolve(endpoint.location,s.controlUrl);break;}if(controlUrl.length()==0)throw new Exception("ContentDirectory не найден");runOnUiThread(new Runnable(){@Override public void run(){status.setText("DLNA каталог готов");browse("0");}});}catch(final Exception e){error(e);}}}).start();}
    private void browse(final String objectId){progress.setVisibility(View.VISIBLE);status.setText("Загрузка каталога…");new Thread(new Runnable(){@Override public void run(){try{String body="<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><ObjectID>"+escape(objectId)+"</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>500</RequestedCount><SortCriteria></SortCriteria></u:Browse>";String response=soap(controlUrl,"urn:schemas-upnp-org:service:ContentDirectory:1#Browse",body);String didl=findText(response,"Result");final List<Item> items=parseDidl(didl);currentId=objectId;runOnUiThread(new Runnable(){@Override public void run(){progress.setVisibility(View.GONE);status.setText("Объектов: "+items.size()+" · ID "+currentId);render(items);}});}catch(final Exception e){error(e);}}}).start();}
    private void render(List<Item> items){list.removeAllViews();for(final Item i:items){String meta=i.container?"Каталог":(i.protocol.length()>0?i.protocol:"Медиа");TextView row=ClientUi.text(this,(i.container?"📁 ":"▶ ")+i.title+"\n"+meta,14,ClientUi.NAVY,true);row.setPadding(ClientUi.dp(this,12),ClientUi.dp(this,10),ClientUi.dp(this,12),ClientUi.dp(this,10));row.setBackground(ClientUi.round(this,ClientUi.SURFACE,12,ClientUi.BORDER,1));row.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){if(i.container)browse(i.id);else openMedia(i);}});list.addView(row,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,4,0,4));}}
    private void openMedia(Item i){if(i.url.length()==0)return;ServiceEndpoint media=new ServiceEndpoint();media.host=endpoint.host;media.displayName=i.title;media.uri=i.url;String lower=i.url.toLowerCase();if(lower.startsWith("rtsp://"))media.protocol="RTSP";else if(lower.startsWith("https://"))media.protocol="HTTPS";else media.protocol="HTTP";if(media.protocol.equals("RTSP")||i.protocol.toLowerCase().contains("video")||i.protocol.toLowerCase().contains("audio")){android.content.Intent in=new android.content.Intent(this,PlayerActivity.class);in.putExtra("url",i.url);in.putExtra("source_url",i.url);in.putExtra("name",i.title);in.putExtra("details","DLNA · "+i.protocol);startActivity(in);}else ProtocolRouter.openBuiltIn(this,media);}
    private String soap(String url,String action,String inner)throws Exception{String envelope="<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"+inner+"</s:Body></s:Envelope>";byte[] bytes=envelope.getBytes(StandardCharsets.UTF_8);HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(15000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","text/xml; charset=utf-8");c.setRequestProperty("SOAPACTION","\""+action+"\"");c.setFixedLengthStreamingMode(bytes.length);OutputStream o=c.getOutputStream();o.write(bytes);o.close();int code=c.getResponseCode();InputStream in=code>=200&&code<400?c.getInputStream():c.getErrorStream();String response=new String(readAll(in),StandardCharsets.UTF_8);c.disconnect();if(code<200||code>=400)throw new Exception("HTTP "+code);return response;}
    private static List<Item> parseDidl(String escaped)throws Exception{String xml=Html.fromHtml(escaped,Html.FROM_HTML_MODE_LEGACY).toString();List<Item> out=new ArrayList<Item>();XmlPullParser p=XmlPullParserFactory.newInstance().newPullParser();p.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),"UTF-8");Item cur=null;String tag="";int ev=p.getEventType();while(ev!=XmlPullParser.END_DOCUMENT){if(ev==XmlPullParser.START_TAG){tag=local(p.getName());if("container".equals(tag)||"item".equals(tag)){cur=new Item();cur.container="container".equals(tag);for(int a=0;a<p.getAttributeCount();a++)if("id".equals(local(p.getAttributeName(a))))cur.id=p.getAttributeValue(a);}else if(cur!=null&&"res".equals(tag)){for(int a=0;a<p.getAttributeCount();a++)if("protocolInfo".equals(local(p.getAttributeName(a))))cur.protocol=p.getAttributeValue(a);}}else if(ev==XmlPullParser.TEXT&&cur!=null){String v=p.getText().trim();if(v.length()>0){if("title".equals(tag))cur.title=v;else if("res".equals(tag))cur.url=v;}}else if(ev==XmlPullParser.END_TAG&&cur!=null){String n=local(p.getName());if((cur.container&&"container".equals(n))||(!cur.container&&"item".equals(n))){if(cur.title.length()==0)cur.title=cur.id;out.add(cur);cur=null;}}ev=p.next();}return out;}
    private static String findText(String xml,String wanted)throws Exception{XmlPullParser p=XmlPullParserFactory.newInstance().newPullParser();p.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),"UTF-8");String tag="";int ev=p.getEventType();while(ev!=XmlPullParser.END_DOCUMENT){if(ev==XmlPullParser.START_TAG)tag=local(p.getName());else if(ev==XmlPullParser.TEXT&&wanted.equals(tag))return p.getText();ev=p.next();}return"";}
    private static String resolve(String base,String value)throws Exception{return new URL(new URL(base),value).toString();}private static String local(String n){int c=n.indexOf(':');return c>=0?n.substring(c+1):n;}private static String escape(String x){return x.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}private static byte[] readAll(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[8192];int n;while((n=in.read(x))>=0)b.write(x,0,n);return b.toByteArray();}
    private void error(final Exception e){runOnUiThread(new Runnable(){@Override public void run(){progress.setVisibility(View.GONE);status.setText("Ошибка DLNA: "+e.getMessage());}});}static final class Item{String id="",title="",url="",protocol="";boolean container;}
}
