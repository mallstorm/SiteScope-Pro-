package pro.sitescope.app.radio

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.widget.RemoteViews
import pro.sitescope.app.R
import pro.sitescope.app.ui.ToolActivity
import java.text.DateFormat
import java.util.Date

class RadioWidget:AppWidgetProvider() {
    companion object {
        fun publish(ctx:Context,f:RadioFrame) {
            val p=ctx.getSharedPreferences("radio-widget",0)
            val e=p.edit().putLong("time",f.time)
            for(key in listOf("SIM 1","SIM 2","Wi-Fi","Общий кэш")){
                val dbm=if(key=="Wi-Fi") f.wifi?.dbm else f.cells.firstOrNull {it.sim==key && it.registered && it.dbm!=null}?.dbm
                e.putString(key,dbm?.toString())
            }
            e.apply()
            refresh(ctx)
        }
        fun refresh(ctx:Context){
            val mgr=AppWidgetManager.getInstance(ctx)
            val ids=mgr.getAppWidgetIds(ComponentName(ctx,RadioWidget::class.java))
            ids.forEach {render(ctx,mgr,it)}
        }
        fun render(ctx:Context,mgr:AppWidgetManager,id:Int){
            val p=ctx.getSharedPreferences("radio-widget",0)
            val style=p.getInt("style-$id",0)
            val key=p.getString("source-$id","SIM 1") ?: "SIM 1"
            val time=if(style==4) p.getLong("latency-time",0) else p.getLong("time",0)
            val measured=p.getString(key,null)?.toIntOrNull()
            val text=if(style==4) p.getString("latency",null)?.plus(" мс") else measured?.let{"$it dBm"}
            val view=RemoteViews(ctx.packageName,R.layout.survey_widget)
            val label=if(style==4) "HTTP · задержка" else key
            view.setTextViewText(R.id.survey_title,label)
            view.setTextViewText(R.id.survey_value,text ?: "—")
            view.setTextViewText(R.id.survey_time,if(time>0) DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(time)) else "Откройте измерение")
            val bar=style==0 || style==1 || style==3
            view.setViewVisibility(R.id.survey_bar,if(bar && measured!=null) android.view.View.VISIBLE else android.view.View.GONE)
            view.setProgressBar(R.id.survey_bar,100,((measured ?: -120)+120).coerceIn(0,70)*100/70,false)
            view.setViewVisibility(R.id.survey_time,if(style==2) android.view.View.GONE else android.view.View.VISIBLE)
            view.setInt(R.id.survey_root,"setBackgroundColor",if(style==1) android.graphics.Color.rgb(20,65,80) else android.graphics.Color.rgb(27,36,52))
            if(style==3) view.setTextViewText(R.id.survey_value,(if(measured!=null) "◉ " else "")+(text ?: "—"))
            val intent=Intent(ctx,ToolActivity::class.java).putExtra("screen","radio").putExtra("tab",if(style==4) 3 else 0)
            view.setOnClickPendingIntent(R.id.survey_root,PendingIntent.getActivity(ctx,id,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            mgr.updateAppWidget(id,view)
        }
    }
    override fun onUpdate(ctx:Context,mgr:AppWidgetManager,ids:IntArray){ ids.forEach {render(ctx,mgr,it)} }
    override fun onAppWidgetOptionsChanged(ctx:Context,mgr:AppWidgetManager,id:Int,options:Bundle){ render(ctx,mgr,id) }
    override fun onDeleted(ctx:Context,ids:IntArray) {
        val edit=ctx.getSharedPreferences("radio-widget",0).edit();ids.forEach {edit.remove("style-$it").remove("source-$it")};edit.apply()
    }
}

class RadioWidgetSetup:android.app.Activity() {
    override fun onCreate(state:Bundle?){
        super.onCreate(state);setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID){finish();return}
        val prefs=getSharedPreferences("radio-widget",0)
        val form=android.widget.LinearLayout(this).apply{orientation=android.widget.LinearLayout.VERTICAL;setPadding(32,24,32,8)}
        fun spinner(title:String,choices:List<String>,selected:Int):android.widget.Spinner{
            form.addView(android.widget.TextView(this).apply{text=title})
            return android.widget.Spinner(this).also {s->s.adapter=android.widget.ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices);s.setSelection(selected.coerceIn(0,choices.lastIndex));form.addView(s)}
        }
        val sources=listOf("SIM 1","SIM 2","Wi-Fi","Общий кэш")
        val src=spinner("Источник",sources,sources.indexOf(prefs.getString("source-$id","SIM 1")))
        val style=spinner("Вид",listOf("Шкала","Контрастная шкала","Компактный","Индикатор со шкалой","Задержка HTTP"),prefs.getInt("style-$id",0))
        android.app.AlertDialog.Builder(this).setTitle("Виджет связи").setView(form).setNegativeButton("Отмена"){_,_->finish()}.setOnCancelListener {finish()}
            .setPositiveButton("Сохранить"){_,_->prefs.edit().putInt("style-$id",style.selectedItemPosition).putString("source-$id",src.selectedItem.toString()).apply()
                RadioWidget.render(this,AppWidgetManager.getInstance(this),id)
                setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()}.show()
    }
}

