package pro.sitescope.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only SNMP v1/v2c GET and bounded WALK client. */
public final class SnmpClientActivity extends Activity {
    private ServiceEndpoint endpoint;
    private EditText community,oid;
    private TextView output,status;
    private final AtomicInteger requestId=new AtomicInteger(1000);
    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);endpoint=ServiceEndpoint.from(getIntent());build();}
    private void build(){LinearLayout root=ClientUi.root(this);root.addView(ClientUi.header(this,"SNMP Browser","Read-only GET/WALK без изменения устройства"));LinearLayout page=ClientUi.page(this);ScrollView s=new ScrollView(this);s.addView(page);root.addView(s,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));LinearLayout card=ClientUi.card(this,15);card.addView(ClientUi.text(this,endpoint.host+":"+(endpoint.port>0?endpoint.port:161),17,ClientUi.NAVY,true));community=ClientUi.input(this,"Community");community.setText("public");oid=ClientUi.input(this,"OID");oid.setText("1.3.6.1.2.1.1");card.addView(community,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,46),0,10,0,0));card.addView(oid,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,46),0,8,0,0));LinearLayout r=ClientUi.row(this);TextView get=ClientUi.button(this,"GET",true),walk=ClientUi.button(this,"WALK",false);r.addView(get,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));r.addView(space(8));r.addView(walk,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));card.addView(r,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,9,0,0));status=ClientUi.text(this,"Community не сохраняется. WALK ограничен 200 объектами.",12,ClientUi.MUTED,false);card.addView(status);page.addView(card);output=ClientUi.text(this,"",12,ClientUi.NAVY,false);output.setTypeface(android.graphics.Typeface.MONOSPACE);output.setTextIsSelectable(true);output.setPadding(ClientUi.dp(this,12),ClientUi.dp(this,12),ClientUi.dp(this,12),ClientUi.dp(this,12));output.setBackground(ClientUi.round(this,Color.rgb(246,248,252),12,ClientUi.BORDER,1));page.addView(output,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));get.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){run(false);}});walk.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){run(true);}});setContentView(root);}
    private View space(int d){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(ClientUi.dp(this,d),1));return v;}
    private void run(final boolean walk){status.setText(walk?"Выполняется WALK…":"Выполняется GET…");output.setText("");new Thread(new Runnable(){@Override public void run(){try{final List<VarBind> list=new ArrayList<VarBind>();String base=normalize(oid.getText().toString());String current=base;for(int i=0;i<(walk?200:1);i++){VarBind vb=query(current,walk);if(vb==null)break;if(walk&&!startsWithOid(vb.oid,base))break;list.add(vb);if(!walk)break;if(vb.oid.equals(current))break;current=vb.oid;}runOnUiThread(new Runnable(){@Override public void run(){StringBuilder b=new StringBuilder();for(VarBind v:list)b.append(v.oid).append(" = ").append(v.value).append('\n');output.setText(b.length()==0?"Ответ не содержит VarBind":b.toString());status.setText("Получено объектов: "+list.size());}});}catch(final Exception e){runOnUiThread(new Runnable(){@Override public void run(){status.setText("Ошибка: "+e.getMessage());}});}}}).start();}
    private VarBind query(String target,boolean next)throws Exception{int id=requestId.incrementAndGet();byte[] request=SnmpCodec.message(1,community.getText().toString(),next?0xA1:0xA0,id,target);DatagramSocket socket=new DatagramSocket();socket.setSoTimeout(5000);DatagramPacket p=new DatagramPacket(request,request.length,InetAddress.getByName(endpoint.host),endpoint.port>0?endpoint.port:161);socket.send(p);byte[] buf=new byte[65535];DatagramPacket reply=new DatagramPacket(buf,buf.length);socket.receive(reply);socket.close();return SnmpCodec.parse(buf,reply.getLength(),id);}
    private static String normalize(String x){x=x.trim();while(x.startsWith("."))x=x.substring(1);return x;}
    private static boolean startsWithOid(String value,String base){return value.equals(base)||value.startsWith(base+".");}
    static final class VarBind{String oid,value;VarBind(String o,String v){oid=o;value=v;}}

    static final class SnmpCodec{
        static byte[] message(int version,String community,int pduType,int id,String oid)throws Exception{byte[] vb=seq(oid(oid),tlv(0x05,new byte[0]));byte[] vbl=seq(vb);byte[] pdu=tlv(pduType,concat(integer(id),integer(0),integer(0),vbl));return seq(integer(version),octet(community.getBytes(StandardCharsets.UTF_8)),pdu);}
        static VarBind parse(byte[] data,int length,int wanted)throws Exception{Reader r=new Reader(data,length);r.enter(0x30);r.readInt();r.readBytes(0x04);int pdu=r.peek();if(pdu!=0xA2)throw new Exception("SNMP PDU "+Integer.toHexString(pdu));r.enter(pdu);int id=r.readInt();int err=r.readInt(),idx=r.readInt();if(id!=wanted)throw new Exception("Несовпадающий request-id");if(err!=0)throw new Exception("SNMP error "+err+" index "+idx);r.enter(0x30);if(!r.has())return null;r.enter(0x30);String oid=r.readOid();int tag=r.peek();String value=r.readValue(tag);return new VarBind(oid,value);}
        static byte[] integer(long v){ByteArrayOutputStream b=new ByteArrayOutputStream();long x=v;do{b.write((byte)(x&255));x>>=8;}while(x!=0&&x!=-1);byte[] rev=b.toByteArray();byte[] val=new byte[rev.length];for(int i=0;i<rev.length;i++)val[i]=rev[rev.length-1-i];if(v>=0&&(val[0]&0x80)!=0)val=concat(new byte[]{0},val);return tlv(0x02,val);}
        static byte[] octet(byte[] v){return tlv(0x04,v);}static byte[] seq(byte[]...v){return tlv(0x30,concat(v));}
        static byte[] oid(String text)throws Exception{String[] p=text.split("\\.");if(p.length<2)throw new Exception("Некорректный OID");ByteArrayOutputStream b=new ByteArrayOutputStream();long first=Long.parseLong(p[0]),second=Long.parseLong(p[1]);writeBase128(b,first*40+second);for(int i=2;i<p.length;i++)writeBase128(b,Long.parseLong(p[i]));return tlv(0x06,b.toByteArray());}
        static void writeBase128(ByteArrayOutputStream b,long v){byte[] tmp=new byte[10];int n=0;tmp[n++]=(byte)(v&0x7F);while((v>>=7)>0)tmp[n++]=(byte)((v&0x7F)|0x80);for(int i=n-1;i>=0;i--)b.write(tmp[i]);}
        static byte[] tlv(int tag,byte[] value){return concat(new byte[]{(byte)tag},len(value.length),value);}static byte[] len(int n){if(n<128)return new byte[]{(byte)n};int bytes=n>0xFFFFFF?4:n>0xFFFF?3:n>0xFF?2:1;byte[] out=new byte[1+bytes];out[0]=(byte)(0x80|bytes);for(int i=bytes;i>0;i--){out[i]=(byte)(n&255);n>>=8;}return out;}
        static byte[] concat(byte[]...arr){int n=0;for(byte[] a:arr)n+=a.length;byte[] o=new byte[n];int p=0;for(byte[] a:arr){System.arraycopy(a,0,o,p,a.length);p+=a.length;}return o;}
        static final class Reader{byte[] d;int p,end;Reader(byte[] x,int n){d=x;end=n;}int peek(){return d[p]&255;}boolean has(){return p<end;}void enter(int tag)throws Exception{int t=read();if(t!=tag)throw new Exception("BER tag "+t+" != "+tag);int n=readLen();end=p+n;}int read(){return d[p++]&255;}int readLen(){int n=read();if((n&0x80)==0)return n;int c=n&0x7F;n=0;for(int i=0;i<c;i++)n=(n<<8)|read();return n;}int readInt()throws Exception{int tag=read();if(tag!=0x02)throw new Exception("BER integer");int n=readLen();int v=0;for(int i=0;i<n;i++)v=(v<<8)|read();return v;}byte[] readBytes(int wanted)throws Exception{int t=read();if(t!=wanted)throw new Exception("BER bytes");int n=readLen();byte[] x=new byte[n];System.arraycopy(d,p,x,0,n);p+=n;return x;}String readOid()throws Exception{byte[] x=readBytes(0x06);List<Long> vals=new ArrayList<Long>();long v=0;for(byte b:x){v=(v<<7)|(b&0x7F);if((b&0x80)==0){vals.add(v);v=0;}}if(vals.isEmpty())return"";long f=vals.remove(0);StringBuilder s=new StringBuilder();s.append(f/40).append('.').append(f%40);for(long z:vals)s.append('.').append(z);return s.toString();}String readValue(int tag)throws Exception{byte[] x=readBytes(tag);if(tag==0x04)return printable(x);if(tag==0x06){Reader rr=new Reader(tlv(0x06,x),tlv(0x06,x).length);return rr.readOid();}if(tag==0x02||tag==0x41||tag==0x42||tag==0x43||tag==0x46){long v=0;for(byte b:x)v=(v<<8)|(b&255);return Long.toString(v);}if(tag==0x40&&x.length==4)return(x[0]&255)+"."+(x[1]&255)+"."+(x[2]&255)+"."+(x[3]&255);if(tag==0x05)return"NULL";return"0x"+hex(x);}String printable(byte[] x){for(byte b:x){int c=b&255;if(c<9||(c>13&&c<32))return"0x"+hex(x);}return new String(x,StandardCharsets.UTF_8);}String hex(byte[] x){StringBuilder b=new StringBuilder();for(byte z:x)b.append(String.format("%02x",z&255));return b.toString();}}
    }
}
