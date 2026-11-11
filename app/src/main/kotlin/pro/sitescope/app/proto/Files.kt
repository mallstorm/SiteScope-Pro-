package pro.sitescope.app.proto

import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.Properties

class RemoteItem(val name: String, val path: String, val directory: Boolean, val size: Long, val modified: Long = 0)

/** Общий вид удалённого хранилища: один файловый экран работает с SFTP, FTP, FTPS и WebDAV. */
interface RemoteFs {
    val title: String
    fun home(): String
    fun list(path: String): List<RemoteItem>
    fun download(path: String, target: OutputStream)
    fun upload(path: String, source: InputStream)
    fun mkdir(path: String)
    fun delete(item: RemoteItem)
    fun rename(from: String, to: String)
    fun close()
}

fun joinPath(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"
fun parentPath(path: String): String { val p = path.trimEnd('/'); val i = p.lastIndexOf('/'); return if (i <= 0) "/" else p.substring(0, i) }

private fun readLine(input: InputStream): String? {
    val b = ByteArrayOutputStream(); var c: Int
    while (true) {
        c = input.read(); if (c == -1 || c == '\n'.code) break
        if (b.size() >= 16384) throw java.io.IOException("Слишком длинная строка ответа")
        if (c != '\r'.code) b.write(c)
    }
    return if (c == -1 && b.size() == 0) null else b.toString("UTF-8")
}

// ------------------------------------------------------------------ FTP и FTPS

class FtpFs(private val host: String, private val port: Int, private val tls: Boolean, user: String, pass: String) : RemoteFs {
    private val user = user.ifEmpty { "anonymous" }
    private val pass = pass.ifEmpty { "sitescope@localhost" }
    private var control: Socket? = null
    private lateinit var input: BufferedInputStream
    private lateinit var out: BufferedOutputStream
    private var last = ""
    override val title = if (tls) "FTPS" else "FTP"

    @Synchronized fun connect() {
        val p = if (port > 0) port else 21
        val implicit = tls && p == 990
        var s: Socket
        if (implicit) { s = TlsSockets.connect(host, p) }
        else { s = Socket(); s.connect(InetSocketAddress(host, p), 10000) }
        s.soTimeout = 15000; streams(s); expect(220)
        if (tls && !implicit) {
            send("AUTH TLS"); expect(234)
            s = TlsSockets.upgrade(s, host, p); streams(s)
        }
        control = s
        send("USER $user")
        when (val code = reply()) { 331 -> { send("PASS $pass"); if (reply() != 230) throw Exception("Неверный логин или пароль") }; 230 -> { }; else -> throw Exception("Сервер не принял логин ($code)") }
        send("TYPE I"); expect(200)
        if (tls) { send("PBSZ 0"); expect(200); send("PROT P"); expect(200) }
    }

    private fun streams(s: Socket) { input = BufferedInputStream(s.getInputStream()); out = BufferedOutputStream(s.getOutputStream()) }
    private fun send(line: String) { out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush() }
    private fun expect(wanted: Int) { val c = reply(); if (c != wanted) throw Exception("Сервер ответил $c: " + last.lineSequence().lastOrNull().orEmpty()) }
    private fun reply(): Int {
        val first = readLine(input) ?: throw Exception("Соединение закрыто")
        val all = StringBuilder(first); val code = first.take(3).toIntOrNull() ?: throw Exception("Непонятный ответ сервера")
        if (first.length > 3 && first[3] == '-') { val end = first.take(3) + " "; while (true) { val line = readLine(input) ?: break; all.append('\n').append(line); if (line.startsWith(end)) break } }
        last = all.toString(); return code
    }

    private fun passive(): Socket {
        send("EPSV"); val code = reply(); val p: Int
        if (code == 229) { val a = last.lastIndexOf("|||"); val b = last.indexOf('|', a + 3); p = last.substring(a + 3, b).toInt() }
        else { send("PASV"); expect(227); val x = last.substring(last.indexOf('(') + 1, last.indexOf(')')).split(","); p = x[4].trim().toInt() * 256 + x[5].trim().toInt() }
        val data = Socket(); data.connect(InetSocketAddress(host, p), 10000); data.soTimeout = 20000
        if (tls) return TlsSockets.upgrade(data, host, p, 20000)
        return data
    }

    private fun started(code: Int, what: String) { if (code != 150 && code != 125) throw Exception("$what: сервер ответил $code") }

    override fun home(): String = try { synchronized(this) { send("PWD"); if (reply() == 257) last.substringAfter('"').substringBefore('"').ifEmpty { "/" } else "/" } } catch (e: Exception) { "/" }

    @Synchronized override fun list(path: String): List<RemoteItem> {
        var data = passive(); send("MLSD $path"); var code = reply(); val mlsd = code == 150 || code == 125
        if (!mlsd) { data.close(); data = passive(); send("LIST $path"); code = reply(); started(code, "Список файлов") }
        val raw = data.getInputStream().readBytes(); data.close(); reply()
        val out = ArrayList<RemoteItem>()
        for (line in String(raw, Charsets.UTF_8).split(Regex("\\r?\\n"))) {
            if (line.isBlank()) continue
            if (mlsd) {
                val split = line.split(" ", limit = 2); if (split.size < 2) continue
                val facts = split[0].lowercase(); val name = split[1].trim()
                if (name == "." || name == ".." || facts.contains("type=cdir") || facts.contains("type=pdir")) continue
                val size = Regex("size=(\\d+)").find(facts)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                out.add(RemoteItem(name, joinPath(path, name), facts.contains("type=dir"), size))
            } else {
                val parts = line.trim().split(Regex("\\s+"), limit = 9); if (parts.size < 9) continue
                val name = parts[8].substringBefore(" -> "); if (name == "." || name == "..") continue
                out.add(RemoteItem(name, joinPath(path, name), line.startsWith("d"), parts[4].toLongOrNull() ?: 0))
            }
        }
        return out
    }

    @Synchronized override fun download(path: String, target: OutputStream) { val data = passive(); send("RETR $path"); started(reply(), "Скачивание"); data.getInputStream().copyTo(target); data.close(); reply() }
    @Synchronized override fun upload(path: String, source: InputStream) { val data = passive(); send("STOR $path"); started(reply(), "Загрузка"); source.copyTo(data.getOutputStream()); data.getOutputStream().flush(); data.close(); reply() }
    @Synchronized override fun mkdir(path: String) { send("MKD $path"); if (reply() != 257) throw Exception("Не удалось создать папку") }
    @Synchronized override fun delete(item: RemoteItem) { send((if (item.directory) "RMD " else "DELE ") + item.path); if (reply() != 250) throw Exception(if (item.directory) "Папка не пуста или нет прав" else "Не удалось удалить файл") }
    @Synchronized override fun rename(from: String, to: String) { send("RNFR $from"); if (reply() != 350) throw Exception("Не удалось переименовать"); send("RNTO $to"); if (reply() != 250) throw Exception("Не удалось переименовать") }
    override fun close() { try { send("QUIT") } catch (e: Exception) { }; try { control?.close() } catch (e: Exception) { } }
}

// ------------------------------------------------------------------ WebDAV

class WebDavFs(private val host: String, private val port: Int, private val tls: Boolean, private val user: String, private val pass: String, private val root: String = "/") : RemoteFs {
    override val title = "WebDAV"
    private class Response(val code: Int, val body: ByteArray)
    private val active = java.util.Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var closed = false

    override fun home(): String = root.ifEmpty { "/" }

    override fun list(path: String): List<RemoteItem> {
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"
        val r = request("PROPFIND", path, "Depth: 1\r\nContent-Type: application/xml\r\n", body.toByteArray(Charsets.UTF_8)); check(r)
        val out = ArrayList<RemoteItem>()
        val p = XmlPullParserFactory.newInstance().newPullParser(); p.setInput(ByteArrayInputStream(r.body), "UTF-8")
        var open = false; var href = ""; var name = ""; var dir = false; var size = 0L; var tag = ""; var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) { tag = localName(p.name); if (tag == "response") { open = true; href = ""; name = ""; dir = false; size = 0 }; if (open && tag == "collection") dir = true }
            else if (ev == XmlPullParser.TEXT && open) { val v = p.text.trim(); if (v.isNotEmpty()) when (tag) { "href" -> href = URLDecoder.decode(v.substringAfter("://", v).let { if (v.contains("://")) "/" + it.substringAfter('/', "") else v }.replace("+", "%2B"), "UTF-8"); "displayname" -> name = v; "getcontentlength" -> size = v.toLongOrNull() ?: 0 } }
            else if (ev == XmlPullParser.END_TAG && localName(p.name) == "response" && open) {
                open = false
                if (href.isNotEmpty() && href.trimEnd('/') != path.trimEnd('/')) out.add(RemoteItem(name.ifEmpty { href.trimEnd('/').substringAfterLast('/') }, href, dir, size))
            } else if (ev == XmlPullParser.END_TAG) tag = ""
            ev = p.next()
        }
        return out
    }

    override fun download(path: String, target: OutputStream) { check(request("GET", path, "", ByteArray(0), target = target)) }
    override fun upload(path: String, source: InputStream) { check(request("PUT", path, "Content-Type: application/octet-stream\r\n", ByteArray(0), source = source)) }
    override fun mkdir(path: String) = check(request("MKCOL", path, "", ByteArray(0)))
    override fun delete(item: RemoteItem) = check(request("DELETE", item.path, "", ByteArray(0)))
    override fun rename(from: String, to: String) = check(request("MOVE", from, "Destination: " + (if (tls) "https" else "http") + "://" + host + ":" + realPort() + encode(to) + "\r\nOverwrite: F\r\n", ByteArray(0)))
    override fun close() {
        synchronized(active) {
            closed = true
            active.forEach { try { it.close() } catch (_: Exception) { } }
            active.clear()
        }
    }

    private fun check(r: Response) { if (r.code == 401) throw Exception("Неверный логин или пароль"); if (r.code == 403) throw Exception("Нет прав на это действие"); if (r.code !in 200..299) throw Exception("Сервер ответил ошибкой ${r.code}") }
    private fun realPort() = if (port > 0) port else if (tls) 443 else 80
    private fun encode(path: String): String = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun request(method: String, path: String, headers: String, body: ByteArray, target: OutputStream? = null, source: InputStream? = null): Response {
        val p = realPort()
        val plain = Socket()
        synchronized(active) {
            if (closed) throw java.io.IOException("Соединение закрыто")
            active.add(plain)
        }
        var s: Socket = plain
        try {
            plain.connect(InetSocketAddress(host, p), 10000)
            s = if (tls) TlsSockets.upgrade(plain, host, p, 20000) else plain
            s.soTimeout = 20000
            val out = BufferedOutputStream(s.getOutputStream())
            val auth = if (user.isNotEmpty()) "Authorization: Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "\r\n" else ""
            var requestPath = path.ifEmpty { "/" }; if (!requestPath.startsWith("/")) requestPath = "/$requestPath"
            val framing = if (source == null) "Content-Length: ${body.size}\r\n" else "Transfer-Encoding: chunked\r\n"
            val authority = if (host.contains(':')) "[$host]:$p" else "$host:$p"
            out.write(("$method ${encode(requestPath)} HTTP/1.1\r\nHost: $authority\r\nUser-Agent: SiteScopePro/1.0\r\nConnection: close\r\n$auth$headers$framing\r\n").toByteArray(Charsets.UTF_8))
            if (source == null) out.write(body) else writeChunked(source, out)
            out.flush()
            val input = BufferedInputStream(s.getInputStream())
            var code: Int; var chunked: Boolean; var length: Long?
            do {
                val status = readLine(input) ?: throw java.io.IOException("Сервер не ответил")
                code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("Непонятный ответ сервера")
                chunked = false; length = null
                var headerBytes = 0
                while (true) {
                    val line = readLine(input) ?: throw java.io.EOFException("Неполные заголовки ответа")
                    headerBytes += line.length
                    if (headerBytes > 65536) throw java.io.IOException("Слишком большие заголовки ответа")
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", true)) {
                        length = line.substringAfter(':').trim().toLongOrNull()?.takeIf { it >= 0 }
                            ?: throw java.io.IOException("Неверная длина ответа")
                    }
                    if (line.startsWith("transfer-encoding:", true) && line.contains("chunked", true)) chunked = true
                }
            } while (code in 100..199 && code != 101)
            val captured = ByteArrayOutputStream()
            val destination = if (code in 200..299 && target != null) target else captured
            val limit = if (destination === captured) 8L * 1024 * 1024 else Long.MAX_VALUE
            if (code != 204 && code != 304) {
                if (chunked) {
                    var remaining = limit
                    while (true) {
                        val line = readLine(input) ?: throw java.io.EOFException("Неполный ответ")
                        val size = line.substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 }
                            ?: throw java.io.IOException("Неверная длина блока")
                        if (size == 0L) break
                        if (size > remaining) throw java.io.IOException("Слишком большой ответ сервера")
                        copyBody(input, destination, size, remaining); remaining -= size
                        if (readLine(input) != "") throw java.io.IOException("Неверный конец блока")
                    }
                } else copyBody(input, destination, length, limit)
            }
            return Response(code, captured.toByteArray())
        } finally { try { s.close() } finally { active.remove(plain) } }
    }

    private fun writeChunked(source: InputStream, out: OutputStream) {
        val buffer = ByteArray(32768)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val n = source.read(buffer); if (n < 0) break; if (n == 0) continue
            out.write((n.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
            out.write(buffer, 0, n); out.write(byteArrayOf(13, 10))
        }
        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun copyBody(input: InputStream, out: OutputStream, length: Long?, limit: Long) {
        if (length != null && length > limit) throw java.io.IOException("Слишком большой ответ сервера")
        val buffer = ByteArray(32768); var copied = 0L
        while (length == null || copied < length) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val n = input.read(buffer, 0, if (length == null) buffer.size else minOf(buffer.size.toLong(), length - copied).toInt())
            if (n < 0) {
                if (length != null) throw java.io.EOFException("Передача файла оборвалась")
                break
            }
            if (copied > limit - n) throw java.io.IOException("Слишком большой ответ сервера")
            out.write(buffer, 0, n); copied += n
        }
    }
}

