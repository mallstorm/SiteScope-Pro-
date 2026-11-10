package pro.sitescope.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Native read/write RFB 3.8 viewer supporting None and classic VNC authentication, raw encoding and touch pointer. */
public final class VncClientActivity extends Activity {
    private ServiceEndpoint endpoint;
    private EditText password,textInput;
    private TextView status;
    private VncSurface surface;
    private VncSession session;
    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.BLACK);endpoint=ServiceEndpoint.from(getIntent());build();}
    private void build(){LinearLayout root=ClientUi.root(this);root.addView(ClientUi.header(this,"VNC Viewer","Нативный RFB-клиент · raw framebuffer"));LinearLayout auth=ClientUi.card(this,12);password=ClientUi.input(this,"VNC пароль (если требуется)");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);LinearLayout row=ClientUi.row(this);TextView connect=ClientUi.button(this,"Подключиться",true),disconnect=ClientUi.button(this,"Отключить",false);row.addView(password,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));row.addView(space(6));row.addView(connect,new LinearLayout.LayoutParams(ClientUi.dp(this,115),ClientUi.dp(this,46)));row.addView(space(6));row.addView(disconnect,new LinearLayout.LayoutParams(ClientUi.dp(this,105),ClientUi.dp(this,46)));auth.addView(row);status=ClientUi.text(this,"Касание управляет указателем. Проверяйте, что подключаетесь к разрешённому серверу.",12,ClientUi.MUTED,false);auth.addView(status,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,7,0,0));root.addView(auth,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,10,0,10,0));surface=new VncSurface();surface.setBackgroundColor(Color.BLACK);root.addView(surface,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));LinearLayout keys=ClientUi.row(this);keys.setPadding(ClientUi.dp(this,10),ClientUi.dp(this,6),ClientUi.dp(this,10),ClientUi.dp(this,10));textInput=ClientUi.input(this,"Текст / клавиша");TextView send=ClientUi.button(this,"Отправить",true),ctrlAltDel=ClientUi.button(this,"Ctrl+Alt+Del",false);keys.addView(textInput,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));keys.addView(space(6));keys.addView(send,new LinearLayout.LayoutParams(ClientUi.dp(this,95),ClientUi.dp(this,46)));keys.addView(space(6));keys.addView(ctrlAltDel,new LinearLayout.LayoutParams(ClientUi.dp(this,120),ClientUi.dp(this,46)));root.addView(keys);connect.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){connect();}});disconnect.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){close();}});send.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){if(session!=null)session.sendText(textInput.getText().toString());textInput.setText("");}});ctrlAltDel.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){if(session!=null)session.ctrlAltDel();}});setContentView(root);}
    private View space(int d){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(ClientUi.dp(this,d),1));return v;}
    private void connect(){close();status.setText("Подключение…");session=new VncSession();new Thread(session).start();}
    private void close(){if(session!=null)session.close();session=null;status.setText("Отключено");}
    @Override protected void onDestroy(){close();super.onDestroy();}
    private void ui(final String s){runOnUiThread(new Runnable(){@Override public void run(){status.setText(s);}});}

    private final class VncSurface extends View{
        Bitmap bitmap;int fbw,fhh;Paint p=new Paint(Paint.FILTER_BITMAP_FLAG);Rect dst=new Rect();VncSurface(){super(VncClientActivity.this);setFocusable(true);}
        synchronized void init(int w,int h){fbw=w;fhh=h;bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);postInvalidate();}
        synchronized void update(int x,int y,int w,int h,int[] pixels){if(bitmap==null)return;bitmap.setPixels(pixels,0,w,x,y,w,h);postInvalidate();}
        @Override protected synchronized void onDraw(Canvas c){super.onDraw(c);if(bitmap==null)return;float scale=Math.min((float)getWidth()/fbw,(float)getHeight()/fhh);int w=Math.round(fbw*scale),h=Math.round(fhh*scale),l=(getWidth()-w)/2,t=(getHeight()-h)/2;dst.set(l,t,l+w,t+h);c.drawBitmap(bitmap,null,dst,p);}
        @Override public boolean onTouchEvent(MotionEvent e){if(session==null||bitmap==null)return true;float scale=Math.min((float)getWidth()/fbw,(float)getHeight()/fhh);int l=(getWidth()-Math.round(fbw*scale))/2,t=(getHeight()-Math.round(fhh*scale))/2;int x=Math.max(0,Math.min(fbw-1,Math.round((e.getX()-l)/scale)));int y=Math.max(0,Math.min(fhh-1,Math.round((e.getY()-t)/scale)));int mask=(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE)?1:0;session.pointer(mask,x,y);return true;}
    }

    private final class VncSession implements Runnable{
        Socket socket;InputStream in;OutputStream out;volatile boolean active;int width,height;
        public void run(){try{socket=new Socket();socket.connect(new InetSocketAddress(endpoint.host,endpoint.port>0?endpoint.port:5900),10000);socket.setSoTimeout(20000);in=new BufferedInputStream(socket.getInputStream());out=new BufferedOutputStream(socket.getOutputStream());byte[] version=read(12);String server=new String(version,StandardCharsets.US_ASCII);if(!server.startsWith("RFB "))throw new Exception("Не RFB сервер");out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));out.flush();int count=in.read();if(count<=0){int n=readInt();throw new Exception(new String(read(n),StandardCharsets.UTF_8));}byte[] types=read(count);int selected=0;for(byte t:types)if((t&255)==1)selected=1;if(selected==0)for(byte t:types)if((t&255)==2)selected=2;if(selected==0)throw new Exception("Поддерживаются только None и VNC Authentication");out.write(selected);out.flush();if(selected==2){byte[] challenge=read(16);byte[] response=vncAuth(password.getText().toString(),challenge);out.write(response);out.flush();}int result=readInt();if(result!=0){String reason="Authentication failed";try{reason=new String(read(readInt()),StandardCharsets.UTF_8);}catch(Exception ignored){}throw new Exception(reason);}out.write(1);out.flush();width=readU16();height=readU16();read(16);String name=new String(read(readInt()),StandardCharsets.UTF_8);setPixelFormat();setEncodings();active=true;runOnUiThread(new Runnable(){@Override public void run(){surface.init(width,height);status.setText("VNC подключён · "+width+"×"+height+" · "+name);}});request(false);socket.setSoTimeout(0);while(active){int type=in.read();if(type<0)break;if(type==0)framebufferUpdate();else if(type==2)ui("VNC bell");else if(type==3){read(3);int n=readInt();String text=new String(read(n),StandardCharsets.UTF_8);ui("Clipboard: "+text);}else throw new Exception("RFB message "+type);}}catch(final Exception e){if(active||session==this)ui("VNC ошибка: "+e.getMessage());}finally{close();}}
        void setPixelFormat()throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(0);b.write(new byte[3]);b.write(32);b.write(24);b.write(0);b.write(1);writeU16(b,255);writeU16(b,255);writeU16(b,255);b.write(16);b.write(8);b.write(0);b.write(new byte[3]);send(b.toByteArray());}
        void setEncodings()throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(2);b.write(0);writeU16(b,2);writeInt(b,0);writeInt(b,-223);send(b.toByteArray());}
        void request(boolean incremental)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(3);b.write(incremental?1:0);writeU16(b,0);writeU16(b,0);writeU16(b,width);writeU16(b,height);send(b.toByteArray());}
        void framebufferUpdate()throws Exception{in.read();int rects=readU16();for(int r=0;r<rects;r++){int x=readU16(),y=readU16(),w=readU16(),h=readU16(),enc=readInt();if(enc==0){byte[] raw=read(w*h*4);int[] pixels=new int[w*h];for(int i=0,p=0;i<pixels.length;i++,p+=4){int blue=raw[p]&255,green=raw[p+1]&255,red=raw[p+2]&255;pixels[i]=0xFF000000|(red<<16)|(green<<8)|blue;}surface.update(x,y,w,h,pixels);}else if(enc==-223){width=w;height=h;runOnUiThread(new Runnable(){@Override public void run(){surface.init(width,height);}});}else throw new Exception("Неподдерживаемое VNC encoding "+enc);}request(true);}
        synchronized void pointer(int mask,int x,int y){try{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(5);b.write(mask);writeU16(b,x);writeU16(b,y);send(b.toByteArray());}catch(Exception ignored){}}
        synchronized void key(int keysym,boolean down){try{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(4);b.write(down?1:0);b.write(0);b.write(0);writeInt(b,keysym);send(b.toByteArray());}catch(Exception ignored){}}
        void sendText(String text){for(int i=0;i<text.length();i++){int c=text.charAt(i);key(c,true);key(c,false);}key(0xFF0D,true);key(0xFF0D,false);}
        void ctrlAltDel(){int[] k={0xFFE3,0xFFE9,0xFFFF};for(int x:k)key(x,true);for(int i=k.length-1;i>=0;i--)key(k[i],false);}
        synchronized void send(byte[] b)throws Exception{out.write(b);out.flush();}
        int readU16()throws Exception{byte[] b=read(2);return((b[0]&255)<<8)|(b[1]&255);}int readInt()throws Exception{byte[] b=read(4);return((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);}byte[] read(int n)throws Exception{byte[] b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new Exception("EOF");p+=r;}return b;}
        void close(){active=false;try{if(socket!=null)socket.close();}catch(Exception ignored){}}
    }
    private static byte[] vncAuth(String password,byte[] challenge)throws Exception{byte[] key=new byte[8];byte[] p=password.getBytes(StandardCharsets.ISO_8859_1);for(int i=0;i<8;i++)key[i]=reverseBits(i<p.length?p[i]:0);Cipher c=Cipher.getInstance("DES/ECB/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"DES"));return c.doFinal(challenge);}private static byte reverseBits(byte b){int x=b&255,r=0;for(int i=0;i<8;i++){r=(r<<1)|(x&1);x>>=1;}return(byte)r;}
    private static void writeU16(OutputStream o,int v)throws Exception{o.write((v>>8)&255);o.write(v&255);}private static void writeInt(OutputStream o,int v)throws Exception{o.write((v>>24)&255);o.write((v>>16)&255);o.write((v>>8)&255);o.write(v&255);}
}
