package pro.sitescope.app.proto

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Authenticated TLS for protocol clients, including STARTTLS and FTP data sockets. */
object TlsSockets {
    fun connect(host: String, port: Int, timeoutMs: Int = 15000): SSLSocket {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), 10000)
            return upgrade(plain, host, port, timeoutMs)
        } catch (e: Exception) {
            plain.close()
            throw e
        }
    }

    fun upgrade(plain: Socket, host: String, port: Int, timeoutMs: Int = 15000): SSLSocket {
        var tls: SSLSocket? = null
        try {
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            tls.soTimeout = timeoutMs
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            return tls
        } catch (e: Exception) {
            try { (tls ?: plain).close() } catch (_: Exception) { }
            throw e
        }
    }
}