// ------------------------------------------------------------------ SSH и SFTP

/** Одно подключение SSH: терминал и файлы по SFTP поверх него. */
class SshClient(private val knownHosts: File, private val host: String, private val port: Int, private val user: String, private val password: String,
                private val privateKey: ByteArray?, private val confirmHost: (String) -> Boolean) {
    private var session: Session? = null
    private var shell: ChannelShell? = null
    private var shellInput: OutputStream? = null
    @Volatile var connected = false; private set

    fun connect() {
        val jsch = JSch()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.absolutePath)
        if (privateKey != null) jsch.addIdentity("sitescope-key", privateKey, null, if (password.isNotEmpty()) password.toByteArray(Charsets.UTF_8) else null)
        val s = jsch.getSession(user, host, if (port > 0) port else 22)
        if (privateKey == null) s.setPassword(password)
        s.setConfig(Properties().apply { put("StrictHostKeyChecking", "ask"); put("PreferredAuthentications", "publickey,password,keyboard-interactive") })
        s.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = password
            override fun promptPassword(message: String?): Boolean = password.isNotEmpty()
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = confirmHost(message ?: "")
            override fun showMessage(message: String?) { }
        }
        try { s.connect(12000) } catch (e: Exception) {
            val text = e.message ?: ""
            throw Exception(when { text.contains("Auth fail", true) || text.contains("Auth cancel", true) -> "Неверный логин, пароль или ключ"; text.contains("reject HostKey", true) -> "Вы не подтвердили ключ сервера"
                text.contains("HostKey has been changed", true) -> "Ключ сервера изменился! Это может быть подмена сервера."; text.contains("timeout", true) -> "Сервер не отвечает"; else -> text.ifEmpty { "Не удалось подключиться" } })
        }
        session = s; connected = true
    }

    /** Открывает терминал; [onOutput] получает уже очищенный текст. Метод возвращается, когда терминал закрыт. */
    fun runShell(onOutput: (String) -> Unit) {
        val s = session ?: throw Exception("Нет подключения")
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("dumb", 100, 40, 0, 0)
        val output = ch.inputStream; shellInput = ch.outputStream
        ch.connect(8000); shell = ch
        val buffer = ByteArray(4096)
        while (ch.isConnected) { val n = output.read(buffer); if (n < 0) break; if (n > 0) onOutput(Terminal.clean(String(buffer, 0, n, Charsets.UTF_8))) }
    }

    fun send(text: String) { Thread { try { shellInput?.let { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() } } catch (e: Exception) { } }.start() }

    fun sftp(): RemoteFs {
        val s = session ?: throw Exception("Нет подключения")
        val channel = s.openChannel("sftp") as ChannelSftp; channel.connect(8000)
        return SftpFs(channel)
    }

    fun close() { connected = false; try { shell?.disconnect() } catch (e: Exception) { }; try { session?.disconnect() } catch (e: Exception) { } }
}

class SftpFs(private val sftp: ChannelSftp) : RemoteFs {
    override val title = "SFTP"
    @Synchronized override fun home(): String = try { sftp.home ?: sftp.pwd() } catch (e: Exception) { "/" }
    @Synchronized override fun list(path: String): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        for (o in sftp.ls(path)) {
            val e = o as? ChannelSftp.LsEntry ?: continue
            if (e.filename == "." || e.filename == "..") continue
            out.add(RemoteItem(e.filename, joinPath(path, e.filename), e.attrs.isDir, e.attrs.size, e.attrs.mTime * 1000L))
        }
        return out
    }
    @Synchronized override fun download(path: String, target: OutputStream) = sftp.get(path, target)
    @Synchronized override fun upload(path: String, source: InputStream) = sftp.put(source, path)
    @Synchronized override fun mkdir(path: String) = sftp.mkdir(path)
    @Synchronized override fun delete(item: RemoteItem) = if (item.directory) sftp.rmdir(item.path) else sftp.rm(item.path)
    @Synchronized override fun rename(from: String, to: String) = sftp.rename(from, to)
    override fun close() { try { sftp.disconnect() } catch (e: Exception) { } }
}
