package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Native SSH terminal and SFTP browser powered by the bundled maintained JSch fork. */
public final class SshClientActivity extends Activity {
    private static final int PICK_UPLOAD = 4101;
    private static final int CREATE_DOWNLOAD = 4102;
    private static final int PICK_PRIVATE_KEY = 4103;

    private ServiceEndpoint endpoint;
    private EditText userInput, passwordInput, pathInput;
    private LinearLayout content, fileList;
    private TextView status, terminal;
    private ProgressBar progress;
    private Session session;
    private ChannelShell shell;
    private ChannelSftp sftp;
    private OutputStream shellInput;
    private volatile boolean reading;
    private String mode = "SSH";
    private String pendingRemoteDownload = "";
    private byte[] selectedPrivateKey;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        endpoint = ServiceEndpoint.from(getIntent());
        mode = getIntent().getStringExtra("mode");
        if (mode == null || mode.length() == 0) mode = endpoint.protocol;
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = ClientUi.root(this);
        root.addView(ClientUi.header(this, "SSH / SFTP", "Проверка fingerprint, терминал и файловый доступ"));
        content = ClientUi.page(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout connection = ClientUi.card(this, 15);
        connection.addView(ClientUi.text(this, endpoint.host + ":" + (endpoint.port > 0 ? endpoint.port : 22), 17, ClientUi.NAVY, true));
        userInput = ClientUi.input(this, "Пользователь");
        passwordInput = ClientUi.input(this, "Пароль");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        connection.addView(userInput, ClientUi.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ClientUi.dp(this,48),0,12,0,0));
        connection.addView(passwordInput, ClientUi.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ClientUi.dp(this,48),0,8,0,0));
        LinearLayout buttons = ClientUi.row(this);
        TextView terminalButton = ClientUi.button(this, "Терминал", true);
        TextView filesButton = ClientUi.button(this, "Файлы SFTP", false);
        buttons.addView(terminalButton, new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));
        buttons.addView(space(8));
        buttons.addView(filesButton, new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f));
        connection.addView(buttons, ClientUi.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,0,10,0,0));
        TextView keyButton = ClientUi.button(this, "Выбрать приватный ключ (не сохраняется)", false);
        keyButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_PRIVATE_KEY);
        }});
        connection.addView(keyButton, ClientUi.margins(this, ViewGroup.LayoutParams.MATCH_PARENT, ClientUi.dp(this,44),0,8,0,0));
        terminalButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { mode="SSH"; connect(); } });
        filesButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { mode="SFTP"; connect(); } });
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        connection.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,36)));
        status = ClientUi.text(this, "Учётные данные не сохраняются. Неизвестный ключ сервера потребует подтверждения.", 12, ClientUi.MUTED, false);
        connection.addView(status);
        content.addView(connection);

        if ("SFTP".equals(mode)) { }
        setContentView(root);
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ClientUi.dp(this,dp),1));
        return v;
    }

    private void connect() {
        if (userInput.getText().toString().trim().length()==0) { userInput.setError("Введите пользователя"); return; }
        disconnect();
        progress.setVisibility(View.VISIBLE);
        status.setText("Подключение…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                JSch jsch = new JSch();
                File known = new File(getFilesDir(), "ssh_known_hosts");
                if (!known.exists()) known.createNewFile();
                jsch.setKnownHosts(known.getAbsolutePath());
                if (selectedPrivateKey != null) {
                    byte[] passphrase = passwordInput.getText().toString().length() > 0 ? passwordInput.getText().toString().getBytes("UTF-8") : null;
                    jsch.addIdentity("SiteScope-selected-key", selectedPrivateKey, null, passphrase);
                }
                session = jsch.getSession(userInput.getText().toString().trim(), endpoint.host, endpoint.port > 0 ? endpoint.port : 22);
                if (selectedPrivateKey == null) session.setPassword(passwordInput.getText().toString());
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "ask");
                config.put("PreferredAuthentications", "password,keyboard-interactive,publickey");
                session.setConfig(config);
                session.setUserInfo(new UiUserInfo());
                session.connect(12000);
                HostKey key = session.getHostKey();
                if (key != null && !known.exists()) {
                    try { jsch.getHostKeyRepository().add(key, null); } catch (Exception ignored) { }
                }
                if ("SFTP".equals(mode)) openSftp(); else openShell();
            } catch (final Exception error) { uiError(error); }
        }}).start();
    }

    private void openShell() throws Exception {
        shell = (ChannelShell) session.openChannel("shell");
        shell.setPtyType("xterm-256color");
        final InputStream output = shell.getInputStream();
        shellInput = shell.getOutputStream();
        shell.connect(8000);
        reading = true;
        runOnUiThread(new Runnable() { @Override public void run() { renderTerminal(); } });
        byte[] buffer = new byte[4096];
        while (reading && shell != null && shell.isConnected()) {
            int n = output.read(buffer);
            if (n < 0) break;
            final String chunk = new String(buffer,0,n,"UTF-8");
            runOnUiThread(new Runnable() { @Override public void run() { appendTerminal(chunk); } });
        }
    }

    private void renderTerminal() {
        progress.setVisibility(View.GONE);
        status.setText("SSH подключён · команды отправляются только вручную");
        if (terminal != null) return;
        LinearLayout card = ClientUi.card(this, 10);
        terminal = ClientUi.text(this, "", 12, Color.rgb(225,236,247), false);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setTextIsSelectable(true);
        terminal.setPadding(ClientUi.dp(this,10),ClientUi.dp(this,10),ClientUi.dp(this,10),ClientUi.dp(this,10));
        terminal.setBackgroundColor(Color.rgb(13,20,30));
        ScrollView console = new ScrollView(this);
        console.addView(terminal);
        card.addView(console, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,330)));
        final EditText command = ClientUi.input(this,"Команда");
        command.setSingleLine(true);
        TextView send = ClientUi.button(this,"Отправить",true);
        LinearLayout row = ClientUi.row(this);
        row.addView(command,new LinearLayout.LayoutParams(0,ClientUi.dp(this,48),1f));
        row.addView(space(8));
        row.addView(send,new LinearLayout.LayoutParams(ClientUi.dp(this,110),ClientUi.dp(this,48)));
        send.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
            String value=command.getText().toString(); if(value.length()==0)return;
            try { shellInput.write((value+"\n").getBytes("UTF-8")); shellInput.flush(); command.setText(""); }
            catch(Exception e){ Toast.makeText(SshClientActivity.this,e.getMessage(),Toast.LENGTH_LONG).show(); }
        }});
        card.addView(row,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,8,0,0));
        content.addView(card,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));
    }

    private void appendTerminal(String value) {
        if (terminal == null) return;
        String current=terminal.getText().toString();
        if(current.length()>120000) current=current.substring(current.length()-80000);
        terminal.setText(current+value);
    }

    private void openSftp() throws Exception {
        Channel channel=session.openChannel("sftp");
        channel.connect(8000);
        sftp=(ChannelSftp)channel;
        runOnUiThread(new Runnable(){ @Override public void run(){ renderSftp(); loadDirectory("."); }});
    }

    private void renderSftp() {
        progress.setVisibility(View.GONE);
        status.setText("SFTP подключён · долгое нажатие скачивает файл");
        if(fileList!=null)return;
        LinearLayout card=ClientUi.card(this,12);
        LinearLayout pathRow=ClientUi.row(this);
        pathInput=ClientUi.input(this,"Удалённый путь"); pathInput.setText(".");
        TextView go=ClientUi.button(this,"Открыть",true);
        pathRow.addView(pathInput,new LinearLayout.LayoutParams(0,ClientUi.dp(this,46),1f)); pathRow.addView(space(8)); pathRow.addView(go,new LinearLayout.LayoutParams(ClientUi.dp(this,96),ClientUi.dp(this,46)));
        go.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){loadDirectory(pathInput.getText().toString().trim());}});
        card.addView(pathRow);
        LinearLayout actions=ClientUi.row(this);
        TextView up=ClientUi.button(this,"Вверх",false), upload=ClientUi.button(this,"Загрузить файл",false);
        actions.addView(up,new LinearLayout.LayoutParams(0,ClientUi.dp(this,44),1f)); actions.addView(space(8)); actions.addView(upload,new LinearLayout.LayoutParams(0,ClientUi.dp(this,44),1f));
        up.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){loadDirectory("..");}});
        upload.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_UPLOAD);}});
        card.addView(actions,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,8,0,0));
        fileList=new LinearLayout(this); fileList.setOrientation(LinearLayout.VERTICAL); card.addView(fileList,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,8,0,0));
        content.addView(card,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));
    }

    private void loadDirectory(final String path) {
        if(sftp==null)return; progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable(){@Override public void run(){try{
            if(path.length()>0)sftp.cd(path); final String pwd=sftp.pwd(); final Vector<?> entries=sftp.ls(".");
            runOnUiThread(new Runnable(){@Override public void run(){progress.setVisibility(View.GONE);pathInput.setText(pwd);fileList.removeAllViews();for(Object o:entries){if(!(o instanceof ChannelSftp.LsEntry))continue;ChannelSftp.LsEntry e=(ChannelSftp.LsEntry)o;if(".".equals(e.getFilename())||"..".equals(e.getFilename()))continue;addSftpEntry(e);}}});
        }catch(final Exception e){uiError(e);}}}).start();
    }

    private void addSftpEntry(final ChannelSftp.LsEntry entry) {
        SftpATTRS a=entry.getAttrs(); String prefix=a.isDir()?"📁 ":"📄 ";
        TextView row=ClientUi.text(this,prefix+entry.getFilename()+"\n"+(a.isDir()?"Каталог":a.getSize()+" байт"),14,ClientUi.NAVY,true);
        row.setPadding(ClientUi.dp(this,10),ClientUi.dp(this,9),ClientUi.dp(this,10),ClientUi.dp(this,9));
        row.setBackground(ClientUi.round(this,Color.WHITE,10,ClientUi.BORDER,1));
        if(a.isDir()) row.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){loadDirectory(entry.getFilename());}});
        else row.setOnLongClickListener(new View.OnLongClickListener(){@Override public boolean onLongClick(View v){pendingRemoteDownload=entry.getFilename();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,entry.getFilename());startActivityForResult(i,CREATE_DOWNLOAD);return true;}});
        fileList.addView(row,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,3,0,3));
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;final Uri uri=data.getData();if(requestCode==PICK_PRIVATE_KEY){try{InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;while((n=input.read(chunk))>=0)buffer.write(chunk,0,n);input.close();selectedPrivateKey=buffer.toByteArray();status.setText("Приватный ключ выбран. Поле пароля используется как passphrase.");}catch(Exception e){status.setText("Ключ не прочитан: "+e.getMessage());}return;}if(requestCode==PICK_UPLOAD){new Thread(new Runnable(){@Override public void run(){try{InputStream in=getContentResolver().openInputStream(uri);String name=uri.getLastPathSegment();if(name==null)name="upload.bin";int slash=name.lastIndexOf('/');if(slash>=0)name=name.substring(slash+1);sftp.put(in,name);in.close();runOnUiThread(new Runnable(){@Override public void run(){loadDirectory(".");Toast.makeText(SshClientActivity.this,"Файл загружен",Toast.LENGTH_SHORT).show();}});}catch(final Exception e){uiError(e);}}}).start();}else if(requestCode==CREATE_DOWNLOAD){new Thread(new Runnable(){@Override public void run(){try{OutputStream out=getContentResolver().openOutputStream(uri);sftp.get(pendingRemoteDownload,out);out.close();runOnUiThread(new Runnable(){@Override public void run(){Toast.makeText(SshClientActivity.this,"Файл сохранён",Toast.LENGTH_SHORT).show();}});}catch(final Exception e){uiError(e);}}}).start();}}

    private void uiError(final Exception e){runOnUiThread(new Runnable(){@Override public void run(){progress.setVisibility(View.GONE);status.setText("Ошибка: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}});}

    private void disconnect(){reading=false;try{if(sftp!=null)sftp.disconnect();}catch(Exception ignored){}try{if(shell!=null)shell.disconnect();}catch(Exception ignored){}try{if(session!=null)session.disconnect();}catch(Exception ignored){}sftp=null;shell=null;session=null;}
    @Override protected void onDestroy(){disconnect();super.onDestroy();}

    private final class UiUserInfo implements com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        @Override public String getPassword(){return passwordInput.getText().toString();}
        @Override public boolean promptPassword(String message){return true;}
        @Override public boolean promptPassphrase(String message){return false;}
        @Override public String getPassphrase(){return null;}
        @Override public void showMessage(final String message){runOnUiThread(new Runnable(){@Override public void run(){Toast.makeText(SshClientActivity.this,message,Toast.LENGTH_LONG).show();}});}
        @Override public boolean promptYesNo(final String message){final boolean[] answer={false};final CountDownLatch latch=new CountDownLatch(1);runOnUiThread(new Runnable(){@Override public void run(){new AlertDialog.Builder(SshClientActivity.this).setTitle("SSH fingerprint").setMessage(message+"\n\nПодтверждайте только известный сервер.").setPositiveButton("Доверять",new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){answer[0]=true;latch.countDown();}}).setNegativeButton("Отмена",new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){latch.countDown();}}).setOnCancelListener(new DialogInterface.OnCancelListener(){@Override public void onCancel(DialogInterface d){latch.countDown();}}).show();}});try{latch.await(60,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return answer[0];}
        @Override public String[] promptKeyboardInteractive(String destination,String name,String instruction,String[] prompt,boolean[] echo){String[] a=new String[prompt.length];for(int i=0;i<a.length;i++)a[i]=passwordInput.getText().toString();return a;}
    }
}
