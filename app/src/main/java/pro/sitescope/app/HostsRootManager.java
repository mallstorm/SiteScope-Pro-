package pro.sitescope.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Root/Magisk integration. It never remounts or writes the verified system partition directly. */
public final class HostsRootManager {
    public static final String SYSTEM_HOSTS = "/system/etc/hosts";
    public static final String MODULE_DIR = "/data/adb/modules/sitescope_hosts";

    public static final class Capability {
        public boolean root;
        public boolean magisk;
        public boolean readable;
        public String path = SYSTEM_HOSTS;
        public String rootIdentity = "";
        public String message = "";
    }

    public static final class ApplyResult {
        public boolean success;
        public boolean rebootRequired;
        public String mode;
        public String message;
        public String beforeHash;
        public String afterHash;
        public HostsStore.Backup backup;
    }

    private static final class ShellResult {
        int code = -1; String out = ""; String err = ""; boolean timeout;
    }

    private HostsRootManager() { }

    public static Capability detect() {
        Capability c = new Capability();
        ShellResult id = shell("id", 5000);
        c.root = id.code == 0 && id.out.contains("uid=0"); c.rootIdentity = id.out.trim();
        if (c.root) {
            ShellResult magisk = shell("command -v magisk >/dev/null 2>&1 || test -d /data/adb/modules; echo $?", 5000);
            c.magisk = magisk.code == 0 && magisk.out.trim().startsWith("0");
            ShellResult hosts = shell("if test -r /system/etc/hosts; then echo /system/etc/hosts; elif test -r /etc/hosts; then echo /etc/hosts; else exit 4; fi", 5000);
            c.readable = hosts.code == 0; if (c.readable && hosts.out.trim().length() > 0) c.path = hosts.out.trim().split("\\s+")[0];
            c.message = c.magisk ? "Root и Magisk доступны" : "Root доступен; Magisk не обнаружен";
        } else {
            File direct = new File(SYSTEM_HOSTS); c.readable = direct.canRead(); c.message = "Root не предоставлен";
        }
        return c;
    }

    public static String readCurrent(Capability capability) throws Exception {
        if (capability != null && capability.root) {
            ShellResult result = shell("cat " + quote(capability.path), 8000);
            if (result.code != 0) throw new IllegalStateException("Не удалось прочитать hosts через root: " + first(result.err, result.out));
            return result.out;
        }
        File file = new File(capability == null ? SYSTEM_HOSTS : capability.path);
        if (!file.canRead()) throw new IllegalStateException("Системный hosts недоступен без root");
        return readFile(file);
    }

