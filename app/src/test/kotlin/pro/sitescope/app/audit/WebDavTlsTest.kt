package pro.sitescope.app.audit

import android.app.Application
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.sitescope.app.proto.WebDavFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebDavTlsTest {
    @Test fun rejectsTrustedCertificateForDifferentHostnameBeforeSendingCredentials() = exchange("127.0.0.1", false)
    @Test fun acceptsTrustedCertificateForMatchingHostname() = exchange("localhost", true)

    @Test fun scannerRecordsExactDerCertificateFingerprintWithoutClaimingTrust() {
        val keys = KeyStore.getInstance("PKCS12").apply { keyFile.inputStream().use { load(it, password) } }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val ctx = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val pool = Executors.newSingleThreadExecutor()
        val received = pool.submit {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                val input = socket.inputStream.bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.outputStream.write("HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()); socket.outputStream.flush()
            }
        }
        try {
            val method = pro.sitescope.app.ScannerEngine::class.java.getDeclaredMethod("probeTls", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val result = method.invoke(null, "127.0.0.1", server.localPort, 3000)
            assertNotNull(result)
            val fingerprint = result.javaClass.getDeclaredField("tlsFingerprint").apply { isAccessible = true }.get(result)
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(keys.getCertificate("test").encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, fingerprint)
            assertEquals("not_checked", result.javaClass.getDeclaredField("tlsTrust").apply { isAccessible = true }.get(result))
            received.get(8, TimeUnit.SECONDS)
        } finally { server.close(); pool.shutdownNow() }
    }

    private fun exchange(host: String, expectedAccepted: Boolean) {
        val keys = KeyStore.getInstance("PKCS12").apply { keyFile.inputStream().use { load(it, password) } }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
        val previous = SSLContext.getDefault()
        val server = serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val authorized = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "tls-test").apply { isDaemon = true } }
        val received = pool.submit {
            try { (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                val input = socket.inputStream.bufferedReader()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Authorization:")) authorized.set(true)
                }
                socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                socket.outputStream.flush()
            } } catch (_: SSLException) { /* Client rejection is the expected negative scenario. */ }
        }
        try {
            SSLContext.setDefault(clientContext)
            var accepted = true
            val output = ByteArrayOutputStream()
            try { WebDavFs(host, server.localPort, true, "test-user", "test-password").download("/", output) }
            catch (_: SSLException) { accepted = false }
            received.get(10, TimeUnit.SECONDS)
            assertEquals("Certificate identity verification", expectedAccepted, accepted)
            assertEquals("Credentials only after server identity is verified", expectedAccepted, authorized.get())
            assertEquals(if (expectedAccepted) "ok" else "", output.toString("UTF-8"))
        } finally {
            SSLContext.setDefault(previous); server.close(); pool.shutdownNow()
        }
    }

    companion object {
        private val password = "test-password".toCharArray()
        private lateinit var keyFile: File
        @JvmStatic @BeforeClass fun createCertificate() {
            val dir = Files.createTempDirectory("sitescope-tls-test").toFile().apply { deleteOnExit() }
            keyFile = File(dir, "test.p12").apply { deleteOnExit() }
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path,
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", keyFile.path, "-storepass", String(password), "-keypass", String(password),
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2").redirectErrorStream(true).start()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "keytool timed out" }
            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
        }
    }
}
