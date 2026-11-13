package pro.sitescope.app

import android.app.Application
import java.net.ServerSocket
import java.net.InetAddress
import java.io.ByteArrayOutputStream
import java.util.concurrent.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RouterApiTest {
    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private class Server(val handler: (Request) -> Pair<Int, String>) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val failures = CopyOnWriteArrayList<Throwable>()
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        @Volatile private var stopped = false
        init { pool.submit {
            while (!stopped) try {
                val connection = socket.accept()
                pool.submit { connection.use { c -> try {
                    c.soTimeout = 5000; val input = c.getInputStream(); val head = ByteArrayOutputStream()
                    while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) { val n = input.read(); if (n < 0) return@use; head.write(n); check(head.size() < 16384) }
                    val lines = head.toString("ISO-8859-1").split("\r\n"); val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0); var read = 0
                    while (read < bytes.size) { val n = input.read(bytes, read, bytes.size - read); if (n < 0) return@use; read += n }
                    val first = lines[0].split(' '); val request = Request(first[0], first[1], headers, String(bytes, Charsets.UTF_8)); requests.add(request)
                    val (status, response) = handler(request); val data = response.toByteArray()
                    val redirect = if (status == 302) "Location: /unexpected\r\n" else ""
                    c.getOutputStream().apply { write("HTTP/1.1 $status Test\r\nContent-Length: ${data.size}\r\nConnection: close\r\n$redirect\r\n".toByteArray()); write(data); flush() }
                } catch (e: Throwable) { if (!stopped) failures.add(e) } } }
            } catch (_: Exception) { break }
        } }
        val base get() = "http://127.0.0.1:${socket.localPort}"
        override fun close() { stopped = true; socket.close(); pool.shutdownNow() }
    }
    private fun config(base: String, kind: String = "routeros") = RouterStore.Config().apply { this.base = base; this.kind = kind; id = "test"; name = "Дом"; allowHttp = true }
    private fun standard(r: Request): Pair<Int, String> = 200 to when (r.path) {
        "/rest/system/identity" -> """[{"name":"home","password":"UNEXPORTED-PASSWORD"}]"""
        "/rest/system/resource" -> """[{"board-name":"hAP","version":"7.20","uptime":"1d","comment":"RAW-COMMENT"}]"""
        "/rest/interface" -> """[{"name":"ether1","type":"ether","running":true,"password":"UNEXPORTED-PASSWORD"}]"""
        "/rest/ip/dhcp-server/lease" -> """[{"host-name":"phone","address":"192.168.1.2","mac-address":"AA:BB:CC:DD:EE:FF","status":"bound"}]"""
        else -> error("Unexpected path ${r.path}")
    }
    @Test fun routerosUsesOnlyFourWhitelistedGetsAndNeverCopiesRawPayload() {
        Server(::standard).use { s ->
            val snapshot = RouterApiClient(null).use { it.read(config(s.base), "owner", "OwnerSecret!", "Дом") }
            RouterStore.validateSnapshot(snapshot); assertEquals(4, snapshot.getJSONArray("rows").length()); assertFalse(snapshot.getBoolean("partial"))
            assertEquals(4, s.requests.size); assertTrue(s.requests.all { it.method == "GET" && it.body.isEmpty() })
            val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("owner:OwnerSecret!".toByteArray())
            assertTrue(s.requests.all { it.headers["authorization"] == expected }); assertTrue(s.failures.isEmpty())
            for (text in listOf("UNEXPORTED-PASSWORD", "RAW-COMMENT", "OwnerSecret!", expected)) assertFalse(snapshot.toString().contains(text))
        }
    }
    @Test fun optionalPermissionsProducePartialSnapshotWithoutLeakingErrorBody() {
        Server { r -> if (r.path.endsWith("/interface")) 403 to "SECRET-ERROR-BODY" else standard(r) }.use { s ->
            val snapshot = RouterApiClient(null).use { it.read(config(s.base), "owner", "owner-secret", "Дом") }
            assertTrue(snapshot.getBoolean("partial")); assertEquals(1, snapshot.getJSONArray("warnings").length()); assertFalse(snapshot.toString().contains("SECRET-ERROR-BODY"))
        }
    }
    @Test fun authErrorRedirectInvalidAndOversizedResponsesAreBoundedAndNotEchoed() {
        for ((status, body) in listOf(401 to "SENSITIVE-RESPONSE", 302 to "SENSITIVE-RESPONSE", 200 to "bad SENSITIVE-RESPONSE", 200 to ("a".repeat(1048577)))) {
            Server { status to body }.use { s ->
                val e = assertThrows(RouterApiClient.Failure::class.java) { RouterApiClient(null).use { it.read(config(s.base), "owner", "owner-secret", "Дом") } }
                assertFalse(e.message!!.contains("SENSITIVE-RESPONSE")); assertEquals(1, s.requests.size)
            }
        }
    }
    @Test fun knownOwnerSecretsAreRemovedEvenWhenEchoedInAllowedFields() {
        Server { r -> if (r.path.endsWith("/identity")) 200 to """{"name":"owner-secret"}""" else standard(r) }.use { s ->
            val snapshot = RouterApiClient(null).use { it.read(config(s.base), "owner", "owner-secret", "Дом") }; assertFalse(snapshot.toString().contains("owner-secret")); assertTrue(snapshot.toString().contains("REDACTED"))
        }
    }
    @Test fun openwrtUsesLoginFixedReadCallsAndDestroysItsSession() {
        val token = "abcdef0123456789abcdef0123456789"
        Server { r ->
            assertEquals("POST", r.method); assertEquals("/ubus", r.path); assertNull(r.headers["authorization"])
            val params = JSONObject(r.body).getJSONArray("params"); val obj = params.getString(1); val method = params.getString(2)
            if (method != "login") assertEquals(token, params.getString(0))
            val data = when ("$obj.$method") {
                "session.login" -> { assertEquals("owner-secret", params.getJSONObject(3).getString("password")); assertEquals(60, params.getJSONObject(3).getInt("timeout")); JSONObject().put("ubus_rpc_session", token) }
                "system.board" -> JSONObject("""{"hostname":"openwrt","model":"AP","system":"Qualcomm","release":{"distribution":"OpenWrt","version":"24.10"},"password":"RAW-SECRET"}""")
                "system.info" -> JSONObject().put("uptime", 50)
                "network.interface.dump" -> JSONObject("""{"interface":[{"interface":"lan","proto":"static","up":true,"ipv4-address":[{"address":"192.168.1.1"}]}]}""")
                "dhcp.ipv4leases" -> JSONObject("""{"device":{"br-lan":{"leases":[{"hostname":"phone","ipaddr":"192.168.1.2","mac":"AA:BB:CC:DD:EE:FF"}]}}}""")
                "session.destroy" -> { assertEquals(token, params.getJSONObject(3).getString("ubus_rpc_session")); JSONObject() }
                else -> error("Unexpected RPC $obj.$method")
            }
            200 to JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONArray().put(0).put(data)).toString()
        }.use { s ->
            val snapshot = RouterApiClient(null).use { it.read(config(s.base, "openwrt"), "owner", "owner-secret", "Дом") }
            RouterStore.validateSnapshot(snapshot); assertEquals(6, s.requests.size); assertTrue(s.failures.toString(), s.failures.isEmpty()); assertEquals(4, snapshot.getJSONArray("rows").length())
            for (secret in listOf(token, "owner-secret", "RAW-SECRET")) assertFalse(snapshot.toString().contains(secret))
        }
    }
    @Test fun cancelPreventsAnyRequest() {
        Server(::standard).use { s -> val c = RouterApiClient(null); c.close(); val e = assertThrows(RouterApiClient.Failure::class.java) { c.read(config(s.base), "owner", "secret", "Дом") }; assertTrue(e.message!!.contains("отменён")); assertTrue(s.requests.isEmpty()) }
    }
    @Test fun httpRequiresExplicitOptInAndLocalLiteralAddress() {
        assertEquals("https://router.example", RouterStore.address("https://router.example/", false).toASCIIString())
        assertEquals("http://192.168.1.1", RouterStore.address("http://192.168.1.1", true).toASCIIString())
        for ((url, allow) in listOf("http://192.168.1.1" to false, "http://router.example" to true, "http://1.1.1.1" to true, "http://0.0.0.0" to true, "https://u:p@router.example" to false, "https://router.example/path" to false, "https://router.example/?token=x" to false, "https://router.example:0" to false)) assertThrows(Exception::class.java) { RouterStore.address(url, allow) }
    }
}
