package com.netscope.core.network

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.netscope.core.model.ProbeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real ICMP echo over an unprivileged datagram socket.
 *
 * Linux allows AF_INET/SOCK_DGRAM/IPPROTO_ICMP without root when the calling process's
 * GID falls inside the kernel's `ping_group_range`. Android normally grants this to
 * apps, but it is a kernel setting and can be closed, so availability is probed at
 * runtime and every caller has a TCP fallback.
 *
 * This deliberately does not use InetAddress.isReachable(), which is not a ping: it
 * commonly degrades to a TCP connect to port 7 and reports misleading latency.
 */
@Singleton
class IcmpProbe @Inject constructor() {

    /** One echo result. [rttMillis] is null when nothing came back in time. */
    data class Result(
        val rttMillis: Double?,
        val responder: InetAddress?,
        val ttlExceeded: Boolean = false,
        val errorMessage: String? = null,
    )

    /**
     * Whether an unprivileged ICMP socket can be created at all.
     *
     * Cached after the first probe: the answer cannot change while the process lives.
     */
    @Volatile
    private var availability: Boolean? = null

    fun isAvailable(): Boolean = availability ?: probeAvailability().also { availability = it }

    private fun probeAvailability(): Boolean = try {
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        Os.close(fd)
        true
    } catch (e: ErrnoException) {
        // EACCES/EPERM mean ping_group_range excludes this process. Anything else is
        // equally fatal for our purposes; either way we fall back to TCP.
        false
    } catch (e: Exception) {
        false
    }

    /**
     * Sends a single echo request and waits for the matching reply.
     *
     * [ttl], when set, limits the hop count so callers can measure hop distance.
     */
    suspend fun echo(
        destination: InetAddress,
        timeoutMillis: Long,
        sequence: Int,
        payloadSize: Int = 32,
        ttl: Int? = null,
    ): Result = withContext(Dispatchers.IO) {
        val isV6 = destination is Inet6Address
        var fd: FileDescriptor? = null
        try {
            fd = Os.socket(
                if (isV6) OsConstants.AF_INET6 else OsConstants.AF_INET,
                OsConstants.SOCK_DGRAM,
                if (isV6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP,
            )
            if (ttl != null) {
                if (isV6) {
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_UNICAST_HOPS, ttl)
                } else {
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, ttl)
                }
            }

            val identifier = sequence and 0xFFFF
            val request = buildEchoRequest(isV6, identifier, sequence, payloadSize)

            val sentAtNanos = System.nanoTime()
            Os.sendto(fd, request, 0, request.size, 0, destination, 0)

            val deadlineNanos = sentAtNanos + timeoutMillis * 1_000_000
            val buffer = ByteArray(1500)

            // Loop because the socket can receive replies to other sequences.
            while (true) {
                val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000
                if (remainingMillis <= 0) return@withContext Result(null, null)

                val pollfd = StructPollfd().apply {
                    this.fd = fd
                    this.events = OsConstants.POLLIN.toShort()
                }
                val ready = Os.poll(arrayOf(pollfd), remainingMillis.toInt())
                if (ready == 0) return@withContext Result(null, null)

                val from = InetSocketAddress(0)
                val read = Os.recvfrom(fd, buffer, 0, buffer.size, 0, from)
                val elapsedMillis = (System.nanoTime() - sentAtNanos) / 1_000_000.0
                if (read <= 0) continue

                val parsed = parseReply(buffer, read, isV6, sequence)
                when (parsed) {
                    ReplyKind.ECHO_REPLY -> return@withContext Result(elapsedMillis, from.address)
                    ReplyKind.TIME_EXCEEDED ->
                        return@withContext Result(elapsedMillis, from.address, ttlExceeded = true)
                    ReplyKind.OTHER -> continue
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result(null, null)
        } catch (e: ErrnoException) {
            // A TTL-limited probe that expires shows up here rather than as a datagram,
            // because reading the router's address needs the socket error queue, which
            // is not part of the public Android API.
            val hopLimited = e.errno == OsConstants.EHOSTUNREACH || e.errno == OsConstants.ETIMEDOUT
            Result(null, null, ttlExceeded = hopLimited, errorMessage = errnoLabel(e))
        } catch (e: Exception) {
            Result(null, null, errorMessage = e.message)
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    private enum class ReplyKind { ECHO_REPLY, TIME_EXCEEDED, OTHER }

    /**
     * Interprets a datagram received on a ping socket.
     *
     * The kernel strips the IP header for SOCK_DGRAM, so the buffer starts at the ICMP
     * type byte. The kernel also rewrites the identifier to the socket's port, which is
     * why matching is done on the sequence number.
     */
    private fun parseReply(buffer: ByteArray, length: Int, isV6: Boolean, sequence: Int): ReplyKind {
        if (length < 8) return ReplyKind.OTHER
        val type = buffer[0].toInt() and 0xFF
        val echoReplyType = if (isV6) ICMPV6_ECHO_REPLY else ICMP_ECHO_REPLY
        val timeExceededType = if (isV6) ICMPV6_TIME_EXCEEDED else ICMP_TIME_EXCEEDED
        return when (type) {
            echoReplyType -> {
                val replySequence = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
                if (replySequence == (sequence and 0xFFFF)) ReplyKind.ECHO_REPLY else ReplyKind.OTHER
            }
            timeExceededType -> ReplyKind.TIME_EXCEEDED
            else -> ReplyKind.OTHER
        }
    }

    /**
     * Builds an echo request.
     *
     * For IPv4 the checksum is computed here; for IPv6 the kernel must compute it
     * because it covers a pseudo-header the application cannot see.
     */
    private fun buildEchoRequest(isV6: Boolean, identifier: Int, sequence: Int, payloadSize: Int): ByteArray {
        val packet = ByteBuffer.allocate(8 + payloadSize).order(ByteOrder.BIG_ENDIAN)
        packet.put((if (isV6) ICMPV6_ECHO_REQUEST else ICMP_ECHO_REQUEST).toByte())
        packet.put(0)                       // code
        packet.putShort(0)                  // checksum placeholder
        packet.putShort(identifier.toShort())
        packet.putShort(sequence.toShort())
        // A recognisable, constant payload: no timestamps, so nothing leaks.
        for (i in 0 until payloadSize) packet.put(('a' + (i % 23)).code.toByte())
        val bytes = packet.array()
        if (!isV6) {
            val checksum = internetChecksum(bytes)
            bytes[2] = ((checksum ushr 8) and 0xFF).toByte()
            bytes[3] = (checksum and 0xFF).toByte()
        }
        return bytes
    }

    /** RFC 1071 one's-complement checksum. */
    private fun internetChecksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < data.size) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun errnoLabel(e: ErrnoException): String = when (e.errno) {
        OsConstants.EACCES, OsConstants.EPERM ->
            "The kernel refused an unprivileged ICMP socket (ping_group_range)."
        OsConstants.EHOSTUNREACH -> "Host unreachable."
        OsConstants.ETIMEDOUT -> "Timed out."
        else -> e.message ?: "errno ${e.errno}"
    }

    companion object {
        private const val ICMP_ECHO_REQUEST = 8
        private const val ICMP_ECHO_REPLY = 0
        private const val ICMP_TIME_EXCEEDED = 11
        private const val ICMPV6_ECHO_REQUEST = 128
        private const val ICMPV6_ECHO_REPLY = 129
        private const val ICMPV6_TIME_EXCEEDED = 3

        val PROBE_TYPE: ProbeType = ProbeType.ICMP
    }
}
