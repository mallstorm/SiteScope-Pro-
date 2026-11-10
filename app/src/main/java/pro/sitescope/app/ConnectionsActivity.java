package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Connection Center 2.0: saved profiles, health checks and non-secret session journal. */
public final class ConnectionsActivity extends Activity {
    private LinearLayout body;
    private EditText search;
    private String tab = "SAVED";
    private volatile boolean bulkChecking;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildUi();
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void buildUi() {
        LinearLayout root=ClientUi.root(this);
        LinearLayout header=ClientUi.header(this,"Подключения","Профили, доступность и журнал запусков");
        TextView checkAll=ClientUi.text(this,"✓ все",13,ClientUi.PRIMARY,true);checkAll.setGravity(Gravity.CENTER);checkAll.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){checkAllProfiles();}});
        header.addView(checkAll,new LinearLayout.LayoutParams(ClientUi.dp(this,58),ClientUi.dp(this,42)));
        root.addView(header);
        LinearLayout page=ClientUi.page(this);
        LinearLayout hint=ClientUi.card(this,12);hint.setBackground(ClientUi.round(this,ClientUi.PRIMARY_SOFT,14,Color.TRANSPARENT,0));hint.addView(ClientUi.text(this,"Профили содержат только адреса, заметки и результаты проверок. Пароли, токены и приватные ключи не попадают в журнал или backup.",12,Color.rgb(49,78,123),false));page.addView(hint);
        LinearLayout tabs=ClientUi.row(this);TextView saved=ClientUi.button(this,"Сохранённые",true);TextView recent=ClientUi.button(this,"Журнал",false);tabs.addView(saved,new LinearLayout.LayoutParams(0,ClientUi.dp(this,43),1f));tabs.addView(space(8));tabs.addView(recent,new LinearLayout.LayoutParams(0,ClientUi.dp(this,43),1f));page.addView(tabs,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));
        search=ClientUi.input(this,"Поиск по имени, адресу, протоколу или тегу");page.addView(search,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,48),0,10,0,8));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);page.addView(body);
        ScrollView scroll=new ScrollView(this);scroll.addView(page,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));root.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root);
        saved.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){tab="SAVED";saved.setBackground(ClientUi.round(ConnectionsActivity.this,ClientUi.PRIMARY,12,Color.TRANSPARENT,0));saved.setTextColor(Color.WHITE);recent.setBackground(ClientUi.round(ConnectionsActivity.this,Color.WHITE,12,ClientUi.BORDER,1));recent.setTextColor(ClientUi.PRIMARY);render();}});
        recent.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){tab="RECENT";recent.setBackground(ClientUi.round(ConnectionsActivity.this,ClientUi.PRIMARY,12,Color.TRANSPARENT,0));recent.setTextColor(Color.WHITE);saved.setBackground(ClientUi.round(ConnectionsActivity.this,Color.WHITE,12,ClientUi.BORDER,1));saved.setTextColor(ClientUi.PRIMARY);render();}});
        search.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}@Override public void onTextChanged(CharSequence s,int st,int b,int c){render();}@Override public void afterTextChanged(Editable e){}});
    }

    private void render(){if(body==null)return;body.removeAllViews();if("RECENT".equals(tab))renderJournal();else renderProfiles();}

    private void renderProfiles(){List<ConnectionProfileStore.Profile> profiles=ConnectionProfileStore.list(this);String q=query();int shown=0;for(final ConnectionProfileStore.Profile profile:profiles){if(!matches(profile,q))continue;shown++;final LinearLayout card=ClientUi.card(this,14);LinearLayout top=ClientUi.row(this);TextView icon=ClientUi.text(this,protocolIcon(profile.protocol),20,ClientUi.PRIMARY,true);icon.setGravity(Gravity.CENTER);icon.setBackground(ClientUi.round(this,ClientUi.PRIMARY_SOFT,14,Color.TRANSPARENT,0));top.addView(icon,new LinearLayout.LayoutParams(ClientUi.dp(this,48),ClientUi.dp(this,48)));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(ClientUi.dp(this,11),0,0,0);String title=(profile.pinned?"★ ":"")+profile.name;info.addView(ClientUi.text(this,title,16,ClientUi.NAVY,true));info.addView(ClientUi.text(this,profile.protocol+" · "+endpointText(profile),12,ClientUi.MUTED,false));if(profile.tags.length()>0)info.addView(ClientUi.text(this,"# "+profile.tags,11,ClientUi.PRIMARY,false));top.addView(info,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));top.addView(statusBadge(profile),new LinearLayout.LayoutParams(ClientUi.dp(this,78),ClientUi.dp(this,32)));card.addView(top);if(profile.notes.length()>0)card.addView(ClientUi.text(this,profile.notes,12,ClientUi.MUTED,false),ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,59,8,0,0));if(profile.lastCheckedAt>0){String checked=new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(profile.lastCheckedAt));card.addView(ClientUi.text(this,"Проверено "+checked+" · "+profile.lastLatencyMs+" мс · "+profile.lastMessage,11,ClientUi.MUTED,false),ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,59,7,0,0));}
        LinearLayout actions=ClientUi.row(this);TextView open=ClientUi.button(this,"Открыть",true);TextView check=ClientUi.button(this,"Проверить",false);TextView more=ClientUi.button(this,"⋮",false);actions.addView(open,new LinearLayout.LayoutParams(0,ClientUi.dp(this,42),1f));actions.addView(space(6));actions.addView(check,new LinearLayout.LayoutParams(0,ClientUi.dp(this,42),1f));actions.addView(space(6));actions.addView(more,new LinearLayout.LayoutParams(ClientUi.dp(this,48),ClientUi.dp(this,42)));card.addView(actions,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,11,0,0));open.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){ProtocolRouter.dispatch(ConnectionsActivity.this,profile.endpoint());}});check.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){checkProfile(profile,card);}});more.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){profileMenu(profile);}});body.addView(card,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,5,0,5));}
        if(shown==0){LinearLayout empty=ClientUi.card(this,18);TextView t=ClientUi.text(this,profiles.isEmpty()?"Сохранённых подключений пока нет":"Ничего не найдено",17,ClientUi.NAVY,true);t.setGravity(Gravity.CENTER);empty.addView(t);TextView n=ClientUi.text(this,profiles.isEmpty()?"Сохраните сервис из результатов сканера или LAN Discovery.":"Измените поисковый запрос.",13,ClientUi.MUTED,false);n.setGravity(Gravity.CENTER);empty.addView(n,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,8,0,0));body.addView(empty);}
    }

    private void renderJournal(){List<ConnectionSessionStore.Event> events=ConnectionSessionStore.list(this);String q=query();int shown=0;for(ConnectionSessionStore.Event event:events){String hay=(event.protocol+" "+event.endpoint+" "+event.status+" "+event.message).toLowerCase(Locale.US);if(q.length()>0&&!hay.contains(q))continue;shown++;LinearLayout card=ClientUi.card(this,13);LinearLayout top=ClientUi.row(this);String state="ONLINE".equals(event.status)?"✓":"OFFLINE".equals(event.status)?"!":"→";int color="ONLINE".equals(event.status)?ClientUi.GREEN:"OFFLINE".equals(event.status)?ClientUi.RED:ClientUi.PRIMARY;TextView icon=ClientUi.text(this,state,18,color,true);icon.setGravity(Gravity.CENTER);top.addView(icon,new LinearLayout.LayoutParams(ClientUi.dp(this,34),ClientUi.dp(this,34)));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(ClientUi.dp(this,8),0,0,0);info.addView(ClientUi.text(this,event.protocol+" · "+event.endpoint,14,ClientUi.NAVY,true));String date=new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault()).format(new Date(event.timestamp));info.addView(ClientUi.text(this,event.type+" · "+event.status+(event.latencyMs>0?" · "+event.latencyMs+" мс":"")+" · "+date,11,ClientUi.MUTED,false));if(event.message.length()>0)info.addView(ClientUi.text(this,event.message,11,ClientUi.MUTED,false));top.addView(info,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));card.addView(top);body.addView(card,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,4,0,4));}
        if(shown==0){LinearLayout empty=ClientUi.card(this,16);TextView t=ClientUi.text(this,"Журнал пока пуст",16,ClientUi.MUTED,true);t.setGravity(Gravity.CENTER);empty.addView(t);body.addView(empty);}TextView clear=ClientUi.button(this,"Очистить журнал",false);clear.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){ConnectionSessionStore.clear(ConnectionsActivity.this);render();}});body.addView(clear,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,44),0,10,0,0));}

    private void checkProfile(final ConnectionProfileStore.Profile profile, final LinearLayout card){final ProgressBar progress=new ProgressBar(this);card.addView(progress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,34)));new Thread(new Runnable(){@Override public void run(){final EndpointProbe.Result result=EndpointProbe.probe(profile.endpoint(),5000);ConnectionProfileStore.updateHealth(ConnectionsActivity.this,profile.id,result);ConnectionSessionStore.recordHealth(ConnectionsActivity.this,profile,result);runOnUiThread(new Runnable(){@Override public void run(){Toast.makeText(ConnectionsActivity.this,result.reachable?"Сервис доступен":"Сервис недоступен",Toast.LENGTH_SHORT).show();render();}});}}).start();}

    private void checkAllProfiles(){if(bulkChecking)return;final List<ConnectionProfileStore.Profile> profiles=ConnectionProfileStore.list(this);if(profiles.isEmpty()){Toast.makeText(this,"Нет сохранённых подключений",Toast.LENGTH_SHORT).show();return;}bulkChecking=true;Toast.makeText(this,"Проверяем "+profiles.size()+" подключений…",Toast.LENGTH_SHORT).show();new Thread(new Runnable(){@Override public void run(){for(ConnectionProfileStore.Profile p:profiles){EndpointProbe.Result r=EndpointProbe.probe(p.endpoint(),4500);ConnectionProfileStore.updateHealth(ConnectionsActivity.this,p.id,r);ConnectionSessionStore.recordHealth(ConnectionsActivity.this,p,r);}bulkChecking=false;runOnUiThread(new Runnable(){@Override public void run(){render();Toast.makeText(ConnectionsActivity.this,"Проверка завершена",Toast.LENGTH_SHORT).show();}});}}).start();}

    private void profileMenu(final ConnectionProfileStore.Profile profile){String[] items={"Изменить название, заметки и теги",profile.pinned?"Открепить":"Закрепить","Скопировать адрес","Удалить профиль"};new AlertDialog.Builder(this).setTitle(profile.name).setItems(items,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int which){if(which==0)editProfile(profile);else if(which==1){ConnectionProfileStore.updateMetadata(ConnectionsActivity.this,profile.id,profile.name,profile.notes,profile.tags,!profile.pinned);render();}else if(which==2){ProtocolRouter.copy(ConnectionsActivity.this,endpointText(profile));Toast.makeText(ConnectionsActivity.this,"Адрес скопирован",Toast.LENGTH_SHORT).show();}else{ConnectionProfileStore.remove(ConnectionsActivity.this,profile.id);render();}}}).show();}

    private void editProfile(final ConnectionProfileStore.Profile profile){LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int pad=ClientUi.dp(this,18);form.setPadding(pad,0,pad,0);final EditText name=ClientUi.input(this,"Название");name.setText(profile.name);final EditText tags=ClientUi.input(this,"Теги через запятую");tags.setText(profile.tags);final EditText notes=new EditText(this);notes.setHint("Заметки");notes.setText(profile.notes);notes.setMinLines(3);notes.setGravity(Gravity.TOP);form.addView(name,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,48)));form.addView(tags,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,48),0,8,0,0));form.addView(notes,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ClientUi.dp(this,100),0,8,0,0));final CheckBox pinned=new CheckBox(this);pinned.setText("Закрепить профиль");pinned.setChecked(profile.pinned);form.addView(pinned);new AlertDialog.Builder(this).setTitle("Профиль подключения").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){ConnectionProfileStore.updateMetadata(ConnectionsActivity.this,profile.id,name.getText().toString(),notes.getText().toString(),tags.getText().toString(),pinned.isChecked());render();}}).show();}

    private TextView statusBadge(ConnectionProfileStore.Profile p){boolean manual="MANUAL".equals(p.lastStatus);String text=p.lastStatus.length()==0?"не проверен":manual?"в клиенте":"ONLINE".equals(p.lastStatus)?"доступен":"недоступен";int color=p.lastStatus.length()==0||manual?ClientUi.MUTED:"ONLINE".equals(p.lastStatus)?ClientUi.GREEN:ClientUi.RED;int bg=p.lastStatus.length()==0||manual?Color.rgb(238,241,246):"ONLINE".equals(p.lastStatus)?Color.rgb(228,247,238):Color.rgb(255,234,236);TextView badge=ClientUi.text(this,text,10,color,true);badge.setGravity(Gravity.CENTER);badge.setBackground(ClientUi.round(this,bg,99,Color.TRANSPARENT,0));return badge;}
    private boolean matches(ConnectionProfileStore.Profile p,String q){if(q.length()==0)return true;return (p.name+" "+p.protocol+" "+p.host+" "+p.port+" "+p.uri+" "+p.tags+" "+p.notes).toLowerCase(Locale.US).contains(q);}
    private String query(){return search==null?"":search.getText().toString().trim().toLowerCase(Locale.US);}
    private String endpointText(ConnectionProfileStore.Profile p){return p.uri.length()>0?p.uri:p.host+":"+p.port;}
    private String protocolIcon(String protocol){if("HTTP".equals(protocol)||"HTTPS".equals(protocol))return"🌐";if("SSH".equals(protocol)||"SFTP".equals(protocol))return">_";if("RDP".equals(protocol)||"VNC".equals(protocol))return"▣";if("RTSP".equals(protocol))return"▶";if("SMB".equals(protocol)||"FTP".equals(protocol)||"FTPS".equals(protocol)||"WEBDAV".equals(protocol))return"▤";if("MQTT".equals(protocol))return"↔";if("SNMP".equals(protocol))return"Σ";return"◎";}
    private View space(int dp){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(ClientUi.dp(this,dp),1));return v;}
}