    public static ApplyResult applyMagisk(Context context, HostsStore store, String profileId, String candidate, Capability capability) {
        ApplyResult result = new ApplyResult(); result.mode = "MAGISK";
        try {
            if (capability == null || !capability.root || !capability.magisk) throw new IllegalStateException("Нужны root и Magisk");
            String current = readCurrent(capability); result.beforeHash = sha256(current.getBytes(StandardCharsets.UTF_8)); result.backup = backup(context, store, current, "MAGISK", capability.path);
            File staging = new File(context.getFilesDir(), "hosts-manager/module-staging"); if (!staging.exists() && !staging.mkdirs()) throw new IllegalStateException("Не удалось создать staging");
            File hosts = new File(staging, "hosts"); File prop = new File(staging, "module.prop");
            writeSync(hosts, candidate);
            writeSync(prop, "id=sitescope_hosts\nname=SiteScope Pro Hosts Manager\nversion=0.34.0-alpha32\nversionCode=340032\nauthor=SiteScope Pro\ndescription=Systemless hosts profile managed by SiteScope Pro\n");
            String command = "set -e; MOD=" + quote(MODULE_DIR) + "; mkdir -p \"$MOD/system/etc\"; " +
                "cp " + quote(prop.getAbsolutePath()) + " \"$MOD/module.prop\"; cp " + quote(hosts.getAbsolutePath()) + " \"$MOD/system/etc/hosts.new\"; " +
                "chown 0:0 \"$MOD/module.prop\" \"$MOD/system/etc/hosts.new\"; chmod 0644 \"$MOD/module.prop\" \"$MOD/system/etc/hosts.new\"; " +
                "mv -f \"$MOD/system/etc/hosts.new\" \"$MOD/system/etc/hosts\"; rm -f \"$MOD/disable\" \"$MOD/remove\"; touch \"$MOD/update\"; " +
                "(mount --bind \"$MOD/system/etc/hosts\" " + quote(capability.path) + " >/dev/null 2>&1 || true); " +
                "sha256sum \"$MOD/system/etc/hosts\" | awk '{print $1}'";
            ShellResult applied = shell(command, 12000);
            if (applied.code != 0) throw new IllegalStateException("Не удалось установить Magisk-модуль: " + first(applied.err, applied.out));
            String expected = sha256(candidate.getBytes(StandardCharsets.UTF_8)); String moduleHash = firstToken(applied.out);
            if (!expected.equalsIgnoreCase(moduleHash)) throw new IllegalStateException("Контрольная сумма файла модуля не совпала");
            String active = ""; try { active = readCurrent(capability); } catch (Exception ignored) { }
            result.afterHash = active.length() > 0 ? sha256(active.getBytes(StandardCharsets.UTF_8)) : moduleHash;
            result.rebootRequired = !expected.equalsIgnoreCase(result.afterHash);
            result.success = true;
            result.message = result.rebootRequired ? "Профиль установлен systemless. Для активации нужен перезапуск устройства." : "Профиль применён systemless и уже активен.";
            store.recordApply(profileId, result.mode, true, result.message, result.beforeHash, result.afterHash);
        } catch (Exception e) {
            result.success=false; result.message=e.getMessage()==null?e.toString():e.getMessage(); store.recordApply(profileId,result.mode,false,result.message,result.beforeHash,result.afterHash);
        }
        return result;
    }

    public static ApplyResult applyTemporary(Context context, HostsStore store, String profileId, String candidate, Capability capability) {
        ApplyResult result = new ApplyResult(); result.mode = "ROOT_TEMP";
        try {
            if (capability == null || !capability.root) throw new IllegalStateException("Root не предоставлен");
            String current=readCurrent(capability);result.beforeHash=sha256(current.getBytes(StandardCharsets.UTF_8));result.backup=backup(context,store,current,"ROOT_TEMP",capability.path);
            File activeDir=new File(context.getFilesDir(),"hosts-manager/active");if(!activeDir.exists()&&!activeDir.mkdirs())throw new IllegalStateException("Не удалось создать рабочий каталог");
            File active=new File(activeDir,"hosts");writeSync(active,candidate);
            String cmd="set -e; cp "+quote(active.getAbsolutePath())+" /data/local/tmp/sitescope-hosts; chown 0:0 /data/local/tmp/sitescope-hosts; chmod 0644 /data/local/tmp/sitescope-hosts; (chcon u:object_r:system_file:s0 /data/local/tmp/sitescope-hosts >/dev/null 2>&1 || true); mount --bind /data/local/tmp/sitescope-hosts "+quote(capability.path)+"; sha256sum "+quote(capability.path)+" | awk '{print $1}'";
            ShellResult applied=shell(cmd,10000);if(applied.code!=0)throw new IllegalStateException("Bind-mount не выполнен: "+first(applied.err,applied.out));
            result.afterHash=firstToken(applied.out);String expected=sha256(candidate.getBytes(StandardCharsets.UTF_8));if(!expected.equalsIgnoreCase(result.afterHash))throw new IllegalStateException("После применения активный файл не совпал с кандидатом");
            result.success=true;result.message="Профиль применён до следующей перезагрузки. Для постоянного режима используйте Magisk.";store.recordApply(profileId,result.mode,true,result.message,result.beforeHash,result.afterHash);
        }catch(Exception e){result.success=false;result.message=e.getMessage()==null?e.toString():e.getMessage();store.recordApply(profileId,result.mode,false,result.message,result.beforeHash,result.afterHash);}return result;
    }

