package com.netscope.core.network

import com.netscope.core.model.PortResult
import com.netscope.core.model.PortState
import com.netscope.core.model.ProbeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** A single liveness answer, always carrying the probe that produced it. */
data class ProbeOutcome(
    val responded: Boolean,
    val latencyMillis: Double?,
    val probeType: ProbeType,
    val detail: String,
)

/**
 * Decides whether a host is there, using ICMP when the kernel allows it and TCP
 * otherwise.
 *
 * A refused TCP connection counts as a response: the host sent a RST, which proves it
 * exists just as well as an accepted connection does.
 */
@Singleton
class HostProber @Inject constructor(private val icmpProbe: IcmpProbe) {

    /** Ports tried when looking for signs of life. Deliberately small and common. */
    private val livenessPorts = listOf(80, 443, 22, 445, 8080)

    val icmpAvailable: Boolean get() = icmpProbe.isAvailable()

    /**
     * Probes [address] once.
     *
     * ICMP is tried first because it is the only probe that produces a true round-trip
     * time. Failure there never ends the story: hosts commonly drop ICMP while happily
     * answering TCP.
     */
    suspend fun probe(
        address: InetAddress,
        timeoutMillis: Long,
        sequence: Int,
        tcpPorts: List<Int> = livenessPorts,
    ): ProbeOutcome {
        if (icmpProbe.isAvailable()) {
            val result = icmpProbe.echo(address, timeoutMillis, sequence)
            if (result.rttMillis != null) {
                return ProbeOutcome(
                    responded = true,
                    latencyMillis = result.rttMillis,
                    probeType = ProbeType.ICMP,
                    detail = "ICMP echo reply in %.1f ms.".format(result.rttMillis),
                )
            }
        }

        // Split the remaining budget so a host that ignores every port still finishes
        // inside the caller's timeout.
        val perPortTimeout = (timeoutMillis / tcpPorts.size.coerceAtLeast(1)).coerceAtLeast(120)
        for (port in tcpPorts) {
            val tcp = tcpConnect(address, port, perPortTimeout)
            if (tcp.responded) return tcp
        }

        return ProbeOutcome(
            responded = false,
            latencyMillis = null,
            probeType = if (icmpProbe.isAvailable()) ProbeType.ICMP else ProbeType.TCP_CONNECT,
            detail = "No reply to ICMP or TCP connect attempts on " +
                tcpPorts.joinToString(", ") + ".",
        )
    }

    /**
     * A single TCP connect, measured.
     *
     * This is the right tool when ICMP is filtered, and it is always labelled as a TCP
     * connect rather than as a ping.
     */
    suspend fun tcpConnect(address: InetAddress, port: Int, timeoutMillis: Long): ProbeOutcome =
        withContext(Dispatchers.IO) {
            val startNanos = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, port), timeoutMillis.toInt())
                    val elapsed = (System.nanoTime() - startNanos) / 1_000_000.0
                    ProbeOutcome(
                        responded = true,
                        latencyMillis = elapsed,
                        probeType = ProbeType.TCP_CONNECT,
                        detail = "TCP connect to port $port succeeded in %.1f ms.".format(elapsed),
                    )
                }
            } catch (e: ConnectException) {
                // RST means something is listening on that address and answered.
                val elapsed = (System.nanoTime() - startNanos) / 1_000_000.0
                ProbeOutcome(
                    responded = true,
                    latencyMillis = elapsed,
                    probeType = ProbeType.TCP_CONNECT,
                    detail = "TCP connect to port $port was refused in %.1f ms, which proves the " +
                        "host is present.".format(elapsed),
                )
            } catch (e: SocketTimeoutException) {
                ProbeOutcome(false, null, ProbeType.TCP_CONNECT, "TCP connect to port $port timed out.")
            } catch (e: IOException) {
                ProbeOutcome(
                    false, null, ProbeType.TCP_CONNECT,
                    "TCP connect to port $port failed: ${e.message ?: "unknown error"}.",
                )
            }
        }

    /**
     * Classifies a single port.
     *
     * A timeout is reported as FILTERED_OR_TIMEOUT rather than "closed", because the
     * two are genuinely indistinguishable from here.
     */
    suspend fun scanPort(address: InetAddress, port: Int, timeoutMillis: Long): PortResult =
        withContext(Dispatchers.IO) {
            val startNanos = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, port), timeoutMillis.toInt())
                    val elapsed = (System.nanoTime() - startNanos) / 1_000_000
                    PortResult(port, PortState.OPEN, elapsed, banner = null)
                }
            } catch (e: ConnectException) {
                PortResult(port, PortState.CLOSED, null, null)
            } catch (e: IOException) {
                PortResult(port, PortState.FILTERED_OR_TIMEOUT, null, null)
            }
        }

    /**
     * Reads a short, size-capped banner from an open port.
     *
     * Remote data is hostile input: the read is bounded, the socket has a hard timeout,
     * and the result is stripped of control characters before it can reach the UI or
     * the log. Nothing returned here is ever executed or rendered as markup.
     */
    suspend fun grabBanner(address: InetAddress, port: Int, timeoutMillis: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), timeoutMillis.toInt())
                    socket.soTimeout = timeoutMillis.toInt()
                    if (port == 80 || port == 8080) {
                        // A minimal, read-only request. No credentials, no side effects.
                        socket.getOutputStream().write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
                        socket.getOutputStream().flush()
                    }
                    val buffer = ByteArray(MAX_BANNER_BYTES)
                    val read = socket.getInputStream().read(buffer)
                    if (read <= 0) return@withContext null
                    sanitize(String(buffer, 0, read, Charsets.ISO_8859_1))
                }
            } catch (e: IOException) {
                null
            }
        }

    /** Strips control characters and caps the length before display or logging. */
    private fun sanitize(raw: String): String? {
        val cleaned = raw.asSequence()
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .joinToString("")
            .trim()
            .take(MAX_BANNER_CHARS)
        return cleaned.ifEmpty { null }
    }

    companion object {
        private const val MAX_BANNER_BYTES = 512
        private const val MAX_BANNER_CHARS = 300
    }
}
