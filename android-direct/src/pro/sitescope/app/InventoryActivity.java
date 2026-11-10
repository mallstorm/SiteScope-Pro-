package pro.sitescope.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Merged LAN device inventory with snapshot comparison. */
public final class InventoryActivity extends Activity {
    private LinearLayout body;
    private List<DeviceInventoryStore.Snapshot> snapshots = new ArrayList<DeviceInventoryStore.Snapshot>();
    private String filter = "CURRENT";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildUi();
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void buildUi() {
        LinearLayout root = ClientUi.root(this);
        LinearLayout header = ClientUi.row(this);
        header.setPadding(ClientUi.dp(this,14),ClientUi.dp(this,12),ClientUi.dp(this,14),ClientUi.dp(this,8));
        TextView back = ClientUi.text(this,"‹",30,ClientUi.PRIMARY,false); back.setGravity(Gravity.CENTER); back.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){finish();}});
        header.addView(back,new LinearLayout.LayoutParams(ClientUi.dp(this,42),ClientUi.dp(this,42)));
        LinearLayout titles=new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL); titles.addView(ClientUi.text(this,"Инвентаризация LAN",22,ClientUi.NAVY,true)); titles.addView(ClientUi.text(this,"Объединение сервисов и сравнение снимков",12,ClientUi.MUTED,false));
        header.addView(titles,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        root.addView(header);
        LinearLayout page=ClientUi.page(this);
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); page.addView(body);
        ScrollView scroll=new ScrollView(this); scroll.addView(page,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)); root.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        setContentView(root);
    }

    private void render() {
        if(body==null)return;
        body.removeAllViews(); snapshots=DeviceInventoryStore.list(this);
        if(snapshots.isEmpty()) {
            LinearLayout empty=ClientUi.card(this,18); TextView t=ClientUi.text(this,"Снимков сети пока нет",18,ClientUi.NAVY,true); t.setGravity(Gravity.CENTER); empty.addView(t); TextView n=ClientUi.text(this,"Запустите LAN Discovery в IP Engineering. Завершённый поиск автоматически создаст снимок.",13,ClientUi.MUTED,false); n.setGravity(Gravity.CENTER); empty.addView(n,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,10,0,0)); body.addView(empty); return;
        }
        final DeviceInventoryStore.Snapshot latest=snapshots.get(0); DeviceInventoryStore.Snapshot previous=snapshots.size()>1?snapshots.get(1):null;
        Set<String> currentIps=DeviceInventoryStore.ipSet(latest); Set<String> previousIps=DeviceInventoryStore.ipSet(previous);
        Set<String> added=new HashSet<String>(currentIps); added.removeAll(previousIps);
        Set<String> removed=new HashSet<String>(previousIps); removed.removeAll(currentIps);
        SimpleDateFormat format=new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault());
        LinearLayout summary=ClientUi.card(this,15); summary.addView(ClientUi.text(this,"Последний снимок · "+format.format(new Date(latest.timestamp)),16,ClientUi.NAVY,true)); summary.addView(ClientUi.text(this,currentIps.size()+" устройств · новых "+added.size()+" · исчезло "+removed.size()+" · снимков "+snapshots.size(),13,ClientUi.MUTED,false),ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,6,0,0));
        LinearLayout actions=ClientUi.row(this); TextView copy=ClientUi.button(this,"Копировать сводку",false); TextView clear=ClientUi.button(this,"Очистить снимки",false); actions.addView(copy,new LinearLayout.LayoutParams(0,ClientUi.dp(this,44),1f)); actions.addView(space(8)); actions.addView(clear,new LinearLayout.LayoutParams(0,ClientUi.dp(this,44),1f)); summary.addView(actions,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,10,0,0));
        copy.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){ProtocolRouter.copy(InventoryActivity.this,summaryText(latest));Toast.makeText(InventoryActivity.this,"Сводка скопирована",Toast.LENGTH_SHORT).show();}});
        clear.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){new AlertDialog.Builder(InventoryActivity.this).setTitle("Удалить снимки?").setMessage("Пользовательские имена устройств сохранятся.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){DeviceInventoryStore.clear(InventoryActivity.this);render();}}).show();}});
        body.addView(summary);
        LinearLayout filters=ClientUi.row(this); String[] names={"Текущие","Новые","Исчезли"}; final String[] modes={"CURRENT","ADDED","REMOVED"}; for(int i=0;i<names.length;i++){final String mode=modes[i];TextView b=ClientUi.button(this,names[i],mode.equals(filter));filters.addView(b,new LinearLayout.LayoutParams(0,ClientUi.dp(this,42),1f));if(i<2)filters.addView(space(6));b.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){filter=mode;render();}});} body.addView(filters,ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,0,12,0,8));
        if("REMOVED".equals(filter)) renderRemoved(previous,removed); else renderAggregates(latest,"ADDED".equals(filter)?added:null);
    }

    private void renderAggregates(DeviceInventoryStore.Snapshot snapshot, Set<String> allowed) {
        int shown=0; for(final DeviceInventoryStore.Aggregate item:DeviceInventoryStore.aggregate(this,snapshot)){if(allowed!=null&&!allowed.contains(item.ip))continue;shown++;LinearLayout card=ClientUi.card(this,14);LinearLayout top=ClientUi.row(this);TextView icon=ClientUi.text(this,"◎",20,ClientUi.GREEN,true);icon.setGravity(Gravity.CENTER);icon.setBackground(ClientUi.round(this,Color.rgb(228,247,238),99,ClientUi.GREEN,1));top.addView(icon,new LinearLayout.LayoutParams(ClientUi.dp(this,42),ClientUi.dp(this,42)));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(ClientUi.dp(this,10),0,0,0);info.addView(ClientUi.text(this,item.title(),16,ClientUi.NAVY,true));info.addView(ClientUi.text(this,item.ip+" · "+join(item.protocols),12,ClientUi.MUTED,false));top.addView(info,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));card.addView(top);if(item.manufacturer.length()>0||item.model.length()>0)card.addView(ClientUi.text(this,(item.manufacturer+" "+item.model).trim(),12,ClientUi.MUTED,false),ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,52,8,0,0));card.addView(ClientUi.text(this,item.services.size()+" обнаруженных сервисов · нажмите для выбора",12,ClientUi.PRIMARY,true),ClientUi.margins(this,ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,52,8,0,0));card.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){openServices(item);}});card.setOnLongClickListener(new View.OnLongClickListener(){@Override public boolean onLongClick(View v){editAlias(item);return true;}});body.addView(card);} if(shown==0){LinearLayout empty=ClientUi.card(this,16);TextView t=ClientUi.text(this,"Нет устройств в этом фильтре",15,ClientUi.MUTED,true);t.setGravity(Gravity.CENTER);empty.addView(t);body.addView(empty);}
    }

    private void renderRemoved(DeviceInventoryStore.Snapshot previous, Set<String> removed) { if(previous==null||removed.isEmpty()){renderAggregates(new DeviceInventoryStore.Snapshot(),new HashSet<String>());return;}renderAggregates(previous,removed); }

    private void openServices(final DeviceInventoryStore.Aggregate item) { String[] labels=new String[item.services.size()];for(int i=0;i<labels.length;i++){Models.DiscoveredDevice d=item.services.get(i);labels[i]=d.protocol+" · "+d.ip+(d.port>0?":"+d.port:"")+(d.name.length()>0?" · "+d.name:"");}new AlertDialog.Builder(this).setTitle(item.title()).setItems(labels,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int which){ProtocolRouter.open(InventoryActivity.this,item.services.get(which));}}).setNeutralButton("Переименовать",new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){editAlias(item);}}).show(); }

    private void editAlias(final DeviceInventoryStore.Aggregate item) { final EditText input=new EditText(this);input.setText(item.alias);input.setHint("Название устройства");new AlertDialog.Builder(this).setTitle("Пользовательское имя").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){DeviceInventoryStore.setAlias(InventoryActivity.this,item.ip,input.getText().toString());render();}}).show(); }

    private String summaryText(DeviceInventoryStore.Snapshot snapshot){StringBuilder out=new StringBuilder("SiteScope Pro · LAN inventory\n");for(DeviceInventoryStore.Aggregate a:DeviceInventoryStore.aggregate(this,snapshot)){out.append(a.ip).append(" · ").append(a.title()).append(" · ").append(join(a.protocols)).append("\n");}return out.toString();}
    private String join(Set<String> set){StringBuilder out=new StringBuilder();for(String s:set){if(out.length()>0)out.append(", ");out.append(s);}return out.toString();}
    private View space(int dp){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(ClientUi.dp(this,dp),1));return v;}
}
