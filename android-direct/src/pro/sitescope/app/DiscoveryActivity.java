package pro.sitescope.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Focused SSDP/ONVIF/mDNS discovery workspace with a virtualized result list. */
public final class DiscoveryActivity extends Activity {
    private NetworkDiscoveryEngine.Task task;
    private TextView status;
    private TextView start;
    private TextView stop;
    private ProgressBar progress;
    private CheckBox authorization;
    private DeviceAdapter adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout root=UiKit.page(this);
        root.addView(UiKit.header(this,"📡","LAN Discovery","SSDP/UPnP, ONVIF и mDNS/DNS-SD"));
        authorization=new CheckBox(this); authorization.setText("У меня есть разрешение на диагностику этой сети"); authorization.setTextColor(UiKit.NAVY); root.addView(authorization);
        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        start=UiKit.button(this,"Найти устройства",true); stop=UiKit.button(this,"Остановить",false); stop.setEnabled(false);
        actions.addView(start,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f)); View gap=new View(this); actions.addView(gap,new LinearLayout.LayoutParams(UiKit.dp(this,8),1)); actions.addView(stop,new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1f));
        root.addView(actions,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,8,0,0));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(false); progress.setMax(100); root.addView(progress,UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,8),0,12,0,4));
        status=UiKit.text(this,"Готово к поиску",12,UiKit.MUTED,false); root.addView(status);
        root.addView(UiKit.text(this,"Обнаруженные сервисы",18,UiKit.NAVY,true),UiKit.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,14,0,8));
        ListView list=new ListView(this); list.setDividerHeight(UiKit.dp(this,8)); list.setClipToPadding(false); list.setPadding(0,0,0,UiKit.dp(this,20)); adapter=new DeviceAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener(){ @Override public void onItemClick(android.widget.AdapterView<?> parent,View view,int position,long id){ ProtocolRouter.open(DiscoveryActivity.this,adapter.getItem(position)); } });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener(){ @Override public boolean onItemLongClick(android.widget.AdapterView<?> parent,View view,int position,long id){ Models.DiscoveredDevice d=adapter.getItem(position); String endpoint=d.location.length()>0?d.location:d.ip+(d.port>0?":"+d.port:""); ProtocolRouter.copy(DiscoveryActivity.this,endpoint); Toast.makeText(DiscoveryActivity.this,"Endpoint скопирован",Toast.LENGTH_SHORT).show(); return true; } });
        root.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f)); setContentView(root);
        start.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ begin(); } }); stop.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ if(task!=null)task.cancel(); } });
    }

    private void begin(){
        if(!authorization.isChecked()){Toast.makeText(this,"Подтвердите право на диагностику сети",Toast.LENGTH_LONG).show();return;}
        if(task!=null)task.cancel(); adapter.clear(); start.setEnabled(false); stop.setEnabled(true); progress.setIndeterminate(true); status.setText("Подготовка multicast discovery…");
        task=NetworkDiscoveryEngine.start(this,new NetworkDiscoveryEngine.Listener(){
            @Override public void onStatus(final String value){runOnUiThread(new Runnable(){ @Override public void run(){ status.setText(value); } });}
            @Override public void onDevice(final Models.DiscoveredDevice device){runOnUiThread(new Runnable(){ @Override public void run(){ adapter.upsert(device); } });}
            @Override public void onComplete(final List<Models.DiscoveredDevice> devices,final boolean cancelled,final String error){runOnUiThread(new Runnable(){ @Override public void run(){
                task=null; start.setEnabled(true); stop.setEnabled(false); progress.setIndeterminate(false); progress.setProgress(100);
                if(!cancelled&&error.length()==0&&!devices.isEmpty()){
                    DeviceInventoryStore.Snapshot snapshot=DeviceInventoryStore.saveSnapshot(DiscoveryActivity.this,devices,"LAN Discovery");
                    status.setText("Готово: "+devices.size()+" сервисов · снимок "+snapshot.id+" сохранён");
                }else status.setText(cancelled?"Поиск остановлен":error.length()>0?"Ошибка: "+error:"Устройства не ответили");
            } });}
        });
    }

    @Override protected void onDestroy(){if(task!=null)task.cancel();super.onDestroy();}

    private final class DeviceAdapter extends BaseAdapter{
        private final List<Models.DiscoveredDevice> rows=new ArrayList<Models.DiscoveredDevice>();
        void clear(){rows.clear();notifyDataSetChanged();}
        void upsert(Models.DiscoveredDevice d){for(int i=0;i<rows.size();i++){if(rows.get(i).key().equals(d.key())){rows.set(i,d);notifyDataSetChanged();return;}}rows.add(d);notifyDataSetChanged();}
        @Override public int getCount(){return rows.size();}
        @Override public Models.DiscoveredDevice getItem(int p){return rows.get(p);}
        @Override public long getItemId(int p){return p;}
        @Override public View getView(int p,View convert,ViewGroup parent){
            Models.DiscoveredDevice d=getItem(p); LinearLayout card=UiKit.card(DiscoveryActivity.this,12); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon=UiKit.text(DiscoveryActivity.this,"✓",18,UiKit.GREEN,true); icon.setGravity(Gravity.CENTER); icon.setBackground(UiKit.round(UiKit.GREEN_SOFT,999,UiKit.GREEN,1,DiscoveryActivity.this)); card.addView(icon,new LinearLayout.LayoutParams(UiKit.dp(DiscoveryActivity.this,38),UiKit.dp(DiscoveryActivity.this,38)));
            LinearLayout col=new LinearLayout(DiscoveryActivity.this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(UiKit.dp(DiscoveryActivity.this,10),0,0,0);
            col.addView(UiKit.text(DiscoveryActivity.this,d.ip+(d.port>0?":"+d.port:""),15,UiKit.GREEN,true));
            String detail=(d.name.length()>0?d.name:d.deviceType)+" · "+d.protocol; col.addView(UiKit.text(DiscoveryActivity.this,detail,12,UiKit.MUTED,false)); card.addView(col,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
            TextView badge=UiKit.text(DiscoveryActivity.this,d.protocol,10,UiKit.PRIMARY,true); badge.setGravity(Gravity.CENTER); badge.setBackground(UiKit.round(UiKit.PRIMARY_SOFT,12,Color.TRANSPARENT,0,DiscoveryActivity.this)); badge.setPadding(UiKit.dp(DiscoveryActivity.this,8),UiKit.dp(DiscoveryActivity.this,5),UiKit.dp(DiscoveryActivity.this,8),UiKit.dp(DiscoveryActivity.this,5)); card.addView(badge); return card;
        }
    }
}
