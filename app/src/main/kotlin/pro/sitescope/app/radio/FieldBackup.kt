package pro.sitescope.app.radio

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.satellite.DishCatalogue
import java.io.File

/** Portable values are validated against fixed table schemas before any live rows change. */
object FieldBackup {
    private val columns = linkedMapOf(
        "sessions" to "id,start,end",
        "samples" to "id,session,time,sim,rat,serving,mcc,mnc,area,cid,pci,channel,dbm,detail,measured,latitude,longitude,accuracy",
        "towers" to "rat,mcc,mnc,area,cid,pci,channel,latitude,longitude,accuracy,name,bearing",
        "events" to "id,time,message"
    ).mapValues { it.value.split(",") }

    @JvmStatic fun exportState(context: Context): JSONObject {
        val root = JSONObject().put("version", 1)
        val db = SurveyStore.get(context).readableDatabase
        db.beginTransaction()
        try {
            for ((table, fields) in columns) {
                val rows = JSONArray()
                db.query(table, fields.toTypedArray(), null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONArray()
                        fields.indices.forEach { index ->
                            row.put(when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                else -> cursor.getString(index)
                            })
                        }
                        rows.put(row)
                    }
                }
                root.put(table, rows)
            }
            val file = AtomicFile(File(context.filesDir, "dish-catalog.txt"))
            root.put("catalog", if (file.baseFile.exists()) file.openRead().use { it.reader().readText() } else "")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return root
    }

    @JvmStatic fun validate(root: JSONObject) {
        require(root.getInt("version") == 1) { "Неизвестная версия радиоизмерений" }
        var count = 0
        for ((table, fields) in columns) {
            val rows = root.getJSONArray(table)
            count += rows.length()
            require(count <= 1_000_000) { "Слишком много радиоизмерений" }
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                require(row.length() == fields.size) { "Неверная структура $table" }
                for (column in fields.indices) {
                    val value = row.get(column)
                    require(value == JSONObject.NULL || value is String || value is Number) { "Неверное значение $table" }
                    require(value !is String || value.length <= 32768) { "Слишком длинное поле $table" }
                    if (fields[column] in listOf("latitude", "longitude") && value != JSONObject.NULL) {
                        val limit = if (fields[column] == "latitude") 90.0 else 180.0
                        require(value is Number && value.toDouble() in -limit..limit) { "Неверные координаты" }
                    }
                }
            }
        }
        val text = root.getString("catalog")
        require(text.toByteArray().size <= 8_000_000) { "Слишком большой спутниковый каталог" }
        require(text.isEmpty() || DishCatalogue.parse(text.lines()).isNotEmpty()) { "Повреждён спутниковый каталог" }
    }

    /** Keeps the radio transaction open until the caller has completed the main backup restore. */
    class Restore internal constructor(private val context: Context, private val root: JSONObject) : AutoCloseable {
        private val db = SurveyStore.get(context).writableDatabase
        private val catalog = AtomicFile(File(context.filesDir, "dish-catalog.txt"))
        private val before = if (catalog.baseFile.exists()) catalog.openRead().use { it.readBytes() } else null
        private var committed = false

        init {
            db.beginTransaction()
            try {
                db.delete("samples", null, null)
                db.delete("sessions", null, null)
                db.delete("towers", null, null)
                db.delete("events", null, null)
                for ((table, fields) in columns) {
                    val rows = root.getJSONArray(table)
                    for (index in 0 until rows.length()) {
                        val row = rows.getJSONArray(index)
                        val values = ContentValues()
                        fields.forEachIndexed { column, field ->
                            when (val value = row.get(column)) {
                                JSONObject.NULL -> values.putNull(field)
                                is String -> values.put(field, value)
                                is Float, is Double -> values.put(field, (value as Number).toDouble())
                                is Number -> values.put(field, value.toLong())
                            }
                        }
                        db.insertOrThrow(table, null, values)
                    }
                }
                // A restored recording is historical and cannot inherit an old process.
                db.execSQL("UPDATE sessions SET end=COALESCE((SELECT MAX(time) FROM samples WHERE session=sessions.id),start) WHERE end IS NULL")
                writeCatalog(root.getString("catalog").toByteArray())
            } catch (failure: Exception) {
                db.endTransaction()
                restoreCatalog()
                throw failure
            }
        }
        private fun writeCatalog(bytes: ByteArray) {
            val out = catalog.startWrite()
            try { out.write(bytes); catalog.finishWrite(out) }
            catch (failure: Exception) { catalog.failWrite(out); throw failure }
        }
        private fun restoreCatalog() {
            if (before == null) catalog.delete() else writeCatalog(before)
        }
        fun commit() {
            db.setTransactionSuccessful()
            committed = true
        }
        override fun close() {
            try { db.endTransaction() }
            finally { if (!committed) restoreCatalog() }
        }
    }

    @JvmStatic fun prepareRestore(context: Context, root: JSONObject): Restore {
        check(!SurveyService.running.value && !SurveyService.closing.value) { "Остановите радиомонитор перед восстановлением" }
        validate(root)
        return Restore(context, root)
    }
}
