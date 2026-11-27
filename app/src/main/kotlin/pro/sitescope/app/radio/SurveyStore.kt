package pro.sitescope.app.radio

import android.content.*
import android.database.Cursor
import android.database.sqlite.*
import android.location.Location
import android.util.Xml
import java.io.*

data class Survey(val id:Long,val start:Long,val end:Long?,val count:Int)
data class Sample(val time:Long,val sim:String,val rat:String,val dbm:Int?,val cid:Long?,val lat:Double?,val lon:Double?,val detail:String,val measured:Long=0)

class SurveyStore private constructor(ctx:Context):SQLiteOpenHelper(ctx,"radio-surveys.db",null,2) {
    companion object {
        @Volatile private var instance:SurveyStore?=null
        fun get(ctx:Context)=instance ?: synchronized(this){instance ?: SurveyStore(ctx.applicationContext).also {instance=it}}
    }
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY, start INTEGER NOT NULL, end INTEGER)")
        db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY, session INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, time INTEGER, sim TEXT, rat TEXT, serving INTEGER, mcc TEXT, mnc TEXT, area INTEGER, cid INTEGER, pci INTEGER, channel INTEGER, dbm INTEGER, detail TEXT, measured INTEGER, latitude REAL, longitude REAL, accuracy REAL)")
        createEvents(db)
        db.execSQL("CREATE INDEX sample_session ON samples(session,time)")
        db.execSQL("CREATE TABLE towers(rat TEXT NOT NULL,mcc TEXT NOT NULL,mnc TEXT NOT NULL,area INTEGER NOT NULL,cid INTEGER NOT NULL,pci INTEGER NOT NULL,channel INTEGER,latitude REAL,longitude REAL,accuracy INTEGER,name TEXT,bearing INTEGER,PRIMARY KEY(rat,mcc,mnc,area,cid,pci))")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) {
        if (old < 2) createEvents(db)
    }
    private fun createEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, time INTEGER NOT NULL, message TEXT NOT NULL)")
    }
    fun begin(time:Long):Long {
        val db=writableDatabase
        db.beginTransaction()
        try {
            // Seal a session interrupted by process death at its actual last measurement.
            db.execSQL("UPDATE sessions SET end=COALESCE((SELECT MAX(time) FROM samples WHERE session=sessions.id),start) WHERE end IS NULL")
            val id=db.insertOrThrow("sessions",null,ContentValues().apply{put("start",time)})
            db.setTransactionSuccessful()
            return id
        } finally {db.endTransaction()}
    }
    fun finish(id:Long) {writableDatabase.execSQL("UPDATE sessions SET end=? WHERE id=? AND end IS NULL",arrayOf(System.currentTimeMillis(),id))}
    fun append(id:Long,f:RadioFrame,loc:Location?) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            fun values()=ContentValues().apply {
                put("session",id);put("time",f.time)
                if(loc!=null){put("latitude",loc.latitude);put("longitude",loc.longitude);if(loc.hasAccuracy()) put("accuracy",loc.accuracy)}
            }
            for(c in f.cells) db.insertOrThrow("samples",null,values().apply {
                put("sim",c.sim);put("rat",c.rat);put("serving",if(c.registered)1 else 0);put("mcc",c.mcc);put("mnc",c.mnc)
                put("area",c.area);put("cid",c.cid);put("pci",c.pci);put("channel",c.channel);put("dbm",c.dbm);put("detail",c.details);put("measured",c.measured)
            })
            f.wifi?.let { w->db.insertOrThrow("samples",null,values().apply{put("sim","Wi-Fi");put("rat","Wi-Fi");put("serving",1);put("dbm",w.dbm);put("detail","${w.ssid} · ${w.frequency} МГц");put("measured",f.time)}) }
            if(f.cells.isEmpty() && f.wifi==null) db.insertOrThrow("samples",null,values().apply{put("rat","—");put("detail",f.note.ifBlank {"Нет измерений"})})
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun surveys():List<Survey> = readableDatabase.rawQuery("SELECT s.id,s.start,s.end,COUNT(p.id) FROM sessions s LEFT JOIN samples p ON s.id=p.session GROUP BY s.id ORDER BY s.id DESC",null).use {c->
        buildList {while(c.moveToNext())add(Survey(c.getLong(0),c.getLong(1),if(c.isNull(2))null else c.getLong(2),c.getInt(3)))}
    }
    private fun Cursor.optionalDouble(i:Int)=if(isNull(i)) null else getDouble(i)
    fun latest(id:Long):List<Sample> = readableDatabase.rawQuery("SELECT time,sim,rat,dbm,cid,latitude,longitude,detail,measured FROM samples WHERE session=? ORDER BY id DESC LIMIT 1000",arrayOf(id.toString())).use {c->
        buildList {while(c.moveToNext())add(Sample(c.getLong(0),c.getString(1).orEmpty(),c.getString(2),if(c.isNull(3))null else c.getInt(3),if(c.isNull(4))null else c.getLong(4),c.optionalDouble(5),c.optionalDouble(6),c.getString(7).orEmpty(),if(c.isNull(8))0 else c.getLong(8)))}
    }
    fun export(id:Long,kml:Boolean,output:OutputStream) {
        val db=readableDatabase
        if(kml) {
            val xml=Xml.newSerializer();xml.setOutput(output,"UTF-8");xml.startDocument("UTF-8",true)
            xml.startTag(null,"kml").attribute(null,"xmlns","http://www.opengis.net/kml/2.2").startTag(null,"Document")
            db.rawQuery("SELECT time,sim,rat,dbm,latitude,longitude FROM samples WHERE session=? AND latitude IS NOT NULL AND (serving=1 OR serving IS NULL) ORDER BY time",arrayOf(id.toString())).use { c->
                while(c.moveToNext()){
                    xml.startTag(null,"Placemark").startTag(null,"name").text(listOfNotNull(c.getString(1),c.getString(2),c.getString(3)?.plus(" dBm")).joinToString(" · ")).endTag(null,"name")
                    xml.startTag(null,"TimeStamp").startTag(null,"when").text(java.time.Instant.ofEpochMilli(c.getLong(0)).toString()).endTag(null,"when").endTag(null,"TimeStamp")
                    xml.startTag(null,"Point").startTag(null,"coordinates").text("${c.getDouble(5)},${c.getDouble(4)}").endTag(null,"coordinates").endTag(null,"Point").endTag(null,"Placemark")
                }
            }
            xml.endTag(null,"Document").endTag(null,"kml");xml.endDocument();return
        }
        val writer=output.bufferedWriter()
        db.rawQuery("SELECT time,sim,rat,serving,mcc,mnc,area,cid,pci,channel,dbm,detail,measured,latitude,longitude,accuracy FROM samples WHERE session=? ORDER BY id",arrayOf(id.toString())).use {c->
            writer.appendLine(c.columnNames.joinToString(","))
            while(c.moveToNext()) writer.appendLine(c.columnNames.indices.joinToString(","){i->"\""+c.getString(i).orEmpty().replace("\"","\"\"")+"\""})
        }
        writer.flush()
    }
    fun saveTower(t:Tower) {
        writableDatabase.insertWithOnConflict("towers",null,ContentValues().apply{
            put("rat",t.rat);put("mcc",t.mcc);put("mnc",t.mnc);put("area",t.area);put("cid",t.cid);put("pci",t.pci ?: -1);put("channel",t.channel)
            put("latitude",t.lat);put("longitude",t.lon);put("accuracy",t.accuracy);put("name",t.name);put("bearing",t.bearing)
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    fun importTowers(input: InputStream): Pair<Int, Int> {
        var imported = 0
        var skipped = 0
        var bytes = 0L
        fun readRows(source: InputStream) {
            val reader = source.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                bytes += line.toByteArray().size + 1
                require(bytes <= 128_000_000 && imported + skipped < 1_000_000 && line.length < 32768) {
                    "Слишком большой справочник"
                }
                val parsed = parseTower(line)
                if (parsed == null) skipped++ else { saveTower(parsed); imported++ }
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val buffered = input.buffered()
            buffered.mark(4)
            val signature = ByteArray(4)
            val length = buffered.read(signature)
            buffered.reset()
            when {
                length >= 2 && signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte() ->
                    java.util.zip.GZIPInputStream(buffered).use { readRows(it) }
                length >= 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte() ->
                    java.util.zip.ZipInputStream(buffered).use { zip ->
                        var count = 0
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            require(++count <= 1000) { "Слишком много файлов" }
                            if (!entry.isDirectory && entry.name.substringAfterLast('.').lowercase() in listOf("clf", "csv", "txt")) {
                                readRows(zip)
                            }
                            zip.closeEntry()
                        }
                    }
                else -> buffered.use { readRows(it) }
            }
            require(imported > 0) { "Подходящих записей нет" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return imported to skipped
    }
    private fun tower(c:Cursor)=Tower(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getInt(5).takeIf{it>=0},
        if(c.isNull(6))null else c.getInt(6),c.optionalDouble(7),c.optionalDouble(8),if(c.isNull(9))null else c.getInt(9),c.getString(10).orEmpty(),if(c.isNull(11))null else c.getInt(11))
    fun towers(query:String):List<Tower> {
        val search="%${query.trim()}%"
        return readableDatabase.rawQuery("SELECT * FROM towers WHERE name LIKE ? OR CAST(cid AS TEXT) LIKE ? OR mcc||mnc LIKE ? ORDER BY name,cid LIMIT 200",arrayOf(search,search,search)).use {c-> buildList {while(c.moveToNext())add(tower(c))} }
    }
    fun nearby(pin:Pin, query:String):List<Tower> {
        val span=20.2/110.0
        val search="%${query.trim()}%"
        return readableDatabase.rawQuery("SELECT * FROM towers WHERE latitude BETWEEN ? AND ? AND longitude IS NOT NULL AND (name LIKE ? OR CAST(cid AS TEXT) LIKE ? OR mcc||mnc LIKE ?)",arrayOf((pin.lat-span).toString(),(pin.lat+span).toString(),search,search,search)).use { c->
            val nearest=java.util.PriorityQueue<Pair<Float,Tower>>(compareByDescending { it.first })
            val distance=FloatArray(1)
            while(c.moveToNext()){
                val t=tower(c)
                Location.distanceBetween(pin.lat,pin.lon,t.lat!!,t.lon!!,distance)
                if(distance[0]<=20000f){nearest.add(distance[0] to t);if(nearest.size>200)nearest.remove()}
            }
            nearest.sortedBy{it.first}.map{it.second}
        }
    }
    fun addEvent(message: String) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("time", System.currentTimeMillis())
            put("message", message)
        })
        writableDatabase.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 20000)")
    }
    fun events(): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT time,message FROM events ORDER BY id DESC LIMIT 200", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
        }
    fun deleteSession(id: Long) {
        check(SurveyService.active.value != id) { "Сначала остановите запись" }
        writableDatabase.delete("sessions", "id=?", arrayOf(id.toString()))
    }
    fun replaceTower(old: Tower?, replacement: Tower) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (old != null) deleteTower(old)
            saveTower(replacement)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun deleteTower(tower: Tower) {
        writableDatabase.delete("towers", "rat=? AND mcc=? AND mnc=? AND area=? AND cid=? AND pci=?",
            arrayOf(tower.rat, tower.mcc, tower.mnc, tower.area.toString(), tower.cid.toString(), (tower.pci ?: -1).toString()))
    }
    fun exportTowers(output:OutputStream) {
        val w=output.bufferedWriter()
        readableDatabase.rawQuery("SELECT * FROM towers ORDER BY mcc,mnc,cid",null).use {c->while(c.moveToNext()) w.appendLine(tower(c).clf())};w.flush()
    }
}

