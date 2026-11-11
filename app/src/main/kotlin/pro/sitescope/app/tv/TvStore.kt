package pro.sitescope.app.tv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.DataStore
import pro.sitescope.app.IptvEngine
import pro.sitescope.app.MulticastProbe
import java.io.File

class TvSource(var id: String, var name: String, var type: String, var url: String) {
    var addedAt = 0L; var updatedAt = 0L; var count = 0
}

/** Хранилище раздела ТВ: источники, каналы, избранное и настройки. Один экземпляр на приложение. */
object TvStore {
    private const val FILE = "tv_state.json"
    private var loaded = false
    val sources = ArrayList<TvSource>()
    val channels = ArrayList<Channel>()
    val epgUrls = ArrayList<String>()
    var udpxyBase = ""
    var threads = 8
    var timeoutMs = 8000
    var lastCheckAt = 0L
    var autoCheck = false

    @Synchronized fun load(context: Context) {
        if (loaded) return
        loaded = true
        installBridge(context.applicationContext)
        val file = File(context.filesDir, FILE)
        try {
            if (file.exists() || File(file.path + ".bak").exists()) {
                AtomicFile(file).openRead().bufferedReader().use { read(JSONObject(it.readText())) }
            } else migrateLegacy(context)
        } catch (e: Exception) { /* повреждённый файл — начинаем с чистого листа */ }
    }

    private fun read(o: JSONObject) {
        sources.clear(); channels.clear(); epgUrls.clear()
        udpxyBase = o.optString("udpxy"); threads = o.optInt("threads", 8); timeoutMs = o.optInt("timeout", 8000)
        lastCheckAt = o.optLong("lastCheck"); autoCheck = o.optBoolean("autoCheck")
        o.optJSONArray("epg")?.let { for (i in 0 until it.length()) epgUrls.add(it.getString(i)) }
        o.optJSONArray("sources")?.let {
            for (i in 0 until it.length()) {
                val s = it.getJSONObject(i)
                sources.add(TvSource(s.optString("id"), s.optString("name"), s.optString("type"), s.optString("url")).apply {
                    addedAt = s.optLong("addedAt"); updatedAt = s.optLong("updatedAt"); count = s.optInt("count")
                })
            }
        }
        o.optJSONArray("channels")?.let { for (i in 0 until it.length()) channels.add(channelFrom(it.getJSONObject(i))) }
    }

