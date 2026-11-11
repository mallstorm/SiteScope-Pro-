package pro.sitescope.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLSocket;

/** Native FTP/FTPS and WebDAV browser with explicit user credentials and SAF transfers. */
public final class NativeFileClientActivity extends Activity {
    private static final int PICK_UPLOAD=4201, CREATE_DOWNLOAD=4202;
    private ServiceEndpoint endpoint;
    private EditText user,password,path;
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private FtpSession ftp;
    private String pendingRemote="";

    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);endpoint=ServiceEndpoint.from(getIntent());build();}

    private void build(){
        LinearLayout root=ClientUi.root(this);root.addView(ClientUi.header(this,"Файловый клиент",endpoint.protocol+" · безопасная ручная авторизация"));
        LinearLayout page=ClientUi.page(this);ScrollView scroll=new ScrollView(this);scroll.addView(page);root.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        LinearLayout c=ClientUi.card(this,15);c.addView(ClientUi.text(this,endpoint.endpointText(),17,ClientUi.NAVY,true));
        user=ClientUi.input(this,"Пользователь");password=ClientUi.input(this,"Пароль");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);path=ClientUi.input(this,"Путь");path.setText("/");
        c.addView(user,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,46),0,10,0,0));c.addView(password,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,46),0,8,0,0));c.addView(path,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,46),0,8,0,0));
        LinearLayout actions=ClientUi.row(this);TextView open=ClientUi.button(this,"Подключиться",true),upload=ClientUi.button(this,"Загрузить",false);actions.addView(open,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));actions.addView(space(8));actions.addView(upload,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));c.addView(actions,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,9,0,0));
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);c.addView(progress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,34)));status=ClientUi.text(this,"Пароли не сохраняются. FTPS проверяет системную цепочку сертификата.",12,ClientUi.MUTED,false);c.addView(status);page.addView(c);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);page.addView(list,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));
        open.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){load();}});upload.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_UPLOAD);}});
        setContentView(root);
    }
    private View space(int d){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(ClientUi.dp(this,d),1));return v;}

    private void load(){progress.setVisibility(View.VISIBLE);status.setText("Получение списка…");new Thread(new Runnable(){@Override public void run(){try{final List<RemoteItem> items;if("WEBDAV".equals(endpoint.protocol))items=WebDav.list(endpoint,user.getText().toString(),password.getText().toString(),path.getText().toString());else{if(ftp!=null)ftp.close();ftp=new FtpSession(endpoint,user.getText().toString(),password.getText().toString());ftp.connect();items=ftp.list(path.getText().toString());}runOnUiThread(new Runnable(){@Override public void run(){progress.setVisibility(View.GONE);status.setText(items.size()+" объектов");render(items);}});}catch(final Exception e){error(e);}}}).start();}
    private void render(List<RemoteItem> items){list.removeAllViews();for(final RemoteItem item:items){TextView row=ClientUi.text(this,(item.directory?"📁 ":"📄 ")+item.name+"\n"+(item.directory?"Каталог":item.size>0?item.size+" байт":"Файл"),14,ClientUi.NAVY,true);row.setPadding(ClientUi.dp(this,12),ClientUi.dp(this,10),ClientUi.dp(this,12),ClientUi.dp(this,10));row.setBackground(ClientUi.round(this,ClientUi.SURFACE,12,ClientUi.BORDER,1));if(item.directory)row.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){path.setText(item.path);load();}});else row.setOnLongClickListener(new View.OnLongClickListener(){@Override public boolean onLongClick(View v){pendingRemote=item.path;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,item.name);startActivityForResult(i,CREATE_DOWNLOAD);return true;}});list.addView(row,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,4,0,4));}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;final Uri uri=data.getData();if(req==PICK_UPLOAD){new Thread(new Runnable(){@Override public void run(){try{InputStream in=getContentResolver().openInputStream(uri);String name=uri.getLastPathSegment();if(name==null)name="upload.bin";int slash=name.lastIndexOf('/');if(slash>=0)name=name.substring(slash+1);String remote=join(path.getText().toString(),name);if("WEBDAV".equals(endpoint.protocol))WebDav.put(endpoint,user.getText().toString(),password.getText().toString(),remote,in);else{if(ftp==null){ftp=new FtpSession(endpoint,user.getText().toString(),password.getText().toString());ftp.connect();}ftp.upload(remote,in);}in.close();runOnUiThread(new Runnable(){@Override public void run(){Toast.makeText(NativeFileClientActivity.this,"Файл загружен",Toast.LENGTH_SHORT).show();load();}});}catch(final Exception e){error(e);}}}).start();}else if(req==CREATE_DOWNLOAD){new Thread(new Runnable(){@Override public void run(){try{OutputStream out=getContentResolver().openOutputStream(uri);if("WEBDAV".equals(endpoint.protocol))WebDav.get(endpoint,user.getText().toString(),password.getText().toString(),pendingRemote,out);else{if(ftp==null){ftp=new FtpSession(endpoint,user.getText().toString(),password.getText().toString());ftp.connect();}ftp.download(pendingRemote,out);}out.close();runOnUiThread(new Runnable(){@Override public void run(){Toast.makeText(NativeFileClientActivity.this,"Файл сохранён",Toast.LENGTH_SHORT).show();}});}catch(final Exception e){error(e);}}}).start();}}
    private void error(final Exception e){runOnUiThread(new Runnable(){@Override public void run(){progress.setVisibility(View.GONE);status.setText("Ошибка: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}});}
    private static String join(String p,String n){if(p.endsWith("/"))return p+n;return p+"/"+n;}
    @Override protected void onDestroy(){if(ftp!=null)ftp.close();super.onDestroy();}

    private static final class RemoteItem{String name,path;boolean directory;long size;}

    private static final class FtpSession{
        final ServiceEndpoint e;final String user,pass;Socket control;BufferedInputStream in;BufferedOutputStream out;boolean tls;
        FtpSession(ServiceEndpoint e,String u,String p){this.e=e;user=u.length()==0?"anonymous":u;pass=p.length()==0?"sitescope@localhost":p;tls="FTPS".equals(e.protocol);}
        void connect()throws Exception{int port=e.port>0?e.port:21;boolean implicit=tls&&port==990;if(implicit){SSLSocket ssl=pro.sitescope.app.proto.TlsSockets.INSTANCE.connect(e.host,port,15000);control=ssl;}else{control=new Socket();control.connect(new InetSocketAddress(e.host,port),10000);}control.setSoTimeout(15000);streams(control);expect(220);if(tls&&!implicit){send("AUTH TLS");expect(234);SSLSocket ssl=pro.sitescope.app.proto.TlsSockets.INSTANCE.upgrade(control,e.host,port,15000);control=ssl;streams(control);}send("USER "+user);int code=readReply();if(code==331){send("PASS "+pass);expect(230);}else if(code!=230)throw new Exception("FTP USER: "+code);send("TYPE I");expect(200);if(tls){send("PBSZ 0");expect(200);send("PROT P");expect(200);}}
        void streams(Socket s)throws Exception{in=new BufferedInputStream(s.getInputStream());out=new BufferedOutputStream(s.getOutputStream());}
        List<RemoteItem> list(String path)throws Exception{Socket data=passive();send("MLSD "+path);int code=readReply();boolean mlsd=code==150||code==125;if(!mlsd){data.close();data=passive();send("LIST "+path);code=readReply();if(code!=150&&code!=125)throw new Exception("FTP LIST: "+code);}byte[] raw=readAll(data.getInputStream());data.close();readReply();List<RemoteItem> result=new ArrayList<RemoteItem>();String[] lines=new String(raw,StandardCharsets.UTF_8).split("\\r?\\n");for(String line:lines){if(line.trim().length()==0)continue;RemoteItem i=new RemoteItem();if(mlsd){String[] split=line.split(" ",2);if(split.length<2)continue;i.name=split[1].trim();String facts=split[0].toLowerCase();i.directory=facts.contains("type=dir");int s=facts.indexOf("size=");if(s>=0){int end=facts.indexOf(';',s);try{i.size=Long.parseLong(facts.substring(s+5,end));}catch(Exception ignored){}}}else{String[] parts=line.trim().split("\\s+",9);if(parts.length<9)continue;i.name=parts[8];i.directory=line.startsWith("d");try{i.size=Long.parseLong(parts[4]);}catch(Exception ignored){}}i.path=join(path,i.name);result.add(i);}return result;}
        void download(String remote,OutputStream target)throws Exception{Socket data=passive();send("RETR "+remote);int c=readReply();if(c!=150&&c!=125)throw new Exception("FTP RETR: "+c);copy(data.getInputStream(),target);data.close();readReply();}
        void upload(String remote,InputStream source)throws Exception{Socket data=passive();send("STOR "+remote);int c=readReply();if(c!=150&&c!=125)throw new Exception("FTP STOR: "+c);copy(source,data.getOutputStream());data.getOutputStream().flush();data.close();readReply();}
        Socket passive()throws Exception{send("EPSV");int code=readReply();int p=0;if(code==229){String r=last;int a=r.lastIndexOf("|||");int b=r.indexOf('|',a+3);p=Integer.parseInt(r.substring(a+3,b));}else{send("PASV");expect(227);int a=last.indexOf('('),b=last.indexOf(')',a);String[] x=last.substring(a+1,b).split(",");p=Integer.parseInt(x[4])*256+Integer.parseInt(x[5]);}Socket data=new Socket();data.connect(new InetSocketAddress(e.host,p),10000);data.setSoTimeout(20000);if(tls){return pro.sitescope.app.proto.TlsSockets.INSTANCE.upgrade(data,e.host,p,20000);}return data;}
        String last="";void send(String s)throws Exception{out.write((s+"\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();}void expect(int wanted)throws Exception{int c=readReply();if(c!=wanted)throw new Exception("FTP: ожидался "+wanted+", получен "+c+" · "+last);}int readReply()throws Exception{StringBuilder all=new StringBuilder();String first=readLine(in);if(first==null)throw new Exception("FTP соединение закрыто");all.append(first);int code=Integer.parseInt(first.substring(0,3));if(first.length()>3&&first.charAt(3)=='-'){String end=first.substring(0,3)+" ";String line;while((line=readLine(in))!=null){all.append('\n').append(line);if(line.startsWith(end))break;}}last=all.toString();return code;}void close(){try{if(out!=null){send("QUIT");}}catch(Exception ignored){}try{if(control!=null)control.close();}catch(Exception ignored){}}
    }

    private static final class WebDav{
        static List<RemoteItem> list(ServiceEndpoint e,String user,String pass,String path)throws Exception{String body="<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>";Response r=request(e,user,pass,"PROPFIND",path,"Depth: 1\r\nContent-Type: application/xml\r\n",body.getBytes(StandardCharsets.UTF_8));if(r.code<200||r.code>=300)throw new Exception("WebDAV HTTP "+r.code);return parse(r.body,path);}
        static void get(ServiceEndpoint e,String u,String p,String path,OutputStream out)throws Exception{Response r=request(e,u,p,"GET",path,"",new byte[0]);if(r.code<200||r.code>=300)throw new Exception("WebDAV HTTP "+r.code);out.write(r.body);}
        static void put(ServiceEndpoint e,String u,String p,String path,InputStream in)throws Exception{Response r=request(e,u,p,"PUT",path,"Content-Type: application/octet-stream\r\n",readAll(in));if(r.code<200||r.code>=300)throw new Exception("WebDAV HTTP "+r.code);}
        static List<RemoteItem> parse(byte[] xml,String base)throws Exception{List<RemoteItem> out=new ArrayList<RemoteItem>();XmlPullParser p=XmlPullParserFactory.newInstance().newPullParser();p.setInput(new ByteArrayInputStream(xml),"UTF-8");RemoteItem cur=null;String tag="";int ev=p.getEventType();while(ev!=XmlPullParser.END_DOCUMENT){if(ev==XmlPullParser.START_TAG){tag=local(p.getName());if("response".equals(tag))cur=new RemoteItem();if(cur!=null&&"collection".equals(tag))cur.directory=true;}else if(ev==XmlPullParser.TEXT&&cur!=null){String v=p.getText().trim();if(v.length()>0){if("href".equals(tag))cur.path=URLDecoder.decode(v,"UTF-8");else if("displayname".equals(tag))cur.name=v;else if("getcontentlength".equals(tag))try{cur.size=Long.parseLong(v);}catch(Exception ignored){}}}else if(ev==XmlPullParser.END_TAG&&"response".equals(local(p.getName()))&&cur!=null){if(cur.name==null||cur.name.length()==0){String x=cur.path;while(x.endsWith("/")&&x.length()>1)x=x.substring(0,x.length()-1);int slash=x.lastIndexOf('/');cur.name=slash>=0?x.substring(slash+1):x;}if(cur.path!=null&&!normalize(cur.path).equals(normalize(base)))out.add(cur);cur=null;}ev=p.next();}return out;}
        static String normalize(String x){if(x==null)return"";while(x.endsWith("/")&&x.length()>1)x=x.substring(0,x.length()-1);return x;}
        static Response request(ServiceEndpoint e,String user,String pass,String method,String path,String headers,byte[] body)throws Exception{boolean tls="HTTPS".equals(e.protocol)||e.port==443||e.port==8443;int port=e.port>0?e.port:(tls?443:80);Socket s;if(tls){s=pro.sitescope.app.proto.TlsSockets.INSTANCE.connect(e.host,port,20000);}else{s=new Socket();s.connect(new InetSocketAddress(e.host,port),10000);}s.setSoTimeout(20000);OutputStream out=new BufferedOutputStream(s.getOutputStream());String auth=user.length()>0?"Authorization: Basic "+Base64.encodeToString((user+":"+pass).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)+"\r\n":"";String target=path.length()==0?"/":path;if(!target.startsWith("/"))target="/"+target;String h=method+" "+target+" HTTP/1.1\r\nHost: "+e.host+":"+port+"\r\nUser-Agent: SiteScopePro/0.23\r\nConnection: close\r\n"+auth+headers+"Content-Length: "+body.length+"\r\n\r\n";out.write(h.getBytes(StandardCharsets.UTF_8));out.write(body);out.flush();InputStream in=new BufferedInputStream(s.getInputStream());String status=readLine(in);if(status==null)throw new Exception("WebDAV: пустой ответ");Response r=new Response();String[] sp=status.split(" ");r.code=Integer.parseInt(sp[1]);boolean chunked=false;int length=-1;String line;while((line=readLine(in))!=null&&line.length()>0){String lower=line.toLowerCase();if(lower.startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());if(lower.startsWith("transfer-encoding:")&&lower.contains("chunked"))chunked=true;}if(chunked)r.body=readChunked(in);else if(length>=0)r.body=readFixed(in,length);else r.body=readAll(in);s.close();return r;}
        static String local(String n){int c=n.indexOf(':');return c>=0?n.substring(c+1):n;}static final class Response{int code;byte[] body;}
    }

    private static String readLine(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);}if(c==-1&&b.size()==0)return null;return b.toString("UTF-8");}
    private static byte[] readAll(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();copy(in,b);return b.toByteArray();}
    private static byte[] readFixed(InputStream in,int n)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[8192];while(n>0){int r=in.read(x,0,Math.min(x.length,n));if(r<0)break;b.write(x,0,r);n-=r;}return b.toByteArray();}
    private static byte[] readChunked(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();while(true){String line=readLine(in);if(line==null)break;int sem=line.indexOf(';');if(sem>=0)line=line.substring(0,sem);int n=Integer.parseInt(line.trim(),16);if(n==0)break;b.write(readFixed(in,n));readLine(in);}return b.toByteArray();}
    private static void copy(InputStream in,OutputStream out)throws Exception{byte[] x=new byte[8192];int n;while((n=in.read(x))>=0)out.write(x,0,n);}
}
