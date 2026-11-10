package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Professional hosts editor with systemless root and no-root DNS-VPN apply modes. */
public final class HostsActivity extends Activity {
    private static final int REQUEST_VPN=601;
    private static final int REQUEST_IMPORT=602;
    private static final int REQUEST_EXPORT=603;

    private HostsStore store;
    private HostsStore.Profile profile;
    private HostsRootManager.Capability capability;
    private String systemText="";
    private TextView stateTitle;
    private TextView stateDetails;
    private TextView count;
    private TextView xraySummary;
    private Spinner xrayProfileSpinner;
    private Spinner profileSpinner;
    private TextView applyButton;
    private TextView stopVpnButton;
    private TextView xrayTestButton;
    private TextView xrayPlusButton;
    private TextView xrayMenuButton;
    private EntryAdapter adapter;
    private boolean spinnerBinding;
    private boolean xraySpinnerBinding;
    private volatile boolean xrayTestRunning;
    private String renderedLifecycle=HostsVpnService.STATE_OFF;
    private boolean pendingVpnStart;
    private String pendingVpnMode=HostsVpnService.MODE_BASIC;
    private String pendingExport="";
    private XrayProfileStore xrayProfiles;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private boolean statePolling;
    private final Runnable statePoll = new Runnable() { @Override public void run() {
        if (!statePolling) return;
        refreshState(); refreshXraySummary();
        stateHandler.postDelayed(this, 1000L);
    } };

    @Override public void onCreate(Bundle state){
        VpnStateRecovery.recoverIfStale(this);
        super.onCreate(state);getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store=new HostsStore(this);xrayProfiles=new XrayProfileStore(this);profile=store.activeProfile();buildUi();detectAndLoad();
    }

    @Override protected void onResume(){super.onResume();bindXrayProfiles();refreshState();refreshXraySummary();if(adapter!=null)adapter.reload();statePolling=true;stateHandler.removeCallbacks(statePoll);stateHandler.post(statePoll);}
    @Override protected void onPause(){statePolling=false;stateHandler.removeCallbacks(statePoll);super.onPause();}

    private void buildUi(){
        LinearLayout root=UiKit.page(this);root.setPadding(UiKit.dp(this,18),UiKit.dp(this,18),UiKit.dp(this,18),UiKit.dp(this,12));
        root.addView(UiKit.header(this,"H","Hosts Manager","Локальные доменные правила Android"));

        LinearLayout state=UiKit.card(this,15);state.setLayoutParams(UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,5));
        LinearLayout titleRow=new LinearLayout(this);titleRow.setOrientation(LinearLayout.HORIZONTAL);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout stateText=new LinearLayout(this);stateText.setOrientation(LinearLayout.VERTICAL);stateTitle=UiKit.text(this,"Проверка доступа…",16,UiKit.NAVY,true);stateDetails=UiKit.text(this,"Определяю root, Magisk и активный режим",12,UiKit.MUTED,false);stateText.addView(stateTitle);stateText.addView(stateDetails);titleRow.addView(stateText,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView refresh=UiKit.button(this,"Обновить",false);refresh.setOnClickListener(v->detectAndLoad());titleRow.addView(refresh,new LinearLayout.LayoutParams(UiKit.dp(this,105),UiKit.dp(this,42)));state.addView(titleRow);
        LinearLayout stateActions=new LinearLayout(this);stateActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView load=UiKit.button(this,"Загрузить системный hosts",false);load.setOnClickListener(v->importSystemHosts());
        TextView backups=UiKit.button(this,"Резервные копии",false);backups.setOnClickListener(v->showBackups());
        stateActions.addView(load,new LinearLayout.LayoutParams(0,UiKit.dp(this,46),1.35f));stateActions.addView(space(8),new LinearLayout.LayoutParams(UiKit.dp(this,8),1));stateActions.addView(backups,new LinearLayout.LayoutParams(0,UiKit.dp(this,46),1f));
        state.addView(stateActions,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,0));root.addView(state);

