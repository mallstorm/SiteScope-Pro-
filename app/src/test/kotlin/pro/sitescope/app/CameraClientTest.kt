package pro.sitescope.app

import android.app.Application
import java.net.ServerSocket
import java.net.InetAddress
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class CameraClientTest {
    private val d = CameraClient.DEVICE
    private val m = CameraClient.MEDIA1
    private val tt = CameraClient.SCHEMA
    private fun envelope(body: String) = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:d="$d" xmlns:m="$m" xmlns:tt="$tt"><s:Body>$body</s:Body></s:Envelope>"""
    private class Headers : LinkedHashMap<String, String>() { fun add(k: String, v: String) { put(k, v) } }
    private class Exchange(val localAddress: InetSocketAddress, val headers: Map<String, String>) { val responseHeaders = Headers() }
    private class Server(val handler: (Exchange, String, String) -> Pair<Int, String>) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        @Volatile private var stopped = false
        init {
            pool.submit {
                while (!stopped) try {
                    val connection = socket.accept()
                    pool.submit {
                        connection.use { c -> try {
                            c.soTimeout = 5000
                            val input = c.getInputStream(); val head = ByteArrayOutputStream()
                            while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) { val n = input.read(); if (n < 0) return@use; head.write(n); check(head.size() < 16384) }
                            val headers = head.toString("ISO-8859-1").split("\r\n").drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                            val bytes = ByteArray(headers["content-length"]!!.toInt()); var read = 0
                            while (read < bytes.size) { val n = input.read(bytes, read, bytes.size - read); if (n < 0) return@use; read += n }
                            val body = String(bytes, Charsets.UTF_8); requests.add(body)
                            val action = headers["content-type"]!!.substringAfter("action=\"").substringBefore('"').substringAfterLast('/')
                            val exchange = Exchange(InetSocketAddress("127.0.0.1", socket.localPort), headers)
                            val (status, response) = handler(exchange, action, body); val data = response.toByteArray()
                            val extra = exchange.responseHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                            val output = c.getOutputStream(); output.write("HTTP/1.1 $status Test\r\nContent-Length: ${data.size}\r\nConnection: close\r\n$extra\r\n".toByteArray()); output.write(data); output.flush()
                        } catch (_: Exception) { } }
                    }
                } catch (_: Exception) { break }
            }
        }
        val base get() = "http://127.0.0.1:${socket.localPort}/device"
        override fun close() { stopped = true; socket.close(); pool.shutdownNow() }
    }
    private fun standard(e: Exchange, action: String, media2: Boolean = false): Pair<Int, String> {
        val media = "http://127.0.0.1:${e.localAddress.port}/media"
        val body = when (action) {
            "GetDeviceInformation" -> "<d:GetDeviceInformationResponse><d:Manufacturer>ASUS</d:Manufacturer><d:Model>CAM-1</d:Model></d:GetDeviceInformationResponse>"
            "GetCapabilities" -> if (media2) "<d:GetCapabilitiesResponse><d:Capabilities/></d:GetCapabilitiesResponse>" else "<d:GetCapabilitiesResponse><d:Capabilities><tt:Media XAddr=\"$media\"/></d:Capabilities></d:GetCapabilitiesResponse>"
            "GetServices" -> "<d:GetServicesResponse><d:Service><d:Namespace>${CameraClient.MEDIA2}</d:Namespace><d:XAddr>$media</d:XAddr></d:Service></d:GetServicesResponse>"
            "GetProfiles" -> if (media2) "<v:GetProfilesResponse xmlns:v=\"${CameraClient.MEDIA2}\"><v:Profiles token=\"main\"><v:Name>Main</v:Name></v:Profiles></v:GetProfilesResponse>" else "<m:GetProfilesResponse><m:Profiles token=\"main\"><tt:Name>Main</tt:Name></m:Profiles></m:GetProfilesResponse>"
            "GetStreamUri" -> if (media2) "<v:GetStreamUriResponse xmlns:v=\"${CameraClient.MEDIA2}\"><v:Uri>rtsp://127.0.0.1:8554/live</v:Uri></v:GetStreamUriResponse>" else "<m:GetStreamUriResponse><m:MediaUri><tt:Uri>rtsp://127.0.0.1:8554/live</tt:Uri></m:MediaUri></m:GetStreamUriResponse>"
            else -> error("Unexpected action")
        }
        return 200 to envelope(body)
    }
    private fun fail(code: CameraClient.Code, action: () -> Unit) {
        val e = assertThrows(CameraClient.Failure::class.java, action)
        assertEquals(code, e.code)
    }
    @Test fun mediaOnePipelineRetainsIdentityAndDoesNotConfuseUriWithPlaybackSuccess() {
        Server { e, a, _ -> standard(e, a) }.use { s ->
            CameraClient(null).use { c ->
                val phases = mutableListOf<String>()
                val device = c.inspect(s.base, "", "", { phases.add(it) })
                assertEquals("CAM-1", device.identity.model); assertEquals("ASUS", device.info["Производитель"])
                assertEquals(CameraClient.MEDIA1, device.mediaNamespace); assertEquals("main", device.profiles.single().token)
                assertEquals("rtsp://127.0.0.1:8554/live", c.stream(device, device.profiles.single(), "", "", null))
                assertEquals(4, s.requests.size); assertEquals(3, phases.size)
            }
        }
    }
    @Test fun mediaTwoFallbackUsesItsOwnNamespaceAndRtspProtocol() {
        Server { e, a, _ -> standard(e, a, true) }.use { s ->
            CameraClient(null).use { c ->
                val device = c.inspect(s.base, "", "", null)
                assertEquals(CameraClient.MEDIA2, device.mediaNamespace)
                assertTrue(c.stream(device, device.profiles.single(), "", "", null).endsWith("/live"))
                assertTrue(s.requests.last().contains("<d:Protocol>RTSP</d:Protocol>")); assertFalse(s.requests.last().contains("StreamSetup"))
            }
        }
    }
    @Test fun wsSecurityUsesFreshNonceAndCorrectPasswordDigestWithoutPlainPassword() {
        Server { e, a, _ -> standard(e, a) }.use { s ->
            CameraClient(null).use { it.inspect(s.base, "owner<&", "test-camera-secret", null) }
            val nonces = mutableSetOf<String>()
            s.requests.forEach { xml ->
                fun value(tag: String) = Regex("<$tag(?: [^>]*)?>([^<]*)</$tag>").find(xml)!!.groupValues[1]
                val nonce = value("wsse:Nonce"); val created = value("wsu:Created")
                val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(Base64.getDecoder().decode(nonce) + created.toByteArray() + "test-camera-secret".toByteArray()))
                assertEquals(expected, value("wsse:Password")); assertFalse(xml.contains("test-camera-secret")); assertTrue(xml.contains("owner&lt;&amp;")); nonces.add(nonce)
            }
            assertEquals(3, nonces.size)
        }
    }
    @Test fun httpAndSoapAuthorizationFailuresAreDistinctAndNeverEchoBodies() {
        for (status in listOf(200, 500, 401, 403)) Server { _, _, _ -> status to envelope("<s:Fault><s:Code><s:Subcode><s:Value>ter:NotAuthorized</s:Value></s:Subcode></s:Code><s:Reason>do-not-display-secret</s:Reason></s:Fault>") }.use { s ->
            val error = assertThrows(CameraClient.Failure::class.java) { CameraClient(null).use { it.inspect(s.base, "u", "p", null) } }
            assertEquals(CameraClient.Code.AUTH, error.code); assertFalse(error.message!!.contains("do-not-display-secret")); assertEquals(1, s.requests.size)
        }
    }
    @Test fun rejectsRedirectsAndForeignMediaBeforeSendingCredentialsToAdvertisedTarget() {
        Server { e, _, _ -> e.responseHeaders.add("Location", "http://localhost:${e.localAddress.port}/stolen"); 302 to "redirect" }.use { s ->
            fail(CameraClient.Code.ADDRESS) { CameraClient(null).use { it.inspect(s.base, "u", "p", null) } }; assertEquals(1, s.requests.size)
        }
        Server { e, a, _ -> if (a == "GetCapabilities") 200 to envelope("<d:GetCapabilitiesResponse><d:Capabilities><tt:Media><tt:XAddr>http://localhost:${e.localAddress.port}/foreign</tt:XAddr></tt:Media></d:Capabilities></d:GetCapabilitiesResponse>") else standard(e, a) }.use { s ->
            fail(CameraClient.Code.ADDRESS) { CameraClient(null).use { it.inspect(s.base, "u", "p", null) } }; assertEquals(2, s.requests.size)
        }
    }
    @Test fun digestChallengeAuthenticatesOwnerWithMd5AndSha256() {
        for (algorithm in listOf("MD5", "SHA-256")) Server { e, action, _ ->
            val header = e.headers["authorization"]
            if (header == null) {
                e.responseHeaders.add("WWW-Authenticate", "Digest realm=\"Camera\", nonce=\"test-nonce\", qop=\"auth\", algorithm=$algorithm")
                401 to ""
            } else {
                fun value(key: String) = Regex("$key=\"([^\"]*)\"").find(header)!!.groupValues[1]
                fun hash(text: String) = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it.toInt() and 255) }
                val uri = if (action == "GetProfiles") "/media" else "/device"
                val expected = hash(hash("owner:Camera:test-password") + ":test-nonce:00000001:" + value("cnonce") + ":auth:" + hash("POST:$uri"))
                assertEquals(uri, value("uri")); assertEquals(expected, value("response")); assertEquals("owner", value("username"))
                standard(e, action)
            }
        }.use { s ->
            CameraClient(null).use { assertEquals(1, it.inspect(s.base, "owner", "test-password", null).profiles.size) }
            assertEquals(6, s.requests.size)
        }
    }
    @Test fun failedDigestAuthenticationStopsAfterOneChallengeRetry() {
        Server { e, _, _ -> e.responseHeaders.add("WWW-Authenticate", "Digest realm=\"Camera\", nonce=\"nonce\", qop=\"auth\""); 401 to "" }.use { s ->
            fail(CameraClient.Code.AUTH) { CameraClient(null).use { it.inspect(s.base, "owner", "wrong", null) } }
            assertEquals(2, s.requests.size)
        }
    }

    @Test fun malformedXmlWrongNamespacesEntitiesAndDuplicateTokensAreRejected() {
        for (xml in listOf("<html>Not ONVIF</html>", envelope("<x:GetDeviceInformationResponse xmlns:x=\"urn:wrong\"/>"), "<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///not-read'>]>" + envelope("<d:GetDeviceInformationResponse/>"))) {
            Server { _, _, _ -> 200 to xml }.use { s -> fail(CameraClient.Code.INVALID) { CameraClient(null).use { it.inspect(s.base, "", "", null) } } }
        }
        Server { e, a, _ -> if (a == "GetProfiles") 200 to envelope("<m:GetProfilesResponse><m:Profiles token=\"same\"/><m:Profiles token=\"same\"/></m:GetProfilesResponse>") else standard(e, a) }.use { s -> fail(CameraClient.Code.INVALID) { CameraClient(null).use { it.inspect(s.base, "", "", null) } } }
    }
    @Test fun emptyProfilesAndUnsupportedDeviceInfoRemainHonestResults() {
        Server { e, a, _ -> when (a) { "GetDeviceInformation" -> 404 to ""; "GetProfiles" -> 200 to envelope("<m:GetProfilesResponse/>"); else -> standard(e, a) } }.use { s ->
            CameraClient(null).use { c -> val device = c.inspect(s.base, "", "", null); assertTrue(device.profiles.isEmpty()); assertFalse(device.identity.isKnown) }
        }
    }
    @Test fun responseSizeAndReadTimeoutAreBounded() {
        Server { _, _, _ -> 200 to "x".repeat(524289) }.use { s -> fail(CameraClient.Code.INVALID) { CameraClient(null).use { it.inspect(s.base, "", "", null) } } }
        Server { e, a, _ -> Thread.sleep(1000); standard(e, a) }.use { s -> fail(CameraClient.Code.TIMEOUT) { CameraClient(null, 100).use { it.inspect(s.base, "", "", null) } } }
    }
    @Test fun cancellationRejectsSubsequentRequestsAndInterruptsPendingResponse() {
        val entered = CountDownLatch(1)
        Server { e, a, _ -> entered.countDown(); Thread.sleep(1500); standard(e, a) }.use { s ->
            val client = CameraClient(null, 300); val pool = Executors.newSingleThreadExecutor()
            try {
                val task = pool.submit<CameraClient.Code> { try { client.inspect(s.base, "", "", null); error("Unexpected success") } catch (e: CameraClient.Failure) { e.code } }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                Thread({ client.close() }).apply { isDaemon = true }.start()
                assertEquals(CameraClient.Code.CANCELLED, task.get(3, TimeUnit.SECONDS))
                fail(CameraClient.Code.CANCELLED) { client.inspect(s.base, "", "", null) }
            } finally { pool.shutdownNow(); client.close() }
        }
    }
    @Test fun streamCannotSilentlyMoveToAnotherAndroidNetwork() {
        Server { e, a, _ -> standard(e, a) }.use { s ->
            val device = CameraClient(null).use { it.inspect(s.base, "", "", null) }
            val network = org.robolectric.shadow.api.Shadow.newInstanceOf(android.net.Network::class.java)
            fail(CameraClient.Code.NETWORK_CHANGED) { CameraClient(network).use { it.stream(device, device.profiles.single(), "u", "p", null) } }
            assertEquals(3, s.requests.size)
        }
    }

    @Test fun selectedHostDowngradeWildcardIpv6AndCredentialRules() {
        fail(CameraClient.Code.ADDRESS) { CameraClient.related("https://camera.test/device", "http://camera.test/media", false) }
        fail(CameraClient.Code.ADDRESS) { CameraClient.related("http://camera.test/device", "http://u:p@camera.test/media", false) }
        fail(CameraClient.Code.ADDRESS) { CameraClient.deviceAddress("camera.test", "http://other.test/device") }
        assertEquals("rtsp://camera.test:8554/live", CameraClient.related("http://camera.test/device", "rtsp://user:old@0.0.0.0:8554/live", true))
        assertEquals("https://camera.test:443/onvif/device_service", CameraClient.defaultUrl("camera.test", 443, ""))
        assertEquals("http://[fe80::1]:80/onvif/device_service", CameraClient.defaultUrl("fe80::1", 3702, ""))
        assertEquals("rtsp://[::1]/live", CameraClient.related("http://[::1]/device", "rtsp://[::]/live", true))
        fail(CameraClient.Code.UNSUPPORTED) { CameraClient.related("http://camera.test/device", "http://camera.test/video", true) }
    }
}
