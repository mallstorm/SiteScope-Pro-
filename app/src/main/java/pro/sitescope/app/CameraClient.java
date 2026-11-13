package pro.sitescope.app;

import android.net.Network;
import org.xmlpull.v1.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;

/** Read-only ONVIF Media1/Media2 session. Responses never become exception text. */
public final class CameraClient implements AutoCloseable {
    public static final String DEVICE="http://www.onvif.org/ver10/device/wsdl", MEDIA1="http://www.onvif.org/ver10/media/wsdl", MEDIA2="http://www.onvif.org/ver20/media/wsdl", SCHEMA="http://www.onvif.org/ver10/schema";
    public enum Code { AUTH, TIMEOUT, NETWORK, TLS, CANCELLED, INVALID, UNSUPPORTED, ADDRESS, NETWORK_CHANGED }
    public static final class Failure extends IOException {
        public final Code code;
        Failure(Code code) { super(message(code));this.code=code; }
        private static String message(Code code) {
            switch(code) {
                case AUTH:return "Камера требует авторизацию или отклонила учётные данные. Проверьте логин, пароль и время камеры.";
                case TIMEOUT:return "Камера не ответила за отведённое время.";
                case NETWORK:return "Нет связи с камерой. Проверьте адрес и подключение к сети.";
                case TLS:return "Не удалось проверить защищённое соединение с камерой.";
                case CANCELLED:return "Запрос отменён.";
                case UNSUPPORTED:return "Камера не поддерживает запрошенную операцию ONVIF или RTSP.";
                case NETWORK_CHANGED:return "Сеть изменилась. Опросите камеру заново перед открытием потока.";
                case ADDRESS:return "Камера сообщила другой адрес сервиса. Укажите прямой адрес камеры и повторите запрос.";
                default:return "Камера вернула некорректный или слишком большой ответ ONVIF.";
            }
        }
    }
    public static final class Profile {
        public final String token,name;
        Profile(String token,String name){this.token=token;this.name=name;}
    }
    public static final class Device {
        public final String base,mediaUrl,mediaNamespace;
        public final Network network;
        public final DeviceIdentity identity;
        public final Map<String,String> info;
        public final List<Profile> profiles;
        Device(String base,String media,String ns,Network network,DeviceIdentity identity,Map<String,String> info,List<Profile> profiles){this.network=network;this.base=base;mediaUrl=media;mediaNamespace=ns;this.identity=identity;this.info=Collections.unmodifiableMap(info);this.profiles=Collections.unmodifiableList(profiles);}
    }
    public interface Progress { void phase(String label); }
    private final Network network;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile HttpURLConnection connection;
    private long deadline;
    private final int timeoutMs;
    public static Network selectNetwork(android.content.Context context,ServiceEndpoint endpoint) {
        android.net.ConnectivityManager cm=context.getSystemService(android.net.ConnectivityManager.class);
        if(cm==null)return null;Network active=cm.getActiveNetwork();
        for(Network candidate:cm.getAllNetworks())if(NetworkDiscoveryEngine.isLan(cm.getNetworkCapabilities(candidate))&&endpoint.scope!=null&&endpoint.scope.id.equals(NetworkScope.capture(context,candidate).id))return candidate;
        android.net.NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
        if(caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))return active;
        if(endpoint.host.matches("[0-9.]+")||endpoint.host.contains(":"))try {
            InetAddress target=InetAddress.getByName(endpoint.host);Network selected=null;int prefix=-1;
            for(Network candidate:cm.getAllNetworks())if(NetworkDiscoveryEngine.isLan(cm.getNetworkCapabilities(candidate))){
                android.net.LinkProperties links=cm.getLinkProperties(candidate);if(links==null)continue;
                for(android.net.RouteInfo route:links.getRoutes())if(!route.isDefaultRoute()&&route.matches(target)&&route.getDestination().getPrefixLength()>prefix){selected=candidate;prefix=route.getDestination().getPrefixLength();}
            }
            if(selected!=null)return selected;
        }catch(Exception ignored){}
        return active;
    }
    public CameraClient(Network network){this(network,8000);}
    public CameraClient(Network network,int timeoutMs){this.network=network;this.timeoutMs=Math.max(100,Math.min(timeoutMs,10000));}
    @Override public void close(){cancelled.set(true);HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    private void check() throws Failure {if(cancelled.get()||Thread.currentThread().isInterrupted())throw new Failure(Code.CANCELLED);if(System.nanoTime()>deadline)throw new Failure(Code.TIMEOUT);}
    private void phase(Progress progress,String value)throws Failure{check();if(progress!=null)progress.phase(value);}
    public Device inspect(String address,String user,String password,Progress progress)throws Failure {
        deadline=System.nanoTime()+25000000000L;
        if(user.length()>1024||password.length()>4096)throw new Failure(Code.INVALID);
        String base=http(address).toString();Map<String,String> info=new LinkedHashMap<>();DeviceIdentity identity=new DeviceIdentity();
        phase(progress,"Сведения об устройстве…");
        try {
            String xml=soap(base,DEVICE,"GetDeviceInformation","",user,password);
            Node root=response(xml,DEVICE,"GetDeviceInformation");identity=DeviceFingerprintEngine.fromOnvifXml(xml);
            for(String[] f:new String[][]{{"Manufacturer","Производитель"},{"Model","Модель"},{"FirmwareVersion","Прошивка"},{"SerialNumber","Серийный номер"},{"HardwareId","Hardware ID"}}){String v=root.text(DEVICE,f[0]);if(!v.isEmpty())info.put(f[1],v);}
        }catch(Failure e){if(e.code!=Code.UNSUPPORTED)throw e;}
        phase(progress,"Поиск ONVIF Media…");String media="",ns=MEDIA1;
        try {
            Node caps=response(soap(base,DEVICE,"GetCapabilities","<d:Category>All</d:Category>",user,password),DEVICE,"GetCapabilities");
            Node cap=caps.child(DEVICE,"Capabilities"),m=cap==null?null:cap.child(SCHEMA,"Media");if(m!=null)media=m.xaddr.isEmpty()?m.text(SCHEMA,"XAddr"):m.xaddr;
        }catch(Failure e){if(e.code!=Code.UNSUPPORTED)throw e;}
        if(media.isEmpty()) {
            Node services=response(soap(base,DEVICE,"GetServices","<d:IncludeCapability>false</d:IncludeCapability>",user,password),DEVICE,"GetServices");
            String second="";
            for(Node s:services.children)if(s.is(DEVICE,"Service")){String n=s.text(DEVICE,"Namespace"),url=s.text(DEVICE,"XAddr");if(n.equals(MEDIA1))media=url;else if(n.equals(MEDIA2))second=url;}
            if(media.isEmpty()&&!second.isEmpty()){media=second;ns=MEDIA2;}
        }
        if(media.isEmpty())throw new Failure(Code.UNSUPPORTED);
        media=related(base,media,false);
        phase(progress,"Авторизация и получение профилей…");
        Node result=response(soap(media,ns,"GetProfiles","",user,password),ns,"GetProfiles");List<Profile> profiles=new ArrayList<>();Set<String> tokens=new HashSet<>();
        for(Node p:result.children)if(p.is(ns,"Profiles")) {
            String token=p.token;if(token.isEmpty()||token.length()>1024||!tokens.add(token))throw new Failure(Code.INVALID);
            String name=p.text(SCHEMA,"Name");if(name.isEmpty())name=p.text(ns,"Name");profiles.add(new Profile(token,name.isEmpty()?token:name));
        }
        check();return new Device(base,media,ns,network,identity,info,profiles);
    }
    public String stream(Device device,Profile profile,String user,String password,Progress progress)throws Failure {
        deadline=System.nanoTime()+12000000000L;
        if(!Objects.equals(device.network,network))throw new Failure(Code.NETWORK_CHANGED);
        if(!device.profiles.contains(profile))throw new Failure(Code.INVALID);
        phase(progress,"Получение адреса RTSP…");
        String inner=device.mediaNamespace.equals(MEDIA2)?"<d:Protocol>RTSP</d:Protocol>":"<d:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></d:StreamSetup>";
        inner+="<d:ProfileToken>"+escape(profile.token)+"</d:ProfileToken>";
        Node result=response(soap(related(device.base,device.mediaUrl,false),device.mediaNamespace,"GetStreamUri",inner,user,password),device.mediaNamespace,"GetStreamUri");
        Node media=result.child(device.mediaNamespace,"MediaUri");String uri=media==null?result.text(device.mediaNamespace,"Uri"):media.text(SCHEMA,"Uri");
        if(uri.isEmpty())throw new Failure(Code.INVALID);check();return related(device.base,uri,true);
    }
    private static final class Challenge extends Exception { final String value;Challenge(String value){this.value=value;} }
    private String soap(String address,String ns,String action,String inner,String user,String password)throws Failure {
        try{return attempt(address,ns,action,inner,user,password,null);}
        catch(Challenge challenge){
            String header=CameraDigest.authorization(challenge.value,http(address),user,password);
            try{return attempt(address,ns,action,inner,user,password,header);}catch(Challenge again){throw new Failure(Code.AUTH);}
        }
    }
    private String attempt(String address,String ns,String action,String inner,String user,String password,String authorization)throws Failure,Challenge {
        HttpURLConnection c=null;
        try {
            check();URL url=http(address).toURL();c=(HttpURLConnection)(network==null?url.openConnection():network.openConnection(url));connection=c;check();
            int left=(int)Math.max(1,Math.min(timeoutMs,(deadline-System.nanoTime())/1000000));
            c.setConnectTimeout(left);c.setReadTimeout(left);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod("POST");c.setDoOutput(true);
            if(authorization!=null)c.setRequestProperty("Authorization",authorization);
            c.setRequestProperty("Content-Type","application/soap+xml; charset=utf-8; action=\""+ns+"/"+action+"\"");
            byte[] payload=("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:d=\""+ns+"\" xmlns:tt=\""+SCHEMA+"\"><s:Header>"+security(user,password)+"</s:Header><s:Body><d:"+action+">"+inner+"</d:"+action+"></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(payload.length);try(OutputStream out=c.getOutputStream()){check();out.write(payload);}
            int code=c.getResponseCode();check();if(code==401&&authorization==null&&!user.isEmpty()){
                String challenge=c.getHeaderField("WWW-Authenticate");if(challenge!=null&&challenge.length()<8192&&challenge.trim().startsWith("Digest "))throw new Challenge(challenge);
            }
            if(code==401||code==403)throw new Failure(Code.AUTH);if(code==404||code==405)throw new Failure(Code.UNSUPPORTED);if(code>=300&&code<400)throw new Failure(Code.ADDRESS);
            if(c.getContentLengthLong()>524288)throw new Failure(Code.INVALID);
            InputStream input=code>=200&&code<300?c.getInputStream():c.getErrorStream();if(input==null)throw new Failure(code==404||code==405?Code.UNSUPPORTED:Code.NETWORK);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=input){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){check();if(bytes.size()+n>524288)throw new Failure(Code.INVALID);bytes.write(b,0,n);}}
            String xml=new String(bytes.toByteArray(),StandardCharsets.UTF_8);response(xml,ns,action);if(code<200||code>=300)throw new Failure(Code.NETWORK);check();return xml;
        }catch(Challenge e){throw e;}catch(Failure e){throw e;}catch(SocketTimeoutException e){throw new Failure(cancelled.get()?Code.CANCELLED:Code.TIMEOUT);}catch(SSLException e){throw new Failure(cancelled.get()?Code.CANCELLED:Code.TLS);}catch(Exception e){throw new Failure(cancelled.get()?Code.CANCELLED:Code.NETWORK);}finally{if(c!=null)c.disconnect();connection=null;}
    }
    static URI http(String raw)throws Failure {
        try{URI u=new URI(raw.trim());if(!Arrays.asList("http","https").contains(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getFragment()!=null||u.getPort()==0||u.getPort()>65535)throw new Exception();return u;}catch(Exception e){throw new Failure(Code.ADDRESS);}
    }
    static String related(String base,String address,boolean rtsp)throws Failure {
        try {
            URI b=http(base),u=new URI(address.trim());String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);if(rtsp?!"rtsp".equalsIgnoreCase(scheme):!Arrays.asList("http","https").contains(scheme))throw new Failure(Code.UNSUPPORTED);
            String host=u.getHost();if(host==null||u.getFragment()!=null||u.getPort()==0||u.getPort()>65535)throw new Failure(Code.ADDRESS);
            if(host.equals("0.0.0.0")||host.equals("[::]")||host.equals("::"))host=b.getHost();
            if(!host.equalsIgnoreCase(b.getHost())||(!rtsp&&b.getScheme().equals("https")&&!scheme.equals("https")))throw new Failure(Code.ADDRESS);
            // Only the selected camera receives credentials. Never accept credentials in advertised URLs.
            if(!rtsp&&u.getRawUserInfo()!=null)throw new Failure(Code.ADDRESS);
            String authority=host+(u.getPort()<0?"":":"+u.getPort());return new URI(scheme+"://"+authority+(u.getRawPath()==null?"":u.getRawPath())+(u.getRawQuery()==null?"":"?"+u.getRawQuery())).toASCIIString();
        }catch(Failure e){throw e;}catch(Exception e){throw new Failure(Code.ADDRESS);}
    }
    public static String deviceAddress(String host,String address)throws Failure {
        URI u=http(address);String expected=host.contains(":")&&!host.startsWith("[")?"["+host+"]":host;
        if(!u.getHost().equalsIgnoreCase(expected))throw new Failure(Code.ADDRESS);return u.toASCIIString();
    }
    public static String defaultUrl(String host,int port,String location) {
        try {for(String s:location.trim().split("\\s+")){URI u=http(s);if(u.getHost().equalsIgnoreCase(host))return u.toASCIIString();}}catch(Exception ignored){}
        String h=host.contains(":")&&!host.startsWith("[")?"["+host+"]":host;
        return (port==443||port==8443?"https":"http")+"://"+h+":"+(port>0&&port!=3702?port:80)+"/onvif/device_service";
    }
    static String security(String user,String password)throws Exception {
        if(user.isEmpty())return "";byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));String created=f.format(new Date());
        MessageDigest sha=MessageDigest.getInstance("SHA-1");sha.update(nonce);sha.update(created.getBytes(StandardCharsets.UTF_8));sha.update(password.getBytes(StandardCharsets.UTF_8));
        return "<wsse:Security s:mustUnderstand=\"1\" xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\"><wsse:UsernameToken><wsse:Username>"+escape(user)+"</wsse:Username><wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">"+java.util.Base64.getEncoder().encodeToString(sha.digest())+"</wsse:Password><wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"+java.util.Base64.getEncoder().encodeToString(nonce)+"</wsse:Nonce><wsu:Created>"+created+"</wsu:Created></wsse:UsernameToken></wsse:Security>";
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    static final class Node {
        String ns,name,value="",token="",xaddr="";final List<Node> children=new ArrayList<>();
        boolean is(String ns,String name){return this.ns.equals(ns)&&this.name.equals(name);}
        Node child(String ns,String name)throws Failure{Node out=null;for(Node n:children)if(n.is(ns,name)){if(out!=null)throw new Failure(Code.INVALID);out=n;}return out;}
        String text(String ns,String name)throws Failure{Node n=child(ns,name);return n==null?"":n.value.trim();}
    }
    static Node response(String xml,String ns,String action)throws Failure {
        if(xml.length()>524288||xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new Failure(Code.INVALID);
        try {
            XmlPullParserFactory f=XmlPullParserFactory.newInstance();f.setNamespaceAware(true);XmlPullParser p=f.newPullParser();p.setInput(new StringReader(xml));
            Deque<Node> stack=new ArrayDeque<>();Node root=null;int count=0;
            for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT;e=p.next()) {
                if(e==XmlPullParser.START_TAG){if(++count>8192||stack.size()>48)throw new Failure(Code.INVALID);Node n=new Node();n.ns=p.getNamespace();n.name=p.getName();String t=p.getAttributeValue(null,"token");if(t!=null)n.token=t;String address=p.getAttributeValue(null,"XAddr");if(address!=null){if(address.length()>8192)throw new Failure(Code.INVALID);n.xaddr=address.trim();}if(stack.isEmpty()){if(root!=null)throw new Failure(Code.INVALID);root=n;}else stack.peek().children.add(n);stack.push(n);}
                else if(e==XmlPullParser.TEXT&&!stack.isEmpty()){Node n=stack.peek();if(n.value.length()+p.getText().length()>16384)throw new Failure(Code.INVALID);n.value+=p.getText();}
                else if(e==XmlPullParser.END_TAG)stack.pop();
            }
            if(root==null||!root.name.equals("Envelope")||!Arrays.asList("http://www.w3.org/2003/05/soap-envelope","http://schemas.xmlsoap.org/soap/envelope/").contains(root.ns))throw new Failure(Code.INVALID);
            Node body=root.child(root.ns,"Body");if(body==null||body.children.size()!=1)throw new Failure(Code.INVALID);Node r=body.children.get(0);
            if(r.is(root.ns,"Fault")){String all=xml.toLowerCase(Locale.ROOT);throw new Failure(all.contains("notauthorized")||all.contains("failedauthentication")?Code.AUTH:all.contains("actionnotsupported")||all.contains("optionalactionnotimplemented")?Code.UNSUPPORTED:Code.INVALID);}
            if(!r.is(ns,action+"Response"))throw new Failure(Code.INVALID);return r;
        }catch(Failure e){throw e;}catch(Exception e){throw new Failure(Code.INVALID);}
    }
}