    /** Переносит каналы и избранное из версии 0.39. */
    private fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences("sitescope_iptv", Context.MODE_PRIVATE)
        val raw = prefs.getString("channels", "[]") ?: "[]"
        val favorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        udpxyBase = prefs.getString("udpxy", "") ?: ""
        val array = JSONArray(raw)
        if (array.length() == 0) return
        val source = TvSource("legacy", "Каналы из прежней версии", "import", "")
        source.addedAt = System.currentTimeMillis(); source.updatedAt = source.addedAt
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val c = Channel()
            c.name = o.optString("name"); c.group = o.optString("group"); c.logo = o.optString("logoUrl")
            c.url = o.optString("sourceUrl"); c.tvgId = o.optString("id")
            c.video = o.optString("videoCodec"); c.audio = o.optString("audioCodec"); c.container = o.optString("container")
            if (c.url.isEmpty()) continue
            c.id = Playlist.channelId(c.url, c.name); c.sourceId = source.id; c.number = channels.size + 1
            c.favorite = favorites.contains(c.url) || favorites.contains(c.name + "|" + c.url) || favorites.contains(c.name)
            channels.add(c)
        }
        source.count = channels.size
        if (channels.isNotEmpty()) { sources.add(source); save(context) }
    }

    @Synchronized fun save(context: Context) {
        try {
            writeState(context, snapshotState())
            if (channels.isEmpty()) DataStore(context).removeProject("IPTV", "iptv://catalog")
        } catch (e: Exception) { android.util.Log.e("TvStore", "Cannot save TV catalog", e) }
    }

    private fun snapshotState(): JSONObject {
            val o = JSONObject().put("schemaVersion", 1)
            o.put("udpxy", udpxyBase); o.put("threads", threads); o.put("timeout", timeoutMs)
            o.put("lastCheck", lastCheckAt); o.put("autoCheck", autoCheck)
            o.put("epg", JSONArray(epgUrls))
            val src = JSONArray()
            for (s in sources) src.put(JSONObject().put("id", s.id).put("name", s.name).put("type", s.type).put("url", s.url)
                .put("addedAt", s.addedAt).put("updatedAt", s.updatedAt).put("count", s.count))
            o.put("sources", src)
            val ch = JSONArray()
            for (c in channels) ch.put(channelTo(c))
            o.put("channels", ch)
            return o
    }

    private fun writeState(context: Context, state: JSONObject) {
        val file = AtomicFile(File(context.filesDir, FILE))
        val output = file.startWrite()
        try { output.write(state.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    @Synchronized fun exportState(context: Context): JSONObject {
        load(context)
        return snapshotState()
    }

    fun validateState(state: JSONObject) {
        require(state.optInt("schemaVersion", 1) == 1) { "Неподдерживаемый формат ТВ-каталога" }
        for (key in listOf("sources", "channels", "epg")) {
            val rows = state.getJSONArray(key)
            for (i in 0 until rows.length()) {
                if (key == "epg") require(rows.get(i) is String) { "Неверный адрес телепрограммы" }
                else rows.getJSONObject(i)
            }
        }
    }

    @Synchronized fun restoreState(context: Context, state: JSONObject) {
        validateState(state)
        writeState(context, state)
        read(state); loaded = true; installBridge(context.applicationContext)
    }

    /** Backups before schema 7 stored the catalog in legacy preferences. */
    @Synchronized fun restoreLegacy(context: Context) {
        read(JSONObject()); loaded = true; installBridge(context.applicationContext)
        migrateLegacy(context)
        writeState(context, snapshotState())
    }

    @Synchronized fun snapshot(): List<Channel> = ArrayList(channels)

    /** Добавляет или обновляет источник, сохраняя избранное и историю проверок совпавших каналов. */
    @Synchronized fun putSource(source: TvSource, parsed: PlaylistData): Int {
        val old = channels.filter { it.sourceId == source.id }.associateBy { it.id }
        channels.removeAll { it.sourceId == source.id }
        for (c in parsed.channels) {
            c.sourceId = source.id
            old[c.id]?.let { p ->
                c.favorite = p.favorite; c.okCount = p.okCount; c.failCount = p.failCount; c.status = p.status
                c.detail = p.detail; c.checkedAt = p.checkedAt; c.startMs = p.startMs; c.bitrateKbps = p.bitrateKbps
                c.video = p.video; c.audio = p.audio; c.resolution = p.resolution; c.container = p.container; c.lastWatched = p.lastWatched
            }
            channels.add(c)
        }
        for (u in parsed.epgUrls) if (!epgUrls.contains(u)) epgUrls.add(u)
        source.count = parsed.channels.size; source.updatedAt = System.currentTimeMillis()
        if (source.addedAt == 0L) source.addedAt = source.updatedAt
        val index = sources.indexOfFirst { it.id == source.id }
        if (index >= 0) sources[index] = source else sources.add(source)
        return parsed.channels.size
    }

    @Synchronized fun removeSource(id: String) { sources.removeAll { it.id == id }; channels.removeAll { it.sourceId == id } }
    @Synchronized fun removeChannels(predicate: (Channel) -> Boolean): Int { val before = channels.size; channels.removeAll(predicate); return before - channels.size }
    @Synchronized fun dedupe(): Int = Playlist.dedupe(channels)
    @Synchronized fun find(id: String): Channel? = channels.firstOrNull { it.id == id }

    fun saveHistory(context: Context) {
        try {
            val list = snapshot()
            DataStore(context).saveIptvSnapshot(list.size, list.count { it.status == StreamStatus.OK || it.status == StreamStatus.SLOW },
                udpxyBase.ifEmpty { "Плейлист" })
        } catch (e: Exception) { }
    }

    private fun channelTo(c: Channel): JSONObject = JSONObject()
        .put("id", c.id).put("n", c.name).put("g", c.group).put("l", c.logo).put("u", c.url).put("t", c.tvgId).put("tn", c.tvgName)
        .put("ua", c.userAgent).put("ref", c.referrer).put("cu", c.catchup).put("cs", c.catchupSource).put("cd", c.catchupDays)
        .put("s", c.sourceId).put("no", c.number).put("st", c.status.name).put("d", c.detail).put("ms", c.startMs)
        .put("br", c.bitrateKbps).put("v", c.video).put("a", c.audio).put("r", c.resolution).put("c", c.container)
        .put("h", c.httpCode).put("at", c.checkedAt).put("ok", c.okCount).put("ko", c.failCount).put("f", c.favorite).put("w", c.lastWatched)

    private fun channelFrom(o: JSONObject): Channel = Channel().apply {
        id = o.optString("id"); name = o.optString("n"); group = o.optString("g"); logo = o.optString("l"); url = o.optString("u"); tvgId = o.optString("t"); tvgName = o.optString("tn")
        userAgent = o.optString("ua"); referrer = o.optString("ref"); catchup = o.optString("cu"); catchupSource = o.optString("cs"); catchupDays = o.optInt("cd")
        sourceId = o.optString("s"); number = o.optInt("no")
        status = try { StreamStatus.valueOf(o.optString("st", "UNKNOWN")) } catch (e: Exception) { StreamStatus.UNKNOWN }
        detail = o.optString("d"); startMs = o.optLong("ms"); bitrateKbps = o.optLong("br"); video = o.optString("v"); audio = o.optString("a")
        resolution = o.optString("r"); container = o.optString("c"); httpCode = o.optInt("h"); checkedAt = o.optLong("at")
        okCount = o.optInt("ok"); failCount = o.optInt("ko"); favorite = o.optBoolean("f"); lastWatched = o.optLong("w")
    }

    // ---------- Связь с прежними движками ----------

    private var bridgeContext: Context? = null

    private fun installBridge(context: Context) {
        bridgeContext = context
        StreamChecker.tsInspector = { data ->
            val probe = IptvEngine.ProbeResult()
            try {
                IptvEngine.fillMpegTsProbe(probe, data, "MPEG-TS", "")
                StreamChecker.TsInfo(probe.videoCodec ?: "", probe.audioCodec ?: "", probe.serviceName ?: "")
            } catch (e: Exception) { null }
        }
    }

    /** Проверка udp:// и rtp:// прямо в локальной сети — прежним MulticastProbe. */
    fun localCheck(channel: Channel): StreamChecker.Result {
        val r = StreamChecker.Result()
        val context = bridgeContext ?: return r.apply { status = StreamStatus.UNKNOWN; detail = "Локальный поток" }
        val started = System.currentTimeMillis()
        val p = MulticastProbe.probe(context, channel.url, 4000)
        r.startMs = System.currentTimeMillis() - started
        r.container = p.container ?: ""; r.video = p.videoCodec ?: ""; r.audio = p.audioCodec ?: ""
        r.serviceName = p.serviceName ?: ""; r.bitrateKbps = p.bitrateKbps
        if (p.playable) { r.status = StreamStatus.OK; r.detail = "Поток идёт в вашей сети" }
        else { r.status = StreamStatus.DEAD; r.detail = p.error?.takeIf { it.isNotEmpty() } ?: "В вашей сети этот поток не идёт" }
        return r
    }

    /** Загружает текст плейлиста по ссылке (с учётом больших файлов). */
    fun download(url: String, maxBytes: Int = 40_000_000): String {
        var current = url
        for (i in 0 until 6) {
            val c = java.net.URL(current).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 12000; c.readTimeout = 25000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", StreamChecker.DEFAULT_UA)
                val code = c.responseCode
                if (code in 300..399) { current = StreamChecker.resolve(current, c.getHeaderField("Location") ?: throw java.io.IOException("Переадресация без адреса")); continue }
                if (code !in 200..299) throw java.io.IOException(when (code) {
                    401, 403 -> "Сервер отказал в доступе ($code). Проверьте логин, пароль или срок подписки."
                    404 -> "Плейлист не найден (404). Проверьте ссылку."
                    else -> "Сервер ответил кодом $code"
                })
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32768)
                var input: java.io.InputStream = java.io.BufferedInputStream(c.inputStream)
                input.mark(4); val b0 = input.read(); val b1 = input.read(); input.reset()
                if (b0 == 0x1f && b1 == 0x8b) input = java.util.zip.GZIPInputStream(input)
                while (out.size() < maxBytes) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                return String(out.toByteArray(), Charsets.UTF_8)
            } finally { c.disconnect() }
        }
        throw java.io.IOException("Слишком много переадресаций")
    }
}
