package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Focused, virtualized scanner workspace. */
public final class ScannerActivity extends Activity {
    private ScanService.Observer observer;
    private EditText targets;
    private EditText ports;
    private EditText timeout;
    private EditText concurrency;
    private EditText delay;
    private CheckBox detect;
    private CheckBox extended;
    private CheckBox authorization;
    private TextView start;
    private TextView pause;
    private TextView stop;
    private TextView status;
    private ProgressBar progress;
    private PortAdapter adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = UiKit.page(this);
        root.addView(UiKit.header(this,"⌁","Сканер сети","Порты, сервисы и результаты"));

        LinearLayout form = UiKit.card(this,16);
        targets = UiKit.input(this,"192.168.1.0/24 или список адресов");
        String initial = getIntent().getStringExtra("target"); if (initial != null) targets.setText(initial);
        ports = UiKit.input(this,"22,80,443,8000-8100"); ports.setText("22,80,443,554,1900,3389,5900");
        timeout = UiKit.input(this,"Timeout, мс"); timeout.setText(String.valueOf(SettingsStore.scannerTimeoutMs(this)));
        concurrency = UiKit.input(this,"Параллельность"); concurrency.setText(String.valueOf(SettingsStore.scannerConcurrency(this)));
        form.addView(UiKit.text(this,"Цели",12,UiKit.NAVY,true));
        form.addView(targets,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,50),0,6,0,10));
        form.addView(UiKit.text(this,"Порты",12,UiKit.NAVY,true));
        form.addView(ports,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,50),0,6,0,10));
        LinearLayout numbers = new LinearLayout(this); numbers.setOrientation(LinearLayout.HORIZONTAL);
        numbers.addView(timeout,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));
        View gap = new View(this); numbers.addView(gap,new LinearLayout.LayoutParams(UiKit.dp(this,8),1));
        numbers.addView(concurrency,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));
        form.addView(numbers);
        delay = UiKit.input(this,"Задержка между стартами, мс (0–5000)"); delay.setText(String.valueOf(SettingsStore.scannerDelayMs(this)));
        delay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(delay);
        detect = new CheckBox(this); detect.setText("Определять сервисы и безопасные сигнатуры"); detect.setTextColor(UiKit.NAVY); detect.setChecked(SettingsStore.scannerDetectServices(this));
        extended = new CheckBox(this); extended.setText("Расширенный локальный режим"); extended.setTextColor(UiKit.NAVY);
        authorization = new CheckBox(this); authorization.setText("У меня есть разрешение на проверку этих узлов"); authorization.setTextColor(UiKit.NAVY);
        form.addView(detect); form.addView(extended); form.addView(authorization);
        root.addView(form,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,6,0,8));

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        start = UiKit.button(this,"Запустить",true); pause = UiKit.button(this,"Пауза",false); stop = UiKit.button(this,"Остановить",false);
        actions.addView(start,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1.2f));
        View g1=new View(this); actions.addView(g1,new LinearLayout.LayoutParams(UiKit.dp(this,7),1));
        actions.addView(pause,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));
        View g2=new View(this); actions.addView(g2,new LinearLayout.LayoutParams(UiKit.dp(this,7),1));
        actions.addView(stop,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));
        root.addView(actions);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        root.addView(progress,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,10),0,12,0,4));
        status = UiKit.text(this,"Готово к запуску",12,UiKit.MUTED,false); root.addView(status);
        root.addView(UiKit.text(this,"Результаты в реальном времени",18,UiKit.NAVY,true),UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,14,0,8));
        ListView list = new ListView(this); list.setDividerHeight(UiKit.dp(this,8)); list.setClipToPadding(false); list.setPadding(0,0,0,UiKit.dp(this,20));
        adapter = new PortAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener(){ @Override public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id){ ProtocolRouter.open(ScannerActivity.this, adapter.getItem(position)); } });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener(){ @Override public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id){
            Models.PortResult r=adapter.getItem(position); ProtocolRouter.copy(ScannerActivity.this,(r.ip.length()>0?r.ip:r.target)+":"+r.port); Toast.makeText(ScannerActivity.this,"Endpoint скопирован",Toast.LENGTH_SHORT).show(); return true;
        } });
        root.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        setContentView(root);

        start.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ prepareStart(); }});
        pause.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
            ScanService.Snapshot s=ScanService.snapshot(); Intent i=new Intent(ScannerActivity.this,ScanService.class); i.setAction(s.paused?ScanService.ACTION_RESUME:ScanService.ACTION_PAUSE); startService(i);
        }});
        stop.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ Intent i=new Intent(ScannerActivity.this,ScanService.class); i.setAction(ScanService.ACTION_STOP); startService(i); }});
        bind();
    }

    private void prepareStart() {
        if (!authorization.isChecked()) { Toast.makeText(this,"Подтвердите право на диагностику",Toast.LENGTH_LONG).show(); return; }
        try {
            boolean ext=extended.isChecked();
            List<String> hosts=ScannerEngine.parseTargets(targets.getText().toString(),ext?ScannerEngine.EXTENDED_MAX_HOSTS:ScannerEngine.STANDARD_MAX_HOSTS);
            List<Integer> parsedPorts=ScannerEngine.parsePorts(ports.getText().toString(),ext?ScannerEngine.EXTENDED_MAX_PORTS:ScannerEngine.STANDARD_MAX_PORTS);
            long probes=(long)hosts.size()*(long)parsedPorts.size();
            long max=ext?ScannerEngine.EXTENDED_MAX_PROBES:ScannerEngine.STANDARD_MAX_PROBES;
            if (probes>max) throw new IllegalArgumentException("Задание содержит "+probes+" проверок; предел режима — "+max);
            if (ext && !ScannerEngine.allTargetsPrivateOrLocal(hosts)) throw new IllegalArgumentException("Расширенный режим разрешён только для числовых приватных/локальных IP");
            final Runnable launch=new Runnable(){ @Override public void run(){ launchService(); }};
            if (SettingsStore.confirmLargeTasks(this) && probes>250000) {
                new AlertDialog.Builder(this).setTitle("Крупное задание").setMessage("Узлов: "+hosts.size()+"\nПортов: "+parsedPorts.size()+"\nПроверок: "+probes+"\n\nПродолжить?")
                    .setNegativeButton("Отмена",null).setPositiveButton("Запустить",new android.content.DialogInterface.OnClickListener(){ @Override public void onClick(android.content.DialogInterface d,int w){ launch.run(); } }).show();
            } else launch.run();
        } catch (Exception e) { new AlertDialog.Builder(this).setTitle("Невозможно запустить").setMessage(e.getMessage()).setPositiveButton("Закрыть",null).show(); }
    }

    private void launchService() {
        int t=parseInt(timeout.getText().toString(),SettingsStore.scannerTimeoutMs(this));
        int c=parseInt(concurrency.getText().toString(),SettingsStore.scannerConcurrency(this));
        SettingsStore.saveScanner(this,t,c,detect.isChecked(),SettingsStore.confirmLargeTasks(this));
        SettingsStore.saveScanTuning(this,c,parseInt(delay.getText().toString(),SettingsStore.scannerDelayMs(this)));
        Intent i=new Intent(this,ScanService.class); i.setAction(ScanService.ACTION_START);
        i.putExtra(ScanService.EXTRA_TARGETS,targets.getText().toString().trim()); i.putExtra(ScanService.EXTRA_PORTS,ports.getText().toString().trim());
        i.putExtra(ScanService.EXTRA_DELAY_MS,SettingsStore.scannerDelayMs(this)); i.putExtra(ScanService.EXTRA_TIMEOUT,t); i.putExtra(ScanService.EXTRA_CONCURRENCY,c); i.putExtra(ScanService.EXTRA_DETECT,detect.isChecked()); i.putExtra(ScanService.EXTRA_EXTENDED_PRIVATE,extended.isChecked());
        if (android.os.Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    private void bind() {
        observer=new ScanService.Observer(){ @Override public void onScanState(final ScanService.Snapshot s){ runOnUiThread(new Runnable(){ @Override public void run(){
            int total=Math.max(1,s.totalProbes); progress.setProgress((int)Math.min(1000,(s.completedProbes*1000L)/total));
            status.setText((s.active?(s.paused?"Пауза":"Сканирование"):s.completed?"Завершено":"Готово")+" · "+s.completedProbes+" / "+s.totalProbes+" · найдено "+s.results.size()+(s.error.length()>0?" · "+s.error:""));
            pause.setText(s.paused?"Продолжить":"Пауза"); start.setEnabled(!s.active); adapter.replace(s.results);
        } }); }};
        ScanService.register(observer);
    }

    @Override protected void onDestroy(){ if(observer!=null) ScanService.unregister(observer); super.onDestroy(); }
    private int parseInt(String s,int fallback){ try{return Integer.parseInt(s.trim());}catch(Exception e){return fallback;} }

    private final class PortAdapter extends BaseAdapter {
        private final List<Models.PortResult> rows=new ArrayList<Models.PortResult>();
        void replace(List<Models.PortResult> source){ rows.clear(); rows.addAll(source); notifyDataSetChanged(); }
        @Override public int getCount(){return rows.size();}
        @Override public Models.PortResult getItem(int p){return rows.get(p);}
        @Override public long getItemId(int p){return p;}
        @Override public View getView(int p,View convert,ViewGroup parent){
            Models.PortResult r=getItem(p);
            LinearLayout card=UiKit.card(ScannerActivity.this,12); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
            TextView ok=UiKit.text(ScannerActivity.this,"✓",18,UiKit.GREEN,true); ok.setGravity(Gravity.CENTER); ok.setBackground(UiKit.round(UiKit.GREEN_SOFT,999,UiKit.GREEN,1,ScannerActivity.this));
            card.addView(ok,new LinearLayout.LayoutParams(UiKit.dp(ScannerActivity.this,38),UiKit.dp(ScannerActivity.this,38)));
            LinearLayout col=new LinearLayout(ScannerActivity.this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(UiKit.dp(ScannerActivity.this,10),0,0,0);
            String address=(r.ip.length()>0?r.ip:r.target)+":"+r.port;
            col.addView(UiKit.text(ScannerActivity.this,address,15,UiKit.GREEN,true));
            col.addView(UiKit.text(ScannerActivity.this,r.service+" · "+r.latencyMs+" мс",12,UiKit.MUTED,false));
            card.addView(col,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
            TextView badge=UiKit.text(ScannerActivity.this,r.confidence.toUpperCase(),10,UiKit.PRIMARY,true); badge.setGravity(Gravity.CENTER); badge.setBackground(UiKit.round(UiKit.PRIMARY_SOFT,12,Color.TRANSPARENT,0,ScannerActivity.this)); badge.setPadding(UiKit.dp(ScannerActivity.this,8),UiKit.dp(ScannerActivity.this,5),UiKit.dp(ScannerActivity.this,8),UiKit.dp(ScannerActivity.this,5)); card.addView(badge);
            return card;
        }
    }
}
