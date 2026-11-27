package pro.sitescope.app.radio

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.graphics.*
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.widget.*
import org.json.JSONArray
import pro.sitescope.app.R
import pro.sitescope.app.ui.ToolActivity
import java.text.DateFormat
import java.util.Date

class RadioWidget : AppWidgetProvider() {
    companion object {
        private val sources = listOf("SIM 1", "SIM 2", "Wi-Fi", "Общий кэш")
        fun publish(context: Context, frame: RadioFrame) {
            val prefs = context.getSharedPreferences("radio-widget", 0)
            val edit = prefs.edit().putLong("time", frame.time)
            val keys = sources + listOf("SIM 1", "SIM 2").flatMap { sim ->
                listOf("LTE", "NR", "GSM", "WCDMA", "TD-SCDMA", "CDMA").map { "$sim|$it" }
            }
            for (source in keys) {
                val sim = source.substringBefore('|')
                val rat = source.substringAfter('|', "")
                val cell = frame.cells.firstOrNull { it.sim == sim && it.registered && it.dbm != null && (rat.isEmpty() || it.rat == rat) }
                val value = if (source == "Wi-Fi") frame.wifi?.dbm else cell?.dbm
                val measured = if (source == "Wi-Fi") frame.time else cell?.measured ?: frame.time
                edit.putString(source, value?.toString()).putLong("measured-$source", measured)
                val history = runCatching { JSONArray(prefs.getString("plot-$source", "[]")) }.getOrDefault(JSONArray())
                val next = JSONArray()
                for (index in maxOf(0, history.length() - 59) until history.length()) next.put(history.get(index))
                next.put(value ?: org.json.JSONObject.NULL)
                edit.putString("plot-$source", next.toString())
            }
            edit.apply()
            refresh(context)
        }
        @JvmStatic fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, RadioWidget::class.java)).forEach { render(context, manager, it) }
        }
        private fun color(raw: String?, fallback: Int): Int = runCatching { Color.parseColor(raw) }.getOrDefault(fallback)
        private fun percent(value: Int, wifi: Boolean): Int {
            val floor = if (wifi) -95 else -125
            val ceiling = if (wifi) -45 else -75
            return ((value - floor) * 100 / (ceiling - floor)).coerceIn(0, 100)
        }
        fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("radio-widget", 0)
            val style = prefs.getInt("style-$id", 0)
            val source = prefs.getString("source-$id", "SIM 1") ?: "SIM 1"
            val radio = prefs.getString("rat-$id", "Авто") ?: "Авто"
            val baseSources = if (source == "Все сигналы") sources.take(3) else listOf(source)
            val selected = baseSources.map { if (it.startsWith("SIM") && radio != "Авто") "$it|$radio" else it }
            val values = selected.associateWith { prefs.getString(it, null)?.toIntOrNull() }
            val time = if (style == 4) prefs.getLong("latency-time", 0)
                else selected.minOf { prefs.getLong("measured-$it", prefs.getLong("time", 0)) }
            val foreground = color(prefs.getString("foreground-$id", "#FFFFFF"), Color.WHITE)
            val accent = color(prefs.getString("accent-$id", "#58D8BD"), Color.CYAN)
            val rawBackground = color(prefs.getString("background-$id", "#1B2434"), Color.DKGRAY)
            val opacity = prefs.getInt("opacity-$id", 85).coerceIn(0, 100) * 255 / 100
            val background = Color.argb(opacity, Color.red(rawBackground), Color.green(rawBackground), Color.blue(rawBackground))
            val units = prefs.getInt("units-$id", 0)
            fun signalText(key: String, value: Int?): String {
                if (value == null) return "—"
                val level = percent(value, key == "Wi-Fi")
                return when (units) {
                    1 -> "$level%"
                    2 -> when { level >= 75 -> "Отлично"; level >= 50 -> "Хорошо"; level >= 25 -> "Слабо"; else -> "Очень слабо" }
                    else -> "$value dBm"
                }
            }
            val text = if (style == 4) prefs.getString("latency", null)?.plus(" мс") ?: "—"
                else values.entries.joinToString(" · ") { (key, value) ->
                    (if (selected.size > 1) "${key.replace('|', ' ')} " else "") + signalText(key, value)
                }
            val view = RemoteViews(context.packageName, R.layout.survey_widget)
            view.setTextViewText(R.id.survey_title, if (style == 4) "HTTP · задержка" else source + if (radio != "Авто") " · $radio" else "")
            view.setTextViewText(R.id.survey_value, text)
            view.setTextViewTextSize(R.id.survey_value, android.util.TypedValue.COMPLEX_UNIT_SP, prefs.getInt("size-$id", 18).coerceIn(10, 32).toFloat())
            for (textId in listOf(R.id.survey_title, R.id.survey_value, R.id.survey_time)) view.setTextColor(textId, foreground)
            view.setInt(R.id.survey_root, "setBackgroundColor", background)
            var timestamp = if (time > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time)) else "Откройте измерение"
            if (prefs.getBoolean("battery-$id", false)) {
                val battery = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (battery in 0..100) timestamp += " · АКБ $battery%"
            }
            view.setTextViewText(R.id.survey_time, timestamp)
            view.setViewVisibility(R.id.survey_time, if (style == 2) View.GONE else View.VISIBLE)
            view.setViewVisibility(R.id.survey_bar, View.GONE)
            val graphic = style in listOf(0, 1, 3, 5)
            view.setViewVisibility(R.id.survey_graphic, if (graphic) View.VISIBLE else View.GONE)
            if (graphic) {
                val options = manager.getAppWidgetOptions(id)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220).coerceIn(110, 550)
                val height = if (style == 3) 100 else 54
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; color = accent }
                values.entries.forEachIndexed { index, (key, value) ->
                    val fraction = (value?.let { percent(it, key == "Wi-Fi") } ?: 0) / 100f
                    val rowWidth = width.toFloat() / selected.size
                    val left = index * rowWidth + 5
                    when (style) {
                        3 -> {
                            paint.style = Paint.Style.STROKE
                            paint.color = Color.argb(75, Color.red(accent), Color.green(accent), Color.blue(accent))
                            val radius = minOf(rowWidth / 2 - 8, height / 2f - 8).coerceAtLeast(4f)
                            val cx = left + rowWidth / 2 - 5
                            val cy = height / 2f
                            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                            canvas.drawArc(bounds, 135f, 270f, false, paint)
                            paint.color = accent
                            canvas.drawArc(bounds, 135f, 270 * fraction, false, paint)
                        }
                        5 -> {
                            val history = runCatching { JSONArray(prefs.getString("plot-$key", "[]")) }.getOrDefault(JSONArray())
                            paint.color = accent
                            var prior: PointF? = null
                            for (point in 0 until history.length()) {
                                if (history.isNull(point)) { prior = null; continue }
                                val next = PointF(left + (rowWidth - 10) * point / maxOf(1, history.length() - 1),
                                    height - 4f - (height - 8) * percent(history.getInt(point), key == "Wi-Fi") / 100f)
                                prior?.let { canvas.drawLine(it.x, it.y, next.x, next.y, paint) }
                                prior = next
                            }
                        }
                        else -> {
                            paint.style = Paint.Style.FILL
                            for (bar in 0 until 10) {
                                paint.color = if (bar < fraction * 10) accent else Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent))
                                val x = left + (rowWidth - 10) * bar / 10
                                val top = if (style == 1) height - 5f - (height - 10) * (bar + 1) / 10 else 12f
                                canvas.drawRoundRect(x, top, x + (rowWidth - 10) / 10 - 2, height - 5f, 2f, 2f, paint)
                            }
                        }
                    }
                }
                view.setImageViewBitmap(R.id.survey_graphic, bitmap)
            }
            val intent = Intent(context, ToolActivity::class.java).putExtra("screen", "radio").putExtra("tab", if (style == 4) 3 else 0)
            view.setOnClickPendingIntent(R.id.survey_root, PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            manager.updateAppWidget(id, view)
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { render(context, manager, it) } }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { render(context, manager, id) }
    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = context.getSharedPreferences("radio-widget", 0)
        val edit = prefs.edit()
        prefs.all.keys.filter { key -> ids.any { key.endsWith("-$it") } }.forEach(edit::remove)
        edit.apply()
    }
}

