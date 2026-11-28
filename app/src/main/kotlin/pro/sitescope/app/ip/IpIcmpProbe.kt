package pro.sitescope.app.ip

import android.net.Network
import android.system.Os
import android.system.OsConstants.*
import android.system.StructMsghdr
import android.system.StructPollfd
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linux IP error queue: the returned socket address identifies the ICMP sender. */
internal object IpIcmpProbe {
    private const val IP_RECVERR = 11
    private const val IPV6_RECVERR = 25
    private const val MSG_ERRQUEUE = 0x2000

    @RequiresApi(31)
    fun hop(network: Network, target: InetAddress, ttl: Int): IpTools.Hop {
        val v4 = target is Inet4Address
        val level = if (v4) IPPROTO_IP else IPPROTO_IPV6
        val errorOption = if (v4) IP_RECVERR else IPV6_RECVERR
        val fd = Os.socket(if (v4) AF_INET else AF_INET6, SOCK_DGRAM, if (v4) IPPROTO_ICMP else IPPROTO_ICMPV6)
        try {
            network.bindSocket(fd)
            Os.setsockoptInt(fd, level, errorOption, 1)
            Os.setsockoptInt(fd, level, if (v4) IP_TTL else IPV6_UNICAST_HOPS, ttl)
            Os.connect(fd, target, 0)
            val packet = byteArrayOf(if (v4) 8 else 0x80.toByte(), 0, 0, 0, 0, 0, 0, ttl.toByte())
            val started = System.nanoTime()
            Os.sendto(fd, packet, 0, packet.size, 0, target, 0)
            val poll = StructPollfd().apply {
                this.fd = fd
                events = (POLLIN or POLLERR).toShort()
            }
            if (Os.poll(arrayOf(poll), 1100) > 0) {
                val ms = (System.nanoTime() - started) / 1e6
                val bytes = ByteBuffer.allocateDirect(128)
                val message = StructMsghdr(null, arrayOf(bytes), null, 0)
                val events = poll.revents.toInt()
                if (events and POLLERR != 0) {
                    Os.recvmsg(fd, message, MSG_ERRQUEUE)
                    val reporter = message.msg_control.orEmpty()
                        .firstOrNull { it.cmsg_level == level && it.cmsg_type == errorOption }
                        ?.let { timeExceededSender(it.cmsg_data, v4) }
                    if (reporter != null) return IpTools.Hop(ttl, reporter, ms, false)
                } else if (events and POLLIN != 0) {
                    val size = Os.recvmsg(fd, message, 0)
                    val sender = (message.msg_name as? InetSocketAddress)?.address
                    val type = if (v4) 0 else 129
                    if (size >= 8 && (bytes.get(0).toInt() and 255) == type && (sender == null || sender == target)) {
                        return IpTools.Hop(ttl, target.hostAddress.orEmpty(), ms, true)
                    }
                }
            }
            return IpTools.Hop(ttl, "", 0.0, false)
        } finally { Os.close(fd) }
    }

    internal fun timeExceededSender(data: ByteArray, v4: Boolean): String? {
        val offset = if (v4) 20 else 24
        val length = if (v4) 4 else 16
        if (data.size < offset + length) return null
        val origin = if (v4) 2 else 3
        val type = if (v4) 11 else 3
        if (data[4].toInt() != origin || data[5].toInt() != type || data[6].toInt() != 0) return null
        val family = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).getShort(16).toInt()
        if (family != if (v4) AF_INET else AF_INET6) return null
        return InetAddress.getByAddress(data.copyOfRange(offset, offset + length))
            .takeUnless { it.isAnyLocalAddress }?.hostAddress
    }
}