    public static ApplyResult restoreBackup(Context context, HostsStore store, String profileId, HostsStore.Backup backup, Capability capability, boolean magisk) {
        ApplyResult result = new ApplyResult(); result.mode = magisk ? "MAGISK_ROLLBACK" : "ROOT_TEMP_ROLLBACK";
        try {
            File file = new File(backup.path); if (!file.isFile()) throw new IllegalStateException("Файл резервной копии отсутствует");
            String content = readFile(file); String actual = sha256(content.getBytes(StandardCharsets.UTF_8)); if (!actual.equalsIgnoreCase(backup.sha256)) throw new IllegalStateException("Резервная копия повреждена");
            return magisk ? applyMagisk(context, store, profileId, content, capability) : applyTemporary(context, store, profileId, content, capability);
        } catch (Exception e) { result.success=false;result.message=e.getMessage()==null?e.toString():e.getMessage();store.recordApply(profileId,result.mode,false,result.message,"","");return result; }
    }

    public static HostsStore.Backup backup(Context context, HostsStore store, String content, String mode, String sourcePath) throws Exception {
        File dir=new File(context.getFilesDir(),"hosts-manager/backups");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Не удалось создать каталог резервных копий");
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date());File file=new File(dir,"hosts-"+stamp+".bak");writeSync(file,content);
        HostsStore.Backup b=new HostsStore.Backup();b.id=stamp+"-"+Math.abs(content.hashCode());b.timestamp=System.currentTimeMillis();b.mode=mode;b.path=file.getAbsolutePath();b.sha256=sha256(content.getBytes(StandardCharsets.UTF_8));b.size=file.length();b.sourcePath=sourcePath;store.recordBackup(b);return b;
    }

    public static String sha256(byte[] bytes) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] hash=digest.digest(bytes);StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.US,"%02x",b&0xff));return out.toString();}
    private static void writeSync(File file,String value)throws Exception{File parent=file.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IllegalStateException("Не удалось создать каталог");FileOutputStream out=new FileOutputStream(file);try{out.write(value.getBytes(StandardCharsets.UTF_8));out.flush();out.getFD().sync();}finally{out.close();}}
    private static String readFile(File file)throws Exception{FileInputStream in=new FileInputStream(file);try{return readStream(in);}finally{in.close();}}
    private static String readStream(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}

    private static ShellResult shell(String command,long timeoutMs){
        ShellResult r=new ShellResult();Process p=null;
        try{
            p=new ProcessBuilder("su","-c",command).start();
            final Process process=p;final ByteArrayOutputStream stdout=new ByteArrayOutputStream();final ByteArrayOutputStream stderr=new ByteArrayOutputStream();
            Thread outThread=new Thread(new Runnable(){@Override public void run(){copy(process.getInputStream(),stdout);}},"HostsRootStdout");
            Thread errThread=new Thread(new Runnable(){@Override public void run(){copy(process.getErrorStream(),stderr);}},"HostsRootStderr");
            outThread.start();errThread.start();
            boolean done=p.waitFor(timeoutMs,TimeUnit.MILLISECONDS);
            if(!done){r.timeout=true;p.destroy();p.waitFor(1000,TimeUnit.MILLISECONDS);r.err="Превышен таймаут root-команды";}
            else r.code=p.exitValue();
            outThread.join(1200);errThread.join(1200);
            r.out=new String(stdout.toByteArray(),StandardCharsets.UTF_8);String capturedErr=new String(stderr.toByteArray(),StandardCharsets.UTF_8);if(capturedErr.length()>0)r.err=capturedErr;
        }catch(Exception e){r.err=e.getMessage()==null?e.toString():e.getMessage();if(p!=null)p.destroy();}
        return r;
    }
    private static void copy(InputStream in,ByteArrayOutputStream out){try{byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);}catch(Exception ignored){}}
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private static String first(String a,String b){String x=a==null?"":a.trim();if(x.length()>0)return x;return b==null?"":b.trim();}
    private static String firstToken(String value){String v=value==null?"":value.trim();return v.length()==0?"":v.split("\\s+")[0];}
}