class RadioWidgetSetup : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val prefs = getSharedPreferences("radio-widget", 0)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 8) }
        fun spinner(title: String, choices: List<String>, selected: Int): Spinner {
            form.addView(TextView(this).apply { text = title })
            return Spinner(this).also {
                it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
                it.setSelection(selected.coerceIn(choices.indices))
                form.addView(it)
            }
        }
        fun field(title: String, value: String): EditText {
            form.addView(TextView(this).apply { text = title })
            return EditText(this).apply { setText(value); isSingleLine = true; form.addView(this) }
        }
        val sources = listOf("SIM 1", "SIM 2", "Wi-Fi", "Общий кэш", "Все сигналы")
        val source = spinner("Источник", sources, sources.indexOf(prefs.getString("source-$id", "SIM 1")))
        val radios = listOf("Авто", "LTE", "NR", "GSM", "WCDMA", "TD-SCDMA", "CDMA")
        val radio = spinner("Тип сигнала SIM", radios, radios.indexOf(prefs.getString("rat-$id", "Авто")))
        val style = spinner("Вид", listOf("Шкала", "Столбики", "Компактный", "Круговой", "Задержка HTTP", "График"), prefs.getInt("style-$id", 0))
        val units = spinner("Показания", listOf("dBm", "Проценты", "Качество"), prefs.getInt("units-$id", 0))
        val foreground = field("Цвет текста #RRGGBB", prefs.getString("foreground-$id", "#FFFFFF").orEmpty())
        val accent = field("Цвет индикатора #RRGGBB", prefs.getString("accent-$id", "#58D8BD").orEmpty())
        val background = field("Цвет фона #RRGGBB", prefs.getString("background-$id", "#1B2434").orEmpty())
        val opacity = field("Непрозрачность фона 0–100%", prefs.getInt("opacity-$id", 85).toString())
        val size = field("Размер текста 10–32 sp", prefs.getInt("size-$id", 18).toString())
        val battery = CheckBox(this).apply { text = "Заряд батареи"; isChecked = prefs.getBoolean("battery-$id", false); form.addView(this) }
        val dialog = AlertDialog.Builder(this).setTitle("Виджет связи").setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Отмена") { _, _ -> finish() }.setOnCancelListener { finish() }
            .setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val colors = listOf(foreground, accent, background)
                if (colors.any { runCatching { Color.parseColor(it.text.toString()) }.isFailure }) {
                    Toast.makeText(this, "Проверьте цвета", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val alpha = opacity.text.toString().toIntOrNull()
                val font = size.text.toString().toIntOrNull()
                if (alpha == null || alpha !in 0..100 || font == null || font !in 10..32) {
                    Toast.makeText(this, "Проверьте непрозрачность и размер", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("rat-$id", radio.selectedItem.toString()).putInt("style-$id", style.selectedItemPosition).putString("source-$id", source.selectedItem.toString())
                    .putInt("units-$id", units.selectedItemPosition).putString("foreground-$id", foreground.text.toString())
                    .putString("accent-$id", accent.text.toString()).putString("background-$id", background.text.toString())
                    .putInt("opacity-$id", alpha).putInt("size-$id", font).putBoolean("battery-$id", battery.isChecked).apply()
                RadioWidget.render(this, AppWidgetManager.getInstance(this), id)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }
}
