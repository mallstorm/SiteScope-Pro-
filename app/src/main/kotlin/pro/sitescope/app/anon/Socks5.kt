package pro.sitescope.app.anon

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Минимальный клиент SOCKS5 (без авторизации). Имя узла уходит прокси как есть — это нужно для .onion. */
object Socks5 {
    class Failure(message: String, val code: Int = -1) : IOException(message)

    fun connect(proxyHost: String, proxyPort: Int, targetHost: String, targetPort: Int, timeoutMs: Int = 30_000): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(proxyHost, proxyPort), minOf(timeoutMs, 10_000))
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val hello = ByteArray(2); input.readFully(hello)
            if (hello[0].toInt() != 5) throw Failure("Это не SOCKS5-прокси")
            if (hello[1].toInt() != 0) throw Failure("SOCKS5 требует авторизацию")
            val name = targetHost.toByteArray(Charsets.US_ASCII)
            if (name.isEmpty() || name.size > 255) throw Failure("Некорректное имя узла")
            val request = ByteArray(7 + name.size)
            request[0] = 5; request[1] = 1; request[2] = 0; request[3] = 3
            request[4] = name.size.toByte()
            System.arraycopy(name, 0, request, 5, name.size)
            request[5 + name.size] = (targetPort shr 8).toByte()
            request[6 + name.size] = targetPort.toByte()
            out.write(request); out.flush()
            val head = ByteArray(4); input.readFully(head)
            val code = head[1].toInt() and 0xFF
            if (code != 0) throw Failure(describe(code), code)
            val skip = when (head[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> throw Failure("Некорректный ответ SOCKS5")
            }
            input.readFully(ByteArray(skip + 2))
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw if (e is Failure) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    fun describe(code: Int): String = when (code) {
        1 -> "Общий сбой SOCKS-сервера"
        2 -> "Запрещено правилами прокси"
        3 -> "Сеть недоступна"
        4 -> "Узел недоступен"
        5 -> "Соединение отклонено"
        6 -> "Истекло время (TTL)"
        7 -> "Команда не поддерживается"
        8 -> "Тип адреса не поддерживается"
        0xF0 -> "Onion-сервис не найден (дескриптор недоступен)"
        0xF1 -> "Onion-сервис: не удалось получить дескриптор"
        0xF2 -> "Onion-сервис: неверный адрес"
        0xF3 -> "Onion-сервис: время ожидания истекло"
        0xF4 -> "Onion-сервис требует авторизации клиента"
        0xF5 -> "Onion-сервис: неверная авторизация"
        0xF6 -> "Onion-сервис: не удалось построить цепочку до точки встречи"
        0xF7 -> "Onion-сервис: неверный запрос"
        else -> "Ошибка SOCKS5 ($code)"
    }
}