        LinearLayout xray=UiKit.card(this,14);
        xray.addView(UiKit.text(this,"Комбинированный режим Hosts + Xray",15,UiKit.NAVY,true));
        LinearLayout xrayProfileRow=new LinearLayout(this);xrayProfileRow.setOrientation(LinearLayout.HORIZONTAL);xrayProfileRow.setGravity(Gravity.CENTER_VERTICAL);
        xrayProfileSpinner=new Spinner(this);xrayProfileRow.addView(xrayProfileSpinner,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));
        xrayPlusButton=UiKit.button(this,"＋",false);xrayPlusButton.setTextSize(22);xrayPlusButton.setOnClickListener(v->editXrayProfile(false));xrayProfileRow.addView(xrayPlusButton,new LinearLayout.LayoutParams(UiKit.dp(this,52),UiKit.dp(this,46)));
        xrayMenuButton=UiKit.button(this,"⋮",false);xrayMenuButton.setTextSize(22);xrayMenuButton.setOnClickListener(v->xrayProfileMenu());xrayProfileRow.addView(xrayMenuButton,new LinearLayout.LayoutParams(UiKit.dp(this,52),UiKit.dp(this,46)));
        xray.addView(xrayProfileRow,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,5,0,0));
        xraySummary=UiKit.text(this,"Профили Xray не заданы",12,UiKit.MUTED,false);
        xray.addView(xraySummary,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,3,0,8));
        LinearLayout xrayActions=new LinearLayout(this);xrayActions.setOrientation(LinearLayout.HORIZONTAL);
        xrayTestButton=UiKit.button(this,"Проверить выбранный",false);xrayTestButton.setOnClickListener(v->testXrayProfile());
        TextView logButton=UiKit.button(this,"Журнал",false);logButton.setOnClickListener(v->showVpnDiagnostics());
        xrayActions.addView(xrayTestButton,new LinearLayout.LayoutParams(0,UiKit.dp(this,44),1.35f));xrayActions.addView(space(6),new LinearLayout.LayoutParams(UiKit.dp(this,6),1));xrayActions.addView(logButton,new LinearLayout.LayoutParams(0,UiKit.dp(this,44),1f));
        xray.addView(xrayActions);root.addView(xray,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,5,0,5));

        LinearLayout profiles=UiKit.card(this,14);LinearLayout pRow=new LinearLayout(this);pRow.setOrientation(LinearLayout.HORIZONTAL);pRow.setGravity(Gravity.CENTER_VERTICAL);
        profileSpinner=new Spinner(this);pRow.addView(profileSpinner,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));
        TextView plus=UiKit.button(this,"＋",false);plus.setTextSize(22);plus.setOnClickListener(v->createProfile());pRow.addView(plus,new LinearLayout.LayoutParams(UiKit.dp(this,52),UiKit.dp(this,46)));
        TextView menu=UiKit.button(this,"⋮",false);menu.setTextSize(22);menu.setOnClickListener(v->profileMenu());pRow.addView(menu,new LinearLayout.LayoutParams(UiKit.dp(this,52),UiKit.dp(this,46)));profiles.addView(pRow);
        count=UiKit.text(this,"",12,UiKit.MUTED,false);profiles.addView(count,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,2,6,2,0));root.addView(profiles,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,5,0,5));

        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.HORIZONTAL);
        TextView add=UiKit.button(this,"＋ Запись",true);add.setOnClickListener(v->editEntry(null));
        TextView paste=UiKit.button(this,"Вставить блок",false);paste.setOnClickListener(v->bulkPaste());
        TextView raw=UiKit.button(this,"Сырой текст",false);raw.setOnClickListener(v->rawEditor());
        tools.addView(add,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));tools.addView(space(7),new LinearLayout.LayoutParams(UiKit.dp(this,7),1));tools.addView(paste,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));tools.addView(space(7),new LinearLayout.LayoutParams(UiKit.dp(this,7),1));tools.addView(raw,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));root.addView(tools,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,5,0,7));

        adapter=new EntryAdapter();ListView list=new ListView(this);list.setAdapter(adapter);list.setDividerHeight(0);list.setPadding(0,0,0,UiKit.dp(this,4));list.setClipToPadding(false);root.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout bottom=UiKit.card(this,12);LinearLayout row1=new LinearLayout(this);row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView validate=UiKit.button(this,"Проверить",false);validate.setOnClickListener(v->showValidation());TextView preview=UiKit.button(this,"Предпросмотр",false);preview.setOnClickListener(v->preview());applyButton=UiKit.button(this,"Применить",true);applyButton.setOnClickListener(v->chooseApplyMode());
        row1.addView(validate,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));row1.addView(space(7),new LinearLayout.LayoutParams(UiKit.dp(this,7),1));row1.addView(preview,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));row1.addView(space(7),new LinearLayout.LayoutParams(UiKit.dp(this,7),1));row1.addView(applyButton,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1.15f));bottom.addView(row1);
        LinearLayout row2=new LinearLayout(this);row2.setOrientation(LinearLayout.HORIZONTAL);row2.setGravity(Gravity.CENTER_VERTICAL);TextView importFile=UiKit.button(this,"Импорт файла",false);importFile.setOnClickListener(v->openImport());TextView exportFile=UiKit.button(this,"Экспорт .hosts",false);exportFile.setOnClickListener(v->openExport(currentCandidate()));stopVpnButton=UiKit.button(this,"Отключить VPN",false);stopVpnButton.setSingleLine(true);stopVpnButton.setMaxLines(1);stopVpnButton.setTextSize(13);stopVpnButton.setGravity(Gravity.CENTER);stopVpnButton.setOnClickListener(v->stopVpn());row2.addView(importFile,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));row2.addView(space(6),new LinearLayout.LayoutParams(UiKit.dp(this,6),1));row2.addView(exportFile,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));row2.addView(space(6),new LinearLayout.LayoutParams(UiKit.dp(this,6),1));row2.addView(stopVpnButton,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));bottom.addView(row2,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,8,0,0));root.addView(bottom);
        setContentView(root);bindProfiles();bindXrayProfiles();refreshActionButtons();
    }

    private View space(int dp){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this,dp),1));return v;}

    private void bindProfiles(){spinnerBinding=true;List<HostsStore.Profile> profiles=store.profiles();List<String> names=new ArrayList<String>();int selected=0;for(int i=0;i<profiles.size();i++){HostsStore.Profile p=profiles.get(i);names.add((p.active?"● ":"")+p.name);if(profile!=null&&p.id.equals(profile.id))selected=i;}ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names);profileSpinner.setAdapter(a);profileSpinner.setSelection(selected);profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){if(spinnerBinding)return;List<HostsStore.Profile> current=store.profiles();if(position>=0&&position<current.size()){profile=current.get(position);store.activate(profile.id);adapter.reload();refreshCount();}}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}});spinnerBinding=false;refreshCount();}

    private void refreshCount(){if(profile==null)return;List<HostsEntry> rows=store.entries(profile.id);int enabled=0;for(HostsEntry e:rows)if(e.enabled)enabled++;count.setText("Записей: "+rows.size()+" · активно: "+enabled+" · профиль: "+profile.name);}

    private void detectAndLoad(){stateTitle.setText("Проверка доступа…");stateDetails.setText("Определяю root, Magisk и системный hosts");new Thread(()->{HostsRootManager.Capability c=HostsRootManager.detect();String text="";try{text=HostsRootManager.readCurrent(c);}catch(Exception ignored){}String finalText=text;runOnUiThread(()->{capability=c;if(finalText.length()>0)systemText=finalText;refreshState();});},"HostsCapability").start();}

    private void refreshState(){
        VpnStateRecovery.recoverIfStale(this);
        android.content.SharedPreferences prefs=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);
        boolean vpn=prefs.getBoolean("active",false);
        String lifecycle=prefs.getString(HostsVpnService.KEY_STATE,vpn?HostsVpnService.STATE_ACTIVE:HostsVpnService.STATE_OFF);
        String vpnMessage=prefs.getString("message","");
        String vpnMode=prefs.getString("mode","");
        long heartbeat=prefs.getLong("heartbeatAt",0L);
        long changedAt=prefs.getLong("changedAt",0L);
        long now=System.currentTimeMillis();
        // The service is the sole lifecycle authority. The UI must never turn STARTING
        // into OFF/ERROR by editing SharedPreferences while a native operation is alive.
        if(HostsVpnService.STATE_ACTIVE.equals(lifecycle)&&(heartbeat<=0L||now-heartbeat>20000L)){
            vpnMessage="Ожидание heartbeat службы…";
        }else if(HostsVpnService.STATE_STARTING.equals(lifecycle)&&changedAt>0L&&now-changedAt>45000L){
            vpnMessage="Запуск занимает слишком долго · нажмите красную кнопку «Отменить»";
        }else if(HostsVpnService.STATE_STOPPING.equals(lifecycle)&&changedAt>0L&&now-changedAt>15000L){
            vpnMessage="Ожидание завершения службы…";
        }
        renderedLifecycle=lifecycle;
        if(capability==null){stateTitle.setText("Проверка доступа…");refreshActionButtons(lifecycle);return;}
        String mode=capability.magisk?"Root + Magisk":capability.root?"Root без Magisk":"Без root";
        if(HostsVpnService.STATE_STARTING.equals(lifecycle))mode+=" · VPN запускается";
        else if(HostsVpnService.STATE_STOPPING.equals(lifecycle))mode+=" · VPN останавливается";
        else if(vpn&&HostsVpnService.STATE_ACTIVE.equals(lifecycle))mode+=" · "+("XRAY".equals(vpnMode)?"Hosts + Xray":"Hosts BASIC");
        else if(HostsVpnService.STATE_ERROR.equals(lifecycle))mode+=" · ошибка VPN";
        stateTitle.setText(mode);
        String details=(capability.readable?"Файл: "+capability.path:"Системный файл недоступен")+"\n"+capability.message+(vpnMessage.length()>0?"\n"+vpnMessage:"");
        String endpoint=prefs.getString("endpoint","");String core=prefs.getString("coreVersion","");
        if(vpn&&endpoint.length()>0)details+="\nСервер: "+endpoint;if(vpn&&core.length()>0)details+="\nXray core: "+core;
        stateDetails.setText(details);refreshActionButtons(lifecycle);
    }

    private String vpnLifecycle(){android.content.SharedPreferences prefs=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);boolean active=prefs.getBoolean("active",false);return prefs.getString(HostsVpnService.KEY_STATE,active?HostsVpnService.STATE_ACTIVE:HostsVpnService.STATE_OFF);}

    private void refreshActionButtons(){refreshActionButtons(vpnLifecycle());}
    private void refreshActionButtons(String lifecycle){
        if(applyButton==null||stopVpnButton==null)return;
        boolean starting=HostsVpnService.STATE_STARTING.equals(lifecycle);
        boolean active=HostsVpnService.STATE_ACTIVE.equals(lifecycle);
        boolean stopping=HostsVpnService.STATE_STOPPING.equals(lifecycle);
        boolean applyEnabled=!starting&&!active&&!stopping;
        boolean stopEnabled=starting||active;
        applyButton.setText(starting?"Запуск…":active?"VPN активен":stopping?"Остановка…":"Применить");
        stopVpnButton.setText(starting?"Отменить":stopping?"Остановка…":"Отключить VPN");
        stopVpnButton.setContentDescription(starting?"Отменить запуск":stopping?"Остановка VPN":"Отключить VPN");
        styleActionButton(applyButton,applyEnabled,true);
        styleActionButton(stopVpnButton,stopEnabled,false);
        boolean testEnabled=applyEnabled&&!xrayTestRunning&&!XrayProfilePreflight.isRunning()&&xrayProfiles!=null&&xrayProfiles.configured();
        if(xrayTestButton!=null)styleSecondaryButton(xrayTestButton,testEnabled);
        boolean profileControlsEnabled=!starting&&!stopping;
        if(xrayProfileSpinner!=null)xrayProfileSpinner.setEnabled(profileControlsEnabled);
        if(xrayPlusButton!=null)styleSecondaryButton(xrayPlusButton,profileControlsEnabled);
        if(xrayMenuButton!=null)styleSecondaryButton(xrayMenuButton,profileControlsEnabled);
    }

    private void styleActionButton(TextView button,boolean enabled,boolean apply){
        button.setEnabled(enabled);button.setClickable(enabled);button.setAlpha(1f);
        if(!enabled){button.setTextColor(UiKit.MUTED);button.setBackground(UiKit.round(Color.rgb(242,245,249),14,UiKit.BORDER,1,this));return;}
        button.setTextColor(Color.WHITE);button.setBackground(UiKit.round(apply?UiKit.PRIMARY:UiKit.RED,14,apply?UiKit.PRIMARY:UiKit.RED,1,this));
    }
    private void styleSecondaryButton(TextView button,boolean enabled){button.setEnabled(enabled);button.setClickable(enabled);button.setAlpha(1f);button.setTextColor(enabled?UiKit.PRIMARY:UiKit.MUTED);button.setBackground(UiKit.round(enabled?Color.WHITE:Color.rgb(242,245,249),14,UiKit.BORDER,1,this));}

    private void importSystemHosts(){if(systemText.length()>0){confirmImportText(systemText,"системного hosts");return;}detectAndLoad();Toast.makeText(this,"Сначала выполняется проверка доступа",Toast.LENGTH_SHORT).show();}

    private void confirmImportText(String raw,String source){HostsParser.Result parsed=HostsParser.parse(raw,profile.id);String message="Найдено записей: "+parsed.entries.size()+"\nЗамена затронет текущий профиль. Комментарии вне записей доступны в сыром редакторе."+(parsed.issues.isEmpty()?"":"\nЗамечаний: "+parsed.issues.size());new AlertDialog.Builder(this).setTitle("Импорт из "+source).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Импортировать",(d,w)->{store.replaceEntries(profile.id,parsed.entries);adapter.reload();refreshCount();showIssuesIfNeeded(parsed);}).show();}

    private void editEntry(final HostsEntry source){final HostsEntry entry=source==null?new HostsEntry():copy(source);LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(UiKit.dp(this,18),0,UiKit.dp(this,18),0);EditText ip=UiKit.input(this,"128.0.3.22 или IPv6");ip.setText(entry.address);EditText hosts=UiKit.input(this,"sige.ru www.sige.ru");hosts.setText(join(entry.hostnames));EditText comment=UiKit.input(this,"Комментарий");comment.setText(entry.comment);CheckBox enabled=new CheckBox(this);enabled.setText("Запись активна");enabled.setChecked(entry.enabled);form.addView(labelField("IP-адрес",ip));form.addView(labelField("Имена узлов через пробел",hosts));form.addView(labelField("Комментарий",comment));form.addView(enabled);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(source==null?"Новая hosts-запись":"Редактирование записи").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String line=ip.getText().toString().trim()+" "+hosts.getText().toString().trim()+(comment.getText().toString().trim().length()>0?" # "+comment.getText().toString().trim():"");HostsParser.Result parsed=HostsParser.parse(line,profile.id);if(parsed.hasErrors()||parsed.entries.isEmpty()){showIssues(parsed,"Запись не сохранена");return;}HostsEntry normalized=parsed.entries.get(0);entry.address=normalized.address;entry.hostnames.clear();entry.hostnames.addAll(normalized.hostnames);entry.comment=comment.getText().toString().trim();entry.enabled=enabled.isChecked();entry.profileId=profile.id;entry.source="manual";store.saveEntry(entry);adapter.reload();refreshCount();dialog.dismiss();if(!parsed.issues.isEmpty())showIssues(parsed,"Запись нормализована");}));dialog.show();}

    private View labelField(String title,EditText input){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(UiKit.text(this,title,12,UiKit.MUTED,true));box.addView(input,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,52)));box.setPadding(0,UiKit.dp(this,6),0,UiKit.dp(this,6));return box;}

    private void bulkPaste(){EditText raw=multiline("128.0.3.22 sige.ru\n0.0.0.0 ads.example.org # блокировка");new AlertDialog.Builder(this).setTitle("Вставить hosts-блок").setView(raw).setNegativeButton("Отмена",null).setPositiveButton("Добавить",(d,w)->{HostsParser.Result parsed=HostsParser.parse(raw.getText().toString(),profile.id);if(parsed.hasErrors()){showIssues(parsed,"Импорт содержит ошибки");return;}List<HostsEntry> all=store.entries(profile.id);all.addAll(parsed.entries);store.replaceEntries(profile.id,all);adapter.reload();refreshCount();showIssuesIfNeeded(parsed);}).show();}

    private void rawEditor(){EditText raw=multiline(HostsParser.buildManagedBlock(store.entries(profile.id)));new AlertDialog.Builder(this).setTitle("Сырой редактор профиля").setMessage("Редактируется управляемый блок. Формат: IP, пробел, одно или несколько имён, затем необязательный # комментарий.").setView(raw).setNegativeButton("Отмена",null).setPositiveButton("Проверить и сохранить",(d,w)->{HostsParser.Result parsed=HostsParser.parse(raw.getText().toString(),profile.id);if(parsed.hasErrors()){showIssues(parsed,"Файл не сохранён");return;}store.replaceEntries(profile.id,parsed.entries);adapter.reload();refreshCount();showIssuesIfNeeded(parsed);}).show();}

    private EditText multiline(String value){EditText e=new EditText(this);e.setText(value);e.setTextSize(13);e.setTextColor(UiKit.NAVY);e.setGravity(Gravity.TOP|Gravity.START);e.setMinLines(10);e.setHorizontallyScrolling(true);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setPadding(UiKit.dp(this,16),UiKit.dp(this,12),UiKit.dp(this,16),UiKit.dp(this,12));return e;}

    private HostsParser.Result validation(){return HostsParser.parse(HostsParser.buildManagedBlock(store.entries(profile.id)),profile.id);}
    private void showValidation(){HostsParser.Result result=validation();showIssues(result,result.hasErrors()?"Проверка не пройдена":"Профиль валиден");}
    private void showIssuesIfNeeded(HostsParser.Result result){if(!result.issues.isEmpty())showIssues(result,"Импорт завершён с замечаниями");}
    private void showIssues(HostsParser.Result result,String title){StringBuilder msg=new StringBuilder();msg.append("Записей: ").append(result.entries.size()).append(" · активно: ").append(result.enabledCount()).append('\n');if(result.issues.isEmpty())msg.append("Ошибок и конфликтов не найдено.");else for(HostsParser.Issue issue:result.issues)msg.append("\n• ").append(issue.toString());new AlertDialog.Builder(this).setTitle(title).setMessage(msg.toString()).setPositiveButton("Закрыть",null).show();}

    private String currentCandidate(){String base=systemText.length()>0?systemText:"127.0.0.1 localhost\n::1 localhost\n";return HostsParser.mergeManagedBlock(base,store.entries(profile.id));}
    private void preview(){HostsParser.Result check=validation();if(check.hasErrors()){showIssues(check,"Сначала исправьте ошибки");return;}String current=systemText.length()>0?systemText:"Системный файл ещё не загружен";String candidate=currentCandidate();TextView text=UiKit.text(this,"ТЕКУЩИЙ SHA-256\n"+safeHash(current)+"\n\nНОВЫЙ SHA-256\n"+safeHash(candidate)+"\n\n--- КАНДИДАТ ---\n"+candidate,12,UiKit.NAVY,false);text.setTextIsSelectable(true);android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setPadding(UiKit.dp(this,16),UiKit.dp(this,8),UiKit.dp(this,16),UiKit.dp(this,8));scroll.addView(text);new AlertDialog.Builder(this).setTitle("Предпросмотр применения").setView(scroll).setNegativeButton("Закрыть",null).setPositiveButton("Экспорт",(d,w)->openExport(candidate)).show();}

    private void chooseApplyMode(){HostsParser.Result check=validation();if(check.hasErrors()){showIssues(check,"Применение заблокировано");return;}List<String> labels=new ArrayList<String>();List<String> modes=new ArrayList<String>();if(capability!=null&&capability.root&&capability.magisk){labels.add("Systemless через Magisk · постоянно");modes.add("MAGISK");}if(capability!=null&&capability.root){labels.add("Root bind-mount · до перезагрузки");modes.add("ROOT_TEMP");}labels.add("BASIC · только Hosts через локальный DNS-VPN");modes.add(HostsVpnService.MODE_BASIC);labels.add("XRAY · Hosts + весь трафик через один профиль");modes.add(HostsVpnService.MODE_XRAY);labels.add("Только экспортировать .hosts");modes.add("EXPORT");new AlertDialog.Builder(this).setTitle("Способ применения").setItems(labels.toArray(new String[0]),(d,which)->applyMode(modes.get(which))).setNegativeButton("Отмена",null).show();}

    private void applyMode(String mode){if(HostsVpnService.MODE_BASIC.equals(mode)||HostsVpnService.MODE_XRAY.equals(mode)){confirmVpn(mode);return;}if("EXPORT".equals(mode)){openExport(currentCandidate());return;}String candidate=currentCandidate();new AlertDialog.Builder(this).setTitle("Подтверждение системного изменения").setMessage("Перед применением будет создана резервная копия. Системный раздел напрямую не перемонтируется.\n\nРежим: "+mode).setNegativeButton("Отмена",null).setPositiveButton("Применить",(d,w)->runRootApply(mode,candidate)).show();}

    private void runRootApply(String mode,String ignoredCandidate){stateTitle.setText("Применение профиля…");new Thread(()->{String candidate;try{String latest=HostsRootManager.readCurrent(capability);candidate=HostsParser.mergeManagedBlock(latest,store.entries(profile.id));}catch(Exception e){candidate=currentCandidate();}final String appliedCandidate=candidate;HostsRootManager.ApplyResult result="MAGISK".equals(mode)?HostsRootManager.applyMagisk(this,store,profile.id,appliedCandidate,capability):HostsRootManager.applyTemporary(this,store,profile.id,appliedCandidate,capability);runOnUiThread(()->{new AlertDialog.Builder(this).setTitle(result.success?"Hosts применён":"Ошибка применения").setMessage(result.message+(result.backup!=null?"\n\nРезервная копия: "+new FileLabel(result.backup.path).name:"")).setPositiveButton("Закрыть",null).show();if(result.success)systemText=appliedCandidate;detectAndLoad();});},"HostsRootApply").start();}

    private void confirmVpn(String mode){if(HostsVpnService.MODE_XRAY.equals(mode)&&!xrayProfiles.configured()){new AlertDialog.Builder(this).setTitle("Профиль Xray не задан").setMessage("Сначала вставьте VLESS, VMess, Trojan, Shadowsocks-ссылку или полный Xray JSON.").setPositiveButton("Открыть профиль",(d,w)->editXrayProfile(false)).setNegativeButton("Отмена",null).show();return;}String title=HostsVpnService.MODE_XRAY.equals(mode)?"Hosts + Xray":"Локальный Hosts DNS-VPN";String message=HostsVpnService.MODE_XRAY.equals(mode)?"Android допускает только одно активное VPN. Весь IPv4/IPv6-трафик будет направлен через выбранный Xray-профиль, а hosts-правила будут обработаны внутри того же туннеля.\n\nSiteScope проверит, что предыдущий VPN полностью отключён, и закрепит Xray за стабильной Wi‑Fi или мобильной сетью. Секрет профиля хранится через Android Keystore.":"Android допускает только одно активное VPN. BASIC перехватывает системные DNS-запросы A/AAAA, но приложения со встроенным DoH или прямым IP могут обходить hosts-правила.";new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(d,w)->requestVpnPermissionAndStart(mode)).show();}

    private void requestVpnPermissionAndStart(String mode){
        pendingVpnMode=mode;
        VpnNetworkCoordinator.Snapshot network=VpnNetworkCoordinator.snapshot(this);
        String lifecycle=vpnLifecycle();
        boolean ownVpnBusy=HostsVpnService.STATE_STARTING.equals(lifecycle)||HostsVpnService.STATE_ACTIVE.equals(lifecycle)||HostsVpnService.STATE_STOPPING.equals(lifecycle);
        if(network.vpnPresent&&!ownVpnBusy){
            new AlertDialog.Builder(this).setTitle("Обнаружен другой VPN").setMessage("Android не разрешает двум VPN работать одновременно. SiteScope не может принудительно остановить VPN другого приложения.\n\nМожно открыть системные настройки либо продолжить: SiteScope подождёт до 15 секунд, пока предыдущий туннель полностью исчезнет.")
                .setNegativeButton("Отмена",null)
                .setNeutralButton("Настройки VPN",(d,w)->openVpnSettings())
                .setPositiveButton("Подождать и запустить",(d,w)->prepareAndStartVpn(mode)).show();
            return;
        }
        prepareAndStartVpn(mode);
    }

    private void prepareAndStartVpn(String mode){
        pendingVpnMode=mode;Intent prepare=VpnService.prepare(this);
        if(prepare!=null){pendingVpnStart=true;startActivityForResult(prepare,REQUEST_VPN);}else startVpn(mode);
    }

    private void openVpnSettings(){
        try{startActivity(VpnNetworkCoordinator.settingsIntent());}
        catch(Exception e){try{startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));}catch(Exception ignored){Toast.makeText(this,"Откройте Настройки → VPN",Toast.LENGTH_LONG).show();}}
    }
    private void startVpn(String mode){
        XrayProfilePreflight.cancel();
        xrayTestRunning=false;
        XrayProfileStore.Info selected=xrayProfiles.info();
        getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE).edit().putString(HostsVpnService.KEY_STATE,HostsVpnService.STATE_STARTING)
            .putBoolean("active",false).putString("mode",mode).putString("message","Запуск "+mode+"…")
            .putString("profileId",HostsVpnService.MODE_XRAY.equals(mode)?selected.id:"").putLong("changedAt",System.currentTimeMillis()).putLong("heartbeatAt",0L).apply();
        Intent intent=new Intent(this,HostsVpnService.class).setAction(HostsVpnService.ACTION_START).putExtra(HostsVpnService.EXTRA_MODE,mode);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
        Toast.makeText(this,(HostsVpnService.MODE_XRAY.equals(mode)?"Hosts + Xray":"Hosts BASIC")+" запускается",Toast.LENGTH_SHORT).show();refreshState();
    }
    private void stopVpn(){
        String lifecycle=vpnLifecycle();if(!HostsVpnService.STATE_ACTIVE.equals(lifecycle)&&!HostsVpnService.STATE_STARTING.equals(lifecycle))return;
        getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE).edit().putString(HostsVpnService.KEY_STATE,HostsVpnService.STATE_STOPPING).putBoolean("active",false).putString("message","Остановка VPN…").putLong("changedAt",System.currentTimeMillis()).apply();
        startService(new Intent(this,HostsVpnService.class).setAction(HostsVpnService.ACTION_STOP));Toast.makeText(this,"VPN останавливается",Toast.LENGTH_SHORT).show();refreshState();
    }

    private void showBackups(){List<HostsStore.Backup> list=store.backups();if(list.isEmpty()){Toast.makeText(this,"Резервных копий пока нет",Toast.LENGTH_SHORT).show();return;}String[] labels=new String[list.size()];SimpleDateFormat format=new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault());for(int i=0;i<list.size();i++){HostsStore.Backup b=list.get(i);labels[i]=format.format(new Date(b.timestamp))+" · "+b.mode+" · "+b.size+" байт";}new AlertDialog.Builder(this).setTitle("Резервные копии hosts").setItems(labels,(d,which)->backupActions(list.get(which))).setNegativeButton("Закрыть",null).show();}
    private void backupActions(HostsStore.Backup backup){String[] actions={"Просмотреть","Экспортировать","Восстановить через доступный root-режим"};new AlertDialog.Builder(this).setTitle(new FileLabel(backup.path).name).setMessage("SHA-256: "+backup.sha256+"\nИсточник: "+backup.sourcePath).setItems(actions,(d,w)->{if(w==0)showBackupText(backup);else if(w==1)exportBackup(backup);else restoreBackup(backup);}).setNegativeButton("Закрыть",null).show();}
    private void showBackupText(HostsStore.Backup backup){try{String raw=readUri(Uri.fromFile(new java.io.File(backup.path)));TextView t=UiKit.text(this,raw,12,UiKit.NAVY,false);t.setTextIsSelectable(true);android.widget.ScrollView s=new android.widget.ScrollView(this);s.setPadding(UiKit.dp(this,16),UiKit.dp(this,8),UiKit.dp(this,16),UiKit.dp(this,8));s.addView(t);new AlertDialog.Builder(this).setTitle("Резервная копия").setView(s).setPositiveButton("Закрыть",null).show();}catch(Exception e){toastError(e);}}
    private void exportBackup(HostsStore.Backup backup){try{pendingExport=readUri(Uri.fromFile(new java.io.File(backup.path)));openExport(pendingExport);}catch(Exception e){toastError(e);}}
    private void restoreBackup(HostsStore.Backup backup){if(capability==null||!capability.root){Toast.makeText(this,"Для восстановления системного файла нужен root",Toast.LENGTH_LONG).show();return;}boolean magisk=capability.magisk;new AlertDialog.Builder(this).setTitle("Восстановить hosts?").setMessage("Текущий файл также будет сохранён. Режим: "+(magisk?"Magisk":"временный root")).setNegativeButton("Отмена",null).setPositiveButton("Восстановить",(d,w)->new Thread(()->{HostsRootManager.ApplyResult r=HostsRootManager.restoreBackup(this,store,profile.id,backup,capability,magisk);runOnUiThread(()->{new AlertDialog.Builder(this).setTitle(r.success?"Восстановлено":"Ошибка").setMessage(r.message).setPositiveButton("Закрыть",null).show();detectAndLoad();});}).start()).show();}

    private void bindXrayProfiles(){
        if(xrayProfileSpinner==null||xrayProfiles==null)return;
        xraySpinnerBinding=true;
        List<XrayProfileStore.Info> profiles=xrayProfiles.profiles();List<String> labels=new ArrayList<String>();int selected=0;
        if(profiles.isEmpty())labels.add("Профили Xray не добавлены");
        else for(int i=0;i<profiles.size();i++){XrayProfileStore.Info info=profiles.get(i);labels.add((info.active?"● ":"")+info.name+" · "+info.type);if(info.active)selected=i;}
        xrayProfileSpinner.setOnItemSelectedListener(null);
        xrayProfileSpinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));xrayProfileSpinner.setSelection(selected,false);
        xrayProfileSpinner.post(()->{
            xrayProfileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){if(xraySpinnerBinding)return;onXrayProfileSelected(position);}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}});
            xraySpinnerBinding=false;
        });
        refreshXraySummary();
    }

    private void onXrayProfileSelected(int position){
        List<XrayProfileStore.Info> profiles=xrayProfiles.profiles();if(position<0||position>=profiles.size())return;
        XrayProfileStore.Info chosen=profiles.get(position);XrayProfileStore.Info current=xrayProfiles.info();if(current.id.equals(chosen.id))return;android.content.SharedPreferences state=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);
        String lifecycle=vpnLifecycle();String mode=state.getString("mode","");String runningId=state.getString("profileId","");
        if(HostsVpnService.STATE_STARTING.equals(lifecycle)||HostsVpnService.STATE_STOPPING.equals(lifecycle)){Toast.makeText(this,"Дождитесь завершения текущей операции VPN",Toast.LENGTH_SHORT).show();bindXrayProfiles();return;}
        xrayProfiles.select(chosen.id);bindXrayProfiles();
        if(HostsVpnService.STATE_ACTIVE.equals(lifecycle)&&HostsVpnService.MODE_XRAY.equals(mode)&&runningId.length()>0&&!runningId.equals(chosen.id))offerXrayProfileSwitch(chosen,runningId);
    }

    private void offerXrayProfileSwitch(XrayProfileStore.Info chosen,String runningId){
        final boolean[] decided={false};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Переключить Xray-профиль?").setMessage("Сейчас VPN использует другой профиль. Туннель будет безопасно перезапущен с профилем:\n\n"+chosen.type+" · "+chosen.name+"\n"+chosen.endpoint)
            .setNegativeButton("Оставить текущий",(d,w)->{decided[0]=true;restoreRunningXrayProfile(runningId);})
            .setPositiveButton("Переключить",(d,w)->{decided[0]=true;startVpn(HostsVpnService.MODE_XRAY);}).create();
        dialog.setOnCancelListener(d->{if(!decided[0])restoreRunningXrayProfile(runningId);});dialog.show();
    }

    private void restoreRunningXrayProfile(String runningId){try{xrayProfiles.select(runningId);}catch(Exception ignored){}bindXrayProfiles();}

    private void refreshXraySummary(){
        if(xraySummary==null)return;List<XrayProfileStore.Info> all=xrayProfiles.profiles();XrayProfileStore.Info info=xrayProfiles.info();boolean arm64=false;for(String abi:Build.SUPPORTED_ABIS)if("arm64-v8a".equalsIgnoreCase(abi))arm64=true;
        if(!info.configured){xraySummary.setText("Добавьте первый профиль · VLESS, VMess, Trojan, Shadowsocks или JSON");return;}
        android.content.SharedPreferences state=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);String lifecycle=vpnLifecycle();String runningId=state.getString("profileId","");String runningName=state.getString("profile","");
        String text="Выбран: "+info.type+" · "+info.name+(info.endpoint.length()>0?"\nСервер: "+info.endpoint:"")+"\nПрофилей: "+all.size()+(arm64?" · ARM64 готов":" · XRAY ABI не поддерживается");
        if(HostsVpnService.STATE_ACTIVE.equals(lifecycle)&&HostsVpnService.MODE_XRAY.equals(state.getString("mode",""))&&runningName.length()>0)text+="\nVPN сейчас: "+runningName+(runningId.equals(info.id)?"":" · выбран другой");
        xraySummary.setText(text);
    }

    private void xrayProfileMenu(){
        XrayProfileStore.Info info=xrayProfiles.info();List<String> actions=new ArrayList<String>();actions.add("Добавить новый профиль");
        if(info.configured){actions.add("Заменить выбранный");actions.add("Переименовать");actions.add("Удалить выбранный");}
        new AlertDialog.Builder(this).setTitle(info.configured?info.name:"Профили Xray").setMessage(info.configured?info.type+" · "+info.endpoint+"\n\nСекреты каждого профиля зашифрованы Android Keystore.":"Можно хранить несколько профилей и быстро выбирать их из списка.")
            .setItems(actions.toArray(new String[0]),(d,w)->{if(w==0)editXrayProfile(false);else if(w==1)editXrayProfile(true);else if(w==2)renameXrayProfile();else deleteXrayProfile();}).setNegativeButton("Закрыть",null).show();
    }

    private void editXrayProfile(boolean replace){
        EditText raw=multiline("");raw.setHint("vless://…  vmess://…  trojan://…  ss://…  или { Xray JSON }");XrayProfileStore.Info selected=xrayProfiles.info();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(replace?"Заменить выбранный профиль":"Добавить профиль Xray").setMessage("Поле очищается после сохранения. UUID, пароль и ключи не выводятся в журнал.").setView(raw).setNegativeButton("Отмена",null).setPositiveButton("Проверить и сохранить",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            String value=raw.getText().toString().trim();if(value.length()==0)throw new IllegalArgumentException("Вставьте профиль");
            android.content.SharedPreferences vpnState=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);String runningId=vpnState.getString("profileId","");boolean xrayRunning=HostsVpnService.STATE_ACTIVE.equals(vpnLifecycle())&&HostsVpnService.MODE_XRAY.equals(vpnState.getString("mode",""));
            XrayProfileStore.Info info=replace?xrayProfiles.replace(selected.id,value):xrayProfiles.add(value);raw.setText("");dialog.dismiss();bindXrayProfiles();
            if(replace&&xrayRunning&&runningId.equals(info.id)){Toast.makeText(this,"Профиль обновлён · VPN перезапускается",Toast.LENGTH_SHORT).show();startVpn(HostsVpnService.MODE_XRAY);return;}
            if(!replace&&xrayRunning&&runningId.length()>0&&!runningId.equals(info.id)){offerXrayProfileSwitch(info,runningId);return;}
            new AlertDialog.Builder(this).setTitle(replace?"Профиль заменён":"Профиль добавлен").setMessage(info.type+" · "+info.name+(info.endpoint.length()>0?"\nСервер: "+info.endpoint:"")+"\n\nСекрет зашифрован Android Keystore.").setPositiveButton("Закрыть",null).show();
        }catch(Exception e){toastError(e);}}));dialog.show();
    }

    private void renameXrayProfile(){
        XrayProfileStore.Info info=xrayProfiles.info();if(!info.configured)return;EditText name=UiKit.input(this,"Название профиля");name.setText(info.name);
        new AlertDialog.Builder(this).setTitle("Переименовать Xray-профиль").setView(name).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,w)->{try{
            String clean=name.getText().toString().trim();xrayProfiles.rename(info.id,clean);android.content.SharedPreferences state=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);if(info.id.equals(state.getString("profileId","")))state.edit().putString("profile",clean).apply();bindXrayProfiles();
        }catch(Exception e){toastError(e);}}).show();
    }

    private void deleteXrayProfile(){
        XrayProfileStore.Info info=xrayProfiles.info();if(!info.configured)return;android.content.SharedPreferences state=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);boolean running=HostsVpnService.STATE_ACTIVE.equals(vpnLifecycle())&&HostsVpnService.MODE_XRAY.equals(state.getString("mode",""))&&info.id.equals(state.getString("profileId",""));
        if(running){new AlertDialog.Builder(this).setTitle("Профиль используется VPN").setMessage("Сначала отключите VPN или переключитесь на другой профиль.").setPositiveButton("Закрыть",null).show();return;}
        new AlertDialog.Builder(this).setTitle("Удалить профиль Xray?").setMessage(info.type+" · "+info.name+"\n"+info.endpoint).setNegativeButton("Отмена",null).setPositiveButton("Удалить",(x,y)->{xrayProfiles.delete(info.id);bindXrayProfiles();}).show();
    }

    private Map<String,List<InetAddress>> xrayOverrides(){Map<String,List<InetAddress>> map=new LinkedHashMap<String,List<InetAddress>>();try{for(HostsEntry entry:store.entries(profile.id)){if(!entry.enabled)continue;InetAddress address=InetAddress.getByName(entry.address);for(String host:entry.hostnames){List<InetAddress> values=map.get(host);if(values==null){values=new ArrayList<InetAddress>();map.put(host,values);}values.add(address);}}}catch(Exception e){throw new IllegalArgumentException("Ошибка hosts: "+e.getMessage());}return map;}

    private void testXrayProfile(){
        String lifecycle=vpnLifecycle();if(HostsVpnService.STATE_STARTING.equals(lifecycle)||HostsVpnService.STATE_ACTIVE.equals(lifecycle)||HostsVpnService.STATE_STOPPING.equals(lifecycle)){Toast.makeText(this,"Сначала отключите VPN",Toast.LENGTH_SHORT).show();return;}
        if(xrayTestRunning||XrayProfilePreflight.isRunning()){Toast.makeText(this,"Проверка профиля уже выполняется",Toast.LENGTH_SHORT).show();return;}
        if(!xrayProfiles.configured()){editXrayProfile(false);return;}
        xrayTestRunning=true;stateTitle.setText("Предпроверка Xray-профиля…");refreshActionButtons(renderedLifecycle);XrayProfileStore.Info selected=xrayProfiles.info();
        new Thread(()->{try{
            XrayConfigBuilder.ConfigResult config=XrayConfigBuilder.build(xrayProfiles.raw(selected.id),xrayOverrides());
            VpnDiagnostics.log(this,"XRAY_PREFLIGHT_START",config.profileType+", endpoint="+config.endpoint+", "+config.transportSummary);
            XrayProfilePreflight.Result result=XrayProfilePreflight.check(config.endpoint);
            VpnDiagnostics.log(this,"XRAY_PREFLIGHT_OK","endpoint="+result.host+":"+result.port+", tcpDelay="+result.delayMs+"ms");
            runOnUiThread(()->{xrayTestRunning=false;refreshState();new AlertDialog.Builder(this).setTitle("Сервер доступен").setMessage(config.profileType+" · "+config.profileName+"\nСервер: "+config.endpoint+"\nПараметры: "+config.transportSummary+"\nTCP-задержка: "+result.delayMs+" мс\n\nЭто безопасная предпроверка адреса и порта без запуска libv2ray. Полная проверка VLESS/Reality/gRPC выполняется при запуске VPN.").setPositiveButton("Закрыть",null).show();});
        }catch(Exception e){VpnDiagnostics.log(this,"XRAY_PREFLIGHT_FAILED",e.getMessage()==null?e.toString():e.getMessage());runOnUiThread(()->{xrayTestRunning=false;refreshState();toastError(e);});}},"SiteScopeXrayPreflightLauncher").start();
    }

    private void showVpnDiagnostics(){android.content.SharedPreferences prefs=getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE);long heartbeat=prefs.getLong("heartbeatAt",0L);String heartbeatText=heartbeat<=0L?"нет":((System.currentTimeMillis()-heartbeat)/1000L)+" сек. назад";long serviceHeartbeat=prefs.getLong("serviceHeartbeatAt",0L);String serviceHeartbeatText=serviceHeartbeat<=0L?"нет":((System.currentTimeMillis()-serviceHeartbeat)/1000L)+" сек. назад";VpnNetworkCoordinator.Snapshot network=VpnNetworkCoordinator.snapshot(this);String header="Состояние: "+prefs.getString(HostsVpnService.KEY_STATE,HostsVpnService.STATE_OFF)+"\nАктивен: "+prefs.getBoolean("active",false)+"\nРежим: "+prefs.getString("mode","")+"\nПрофиль: "+prefs.getString("profile","")+"\nСервер: "+prefs.getString("endpoint","")+"\nCore: "+prefs.getString("coreVersion","")+"\nHeartbeat: "+heartbeatText+"\nVPN-сетей Android: "+network.vpnCount+"\nБазовая сеть: "+network.description+"\nСтатус: "+prefs.getString("message","")+"\n\n";TextView text=UiKit.text(this,header+VpnDiagnostics.read(this),12,UiKit.NAVY,false);text.setTextIsSelectable(true);android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setPadding(UiKit.dp(this,16),UiKit.dp(this,8),UiKit.dp(this,16),UiKit.dp(this,8));scroll.addView(text);new AlertDialog.Builder(this).setTitle("Диагностика Hosts + Xray").setView(scroll).setNeutralButton("Настройки VPN",(d,w)->openVpnSettings()).setPositiveButton("Закрыть",null).show();}

    private void createProfile(){EditText name=UiKit.input(this,"Например: Дом, Работа, Разработка");new AlertDialog.Builder(this).setTitle("Новый профиль hosts").setView(name).setNegativeButton("Отмена",null).setPositiveButton("Создать",(d,w)->{HostsStore.Profile p=store.createProfile(name.getText().toString());store.activate(p.id);profile=p;bindProfiles();adapter.reload();}).show();}
    private void profileMenu(){String[] actions={"Переименовать","Сделать активным","Дублировать","Удалить"};new AlertDialog.Builder(this).setTitle(profile.name).setItems(actions,(d,w)->{if(w==0)renameProfile();else if(w==1){store.activate(profile.id);bindProfiles();}else if(w==2)duplicateProfile();else deleteProfile();}).show();}
    private void renameProfile(){EditText name=UiKit.input(this,"Название профиля");name.setText(profile.name);new AlertDialog.Builder(this).setTitle("Переименовать профиль").setView(name).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,w)->{store.renameProfile(profile.id,name.getText().toString());profile=store.activeProfile();bindProfiles();}).show();}
    private void duplicateProfile(){HostsStore.Profile copy=store.createProfile(profile.name+" — копия");List<HostsEntry> source=store.entries(profile.id);for(HostsEntry e:source){e.id=0;e.profileId=copy.id;}store.replaceEntries(copy.id,source);store.activate(copy.id);profile=copy;bindProfiles();adapter.reload();}
    private void deleteProfile(){if(HostsStore.DEFAULT_PROFILE_ID.equals(profile.id)){Toast.makeText(this,"Основной профиль удалить нельзя",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("Удалить профиль?").setMessage(profile.name+" и все его записи будут удалены.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{store.deleteProfile(profile.id);profile=store.activeProfile();bindProfiles();adapter.reload();}).show();}

    private void openImport(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,REQUEST_IMPORT);}
    private void openExport(String text){pendingExport=text;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"sitescope-hosts-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".hosts").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,REQUEST_EXPORT);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQUEST_VPN){if(resultCode==RESULT_OK&&pendingVpnStart)startVpn(pendingVpnMode);pendingVpnStart=false;return;}if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;try{if(requestCode==REQUEST_IMPORT)confirmImportText(readUri(data.getData()),"файла");else if(requestCode==REQUEST_EXPORT){OutputStream out=getContentResolver().openOutputStream(data.getData(),"w");if(out==null)throw new IllegalStateException("Не удалось открыть файл");try{out.write(pendingExport.getBytes(StandardCharsets.UTF_8));out.flush();}finally{out.close();}Toast.makeText(this,"Hosts экспортирован",Toast.LENGTH_SHORT).show();}}catch(Exception e){toastError(e);}}

    private String readUri(Uri uri)throws Exception{InputStream in;if("file".equals(uri.getScheme()))in=new java.io.FileInputStream(new java.io.File(uri.getPath()));else in=getContentResolver().openInputStream(uri);if(in==null)throw new IllegalStateException("Не удалось открыть файл");try{BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();char[] b=new char[8192];int n;while((n=r.read(b))>=0){if(out.length()+n>4*1024*1024)throw new IllegalArgumentException("Файл hosts больше 4 МБ");out.append(b,0,n);}return out.toString();}finally{in.close();}}
    private void toastError(Exception e){Toast.makeText(this,e.getMessage()==null?e.toString():e.getMessage(),Toast.LENGTH_LONG).show();}
    private String safeHash(String value){try{return HostsRootManager.sha256(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){return "—";}}
    private String join(List<String> values){StringBuilder out=new StringBuilder();for(String v:values){if(out.length()>0)out.append(' ');out.append(v);}return out.toString();}
    private HostsEntry copy(HostsEntry source){HostsEntry e=new HostsEntry();e.id=source.id;e.profileId=source.profileId;e.position=source.position;e.enabled=source.enabled;e.address=source.address;e.hostnames.addAll(source.hostnames);e.comment=source.comment;e.source=source.source;return e;}

    private final class EntryAdapter extends BaseAdapter {
        private final List<HostsEntry> rows=new ArrayList<HostsEntry>();
        void reload(){rows.clear();if(profile!=null)rows.addAll(store.entries(profile.id));notifyDataSetChanged();refreshCount();}
        @Override public int getCount(){return rows.size();}@Override public HostsEntry getItem(int p){return rows.get(p);}@Override public long getItemId(int p){return rows.get(p).id;}
        @Override public View getView(int position,View convert,ViewGroup parent){HostsEntry entry=getItem(position);LinearLayout card=UiKit.card(HostsActivity.this,13);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);CheckBox enabled=new CheckBox(HostsActivity.this);enabled.setChecked(entry.enabled);enabled.setOnCheckedChangeListener((button,checked)->{if(button.isPressed()){entry.enabled=checked;store.saveEntry(entry);refreshCount();if(getSharedPreferences(HostsVpnService.PREFS,MODE_PRIVATE).getBoolean("active",false))reloadVpn();}});card.addView(enabled,new LinearLayout.LayoutParams(UiKit.dp(HostsActivity.this,44),UiKit.dp(HostsActivity.this,44)));LinearLayout text=new LinearLayout(HostsActivity.this);text.setOrientation(LinearLayout.VERTICAL);TextView line=UiKit.text(HostsActivity.this,entry.address+"  "+join(entry.hostnames),14,entry.enabled?UiKit.NAVY:UiKit.MUTED,true);line.setTextIsSelectable(true);text.addView(line);if(entry.comment!=null&&entry.comment.length()>0)text.addView(UiKit.text(HostsActivity.this,entry.comment,12,UiKit.MUTED,false));card.addView(text,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));TextView more=UiKit.text(HostsActivity.this,"⋮",24,UiKit.PRIMARY,true);more.setGravity(Gravity.CENTER);more.setOnClickListener(v->entryMenu(entry));card.addView(more,new LinearLayout.LayoutParams(UiKit.dp(HostsActivity.this,44),UiKit.dp(HostsActivity.this,44)));card.setOnClickListener(v->editEntry(entry));card.setLayoutParams(UiKit.margins(HostsActivity.this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,4,0,4));return card;}
    }

    private void entryMenu(HostsEntry entry){String[] actions={"Редактировать","Дублировать","Копировать строку","Удалить"};new AlertDialog.Builder(this).setTitle(entry.primaryHost()).setItems(actions,(d,w)->{if(w==0)editEntry(entry);else if(w==1){HostsEntry copy=copy(entry);copy.id=0;copy.comment=(copy.comment+" копия").trim();store.saveEntry(copy);adapter.reload();}else if(w==2){android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText("hosts",entry.canonicalLine()));Toast.makeText(this,"Строка скопирована",Toast.LENGTH_SHORT).show();}else new AlertDialog.Builder(this).setTitle("Удалить запись?").setMessage(entry.canonicalLine()).setNegativeButton("Отмена",null).setPositiveButton("Удалить",(x,y)->{store.deleteEntry(entry.id,profile.id);adapter.reload();}).show();}).show();}
    private void reloadVpn(){Intent i=new Intent(this,HostsVpnService.class).setAction(HostsVpnService.ACTION_RELOAD);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private static final class FileLabel{final String name;FileLabel(String path){int p=path==null?-1:path.lastIndexOf('/');name=p>=0?path.substring(p+1):path;}}
}
