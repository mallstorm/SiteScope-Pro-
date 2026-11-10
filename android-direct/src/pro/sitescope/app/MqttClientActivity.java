package pro.sitescope.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal native MQTT 3.1.1 client for explicit connect, subscribe and publish actions. */
public final class MqttClientActivity extends Activity {
    private ServiceEndpoint endpoint;
    private EditText clientId,user,password,topic,payload;
    private CheckBox tls;
    private TextView status,log;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean running;
    private final AtomicInteger packetId=new AtomicInteger(1);

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);endpoint=ServiceEndpoint.from(getIntent());build();}
    private void build(){LinearLayout root=ClientUi.root(this);root.addView(ClientUi.header(this,"MQTT Client","Ручное подключение, подписка и публикация"));LinearLayout page=ClientUi.page(this);ScrollView sc=new ScrollView(this);sc.addView(page);root.addView(sc,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));LinearLayout c=ClientUi.card(this,15);c.addView(ClientUi.text(this,endpoint.host+":"+(endpoint.port>0?endpoint.port:1883),17,ClientUi.NAVY,true));clientId=ClientUi.input(this,"Client ID");clientId.setText("SiteScope-"+Long.toHexString(System.currentTimeMillis()));user=ClientUi.input(this,"Пользователь (необязательно)");password=ClientUi.input(this,"Пароль");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);topic=ClientUi.input(this,"Топик");topic.setText("#");payload=ClientUi.input(this,"Сообщение для публикации");tls=new CheckBox(this);tls.setText("TLS");tls.setTextColor(ClientUi.NAVY);tls.setChecked(endpoint.port==8883||endpoint.port==8884);c.addView(clientId,lp(46,10));c.addView(user,lp(46,8));c.addView(password,lp(46,8));c.addView(topic,lp(46,8));c.addView(payload,lp(46,8));c.addView(tls);LinearLayout r=ClientUi.row(this);TextView connect=ClientUi.button(this,"Подключиться",true),subscribe=ClientUi.button(this,"Подписаться",false),publish=ClientUi.button(this,"Отправить",false);r.addView(connect,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));r.addView(space(6));r.addView(subscribe,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));r.addView(space(6));r.addView(publish,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));c.addView(r,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,10,0,0));status=ClientUi.text(this,"Публикация выполняется только по нажатию. Пароли не сохраняются.",12,ClientUi.MUTED,false);c.addView(status);page.addView(c);log=ClientUi.text(this,"",12,ClientUi.NAVY,false);log.setTypeface(android.graphics.Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setPadding(ClientUi.dp(this,12),ClientUi.dp(this,12),ClientUi.dp(this,12),ClientUi.dp(this,12));log.setBackground(ClientUi.round(this,Color.rgb(246,248,252),12,ClientUi.BORDER,1));page.addView(log,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));connect.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){connect();}});subscribe.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){subscribe();}});publish.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){publish();}});setContentView(root);}
    private LinearLayout.LayoutParams lp(int h,int top){return ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,h),0,top,0,0);}private View space(int d){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(ClientUi.dp(this,d),1));return v;}
    private void connect(){disconnect();status.setText("Подключение…");new Thread(new Runnable(){@Override public void run(){try{int port=endpoint.port>0?endpoint.port:(tls.isChecked()?8883:1883);Socket s=tls.isChecked()?((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket():new Socket();s.connect(new InetSocketAddress(endpoint.host,port),10000);if(s instanceof SSLSocket)((SSLSocket)s).startHandshake();s.setSoTimeout(0);socket=s;in=new BufferedInputStream(s.getInputStream());out=new BufferedOutputStream(s.getOutputStream());send(connectPacket());int type=in.read();if(type!=0x20)throw new Exception("Ожидался CONNACK");int len=readRemaining(in);byte[] body=readExact(in,len);if(body.length<2||body[1]!=0)throw new Exception("MQTT CONNACK code "+(body.length>1?(body[1]&255):-1));running=true;ui("Подключено","> CONNECT OK\n");new Thread(new Reader()).start();}catch(final Exception e){ui("Ошибка: "+e.getMessage(),"> ERROR "+e.getMessage()+"\n");}}}).start();}
    private byte[] connectPacket()throws Exception{ByteArrayOutputStream v=new ByteArrayOutputStream();writeUtf(v,"MQTT");v.write(4);int flags=2;if(user.getText().toString().length()>0)flags|=0x80;if(password.getText().toString().length()>0)flags|=0x40;v.write(flags);v.write(0);v.write(30);writeUtf(v,clientId.getText().toString());if((flags&0x80)!=0)writeUtf(v,user.getText().toString());if((flags&0x40)!=0)writeUtf(v,password.getText().toString());return packet(0x10,v.toByteArray());}
    private void subscribe(){if(!running){status.setText("Сначала подключитесь");return;}try{ByteArrayOutputStream b=new ByteArrayOutputStream();int id=nextId();b.write(id>>8);b.write(id);writeUtf(b,topic.getText().toString());b.write(0);send(packet(0x82,b.toByteArray()));append("> SUBSCRIBE "+topic.getText()+"\n");}catch(Exception e){status.setText(e.getMessage());}}
    private void publish(){if(!running){status.setText("Сначала подключитесь");return;}try{ByteArrayOutputStream b=new ByteArrayOutputStream();writeUtf(b,topic.getText().toString());b.write(payload.getText().toString().getBytes(StandardCharsets.UTF_8));send(packet(0x30,b.toByteArray()));append("> PUBLISH "+topic.getText()+" · "+payload.getText()+"\n");}catch(Exception e){status.setText(e.getMessage());}}
    private synchronized void send(byte[] p)throws Exception{if(out==null)throw new Exception("Нет соединения");out.write(p);out.flush();}
    private final class Reader implements Runnable{public void run(){try{while(running){int h=in.read();if(h<0)break;int len=readRemaining(in);byte[] body=readExact(in,len);int type=h>>4;if(type==3){int tlen=((body[0]&255)<<8)|(body[1]&255);String t=new String(body,2,tlen,StandardCharsets.UTF_8);int pos=2+tlen;int qos=(h>>1)&3;if(qos>0)pos+=2;String msg=new String(body,pos,body.length-pos,StandardCharsets.UTF_8);append("< "+t+" · "+msg+"\n");}else if(type==9)append("< SUBACK\n");else append("< PACKET type="+type+" len="+len+"\n");}}catch(Exception e){if(running)ui("Соединение закрыто: "+e.getMessage(),"< DISCONNECT\n");}finally{disconnect();}}}
    private void ui(final String s,final String l){runOnUiThread(new Runnable(){@Override public void run(){status.setText(s);append(l);}});}private void append(String x){String c=log.getText().toString();if(c.length()>100000)c=c.substring(c.length()-70000);log.setText(c+x);}
    private int nextId(){int id=packetId.incrementAndGet();if(id>65535){packetId.set(1);id=1;}return id;}
    private static byte[] packet(int header,byte[] body)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(header);int x=body.length;do{int d=x%128;x/=128;if(x>0)d|=128;b.write(d);}while(x>0);b.write(body);return b.toByteArray();}
    private static void writeUtf(OutputStream o,String s)throws Exception{byte[] x=s.getBytes(StandardCharsets.UTF_8);o.write(x.length>>8);o.write(x.length);o.write(x);}private static int readRemaining(InputStream in)throws Exception{int m=1,v=0,d;do{d=in.read();if(d<0)throw new Exception("EOF");v+=(d&127)*m;m*=128;if(m>128*128*128*128)throw new Exception("MQTT length");}while((d&128)!=0);return v;}private static byte[] readExact(InputStream in,int n)throws Exception{byte[] x=new byte[n];int p=0;while(p<n){int r=in.read(x,p,n-p);if(r<0)throw new Exception("EOF");p+=r;}return x;}
    private void disconnect(){running=false;try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;in=null;out=null;}
    @Override protected void onDestroy(){disconnect();super.onDestroy();}
}
