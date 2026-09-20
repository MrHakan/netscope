package com.netscope.core.network

import com.netscope.core.model.Confidence
import com.netscope.core.model.DiscoveredService
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.MacAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** One SSDP response, parsed defensively. */
data class SsdpRecord(
    val address: InetAddress,
    val server: String?,
    val location: String?,
    val searchTarget: String?,
    val usn: String?,
    val deviceType: String?,
    val macAddress: MacAddress?,
)

/**
 * SSDP / UPnP discovery over multicast.
 *
 * Everything arriving here was written by an unauthenticated device on the LAN, so the
 * parser is strict: bounded datagram size, a header count cap, a per-line length cap,
 * no XML fetching, and no rendering of remote content anywhere.
 */
@Singleton
class SsdpDiscovery @Inject constructor(
    private val multicastLockHolder: MulticastLockHolder,
) {

    /**
     * Sends M-SEARCH and collects responses for [timeoutMillis].
     *
     * The search targets are the generic ones; asking for specific device types as well
     * would multiply traffic for little gain, since most devices answer ssdp:all.
     */
    suspend fun discover(
        timeoutMillis: Long,
        onRecord: suspend (SsdpRecord) -> Unit,
    ) = withContext(Dispatchers.IO) {
        multicastLockHolder.acquire()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = SOCKET_POLL_MILLIS
                broadcast = true
            }
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val request = buildSearchRequest().toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(group, SSDP_PORT)))

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            val seen = mutableSetOf<String>()

            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val record = parseResponse(packet) ?: continue
                // A device answers each search target separately; report it once.
                val key = record.usn ?: record.address.hostAddress ?: continue
                if (!seen.add(key)) continue
                onRecord(record)
            }
        } catch (e: Exception) {
            // Discovery is best-effort: a blocked socket must never fail the whole scan.
        } finally {
            runCatching { socket?.close() }
            multicastLockHolder.release()
        }
    }

    private fun buildSearchRequest(): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $MX_SECONDS\r\n")
        append("ST: ssdp:all\r\n")
        append("\r\n")
    }

    /**
     * Parses an SSDP response into headers.
     *
     * Anything oversized, malformed or non-UTF-8 is discarded rather than repaired: a
     * device that cannot speak the protocol correctly gets no entry.
     */
    private fun parseResponse(packet: DatagramPacket): SsdpRecord? {
        val text = runCatching {
            String(packet.data, packet.offset, packet.length.coerceAtMost(MAX_DATAGRAM_BYTES), Charsets.UTF_8)
        }.getOrNull() ?: return null

        val headers = mutableMapOf<String, String>()
        var lineCount = 0
        for (line in text.lineSequence()) {
            if (++lineCount > MAX_HEADERS) break
            if (line.length > MAX_HEADER_LENGTH) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().uppercase()
            val value = sanitize(line.substring(colon + 1).trim()) ?: continue
            headers.putIfAbsent(name, value)
        }
        if (headers.isEmpty()) return null

        val searchTarget = headers["ST"] ?: headers["NT"]
        val usn = headers["USN"]
        return SsdpRecord(
            address = packet.address ?: return null,
            server = headers["SERVER"],
            location = headers["LOCATION"]?.takeIf { it.startsWith("http://", ignoreCase = true) },
            searchTarget = searchTarget,
            usn = usn,
            deviceType = searchTarget?.takeIf { it.startsWith("urn:") },
            // Some devices volunteer their MAC in the USN or a vendor header. That is a
            // legitimate source; it is never synthesised when absent.
            macAddress = extractMac(usn) ?: extractMac(headers["WAKEUP"]),
        )
    }

    /** Pulls a MAC out of a header only when one is genuinely present. */
    private fun extractMac(value: String?): MacAddress? {
        if (value == null) return null
        val match = MAC_PATTERN.find(value) ?: return null
        return MacAddress.parse(match.value)
    }

    private fun sanitize(raw: String): String? = raw
        .filter { it == ' ' || !it.isISOControl() }
        .trim()
        .take(MAX_HEADER_LENGTH)
        .ifEmpty { null }

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MX_SECONDS = 2
        private const val SOCKET_POLL_MILLIS = 400
        private const val MAX_DATAGRAM_BYTES = 8 * 1024
        private const val MAX_HEADERS = 40
        private const val MAX_HEADER_LENGTH = 256

        private val MAC_PATTERN = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

        fun toService(record: SsdpRecord, observedAtEpochMillis: Long) = DiscoveredService(
            port = null,
            protocol = "upnp",
            name = record.server ?: record.searchTarget ?: "UPnP device",
            serviceType = record.deviceType ?: record.searchTarget,
            detail = "Answered an SSDP M-SEARCH." + (record.server?.let { " Server: $it." } ?: ""),
            source = EvidenceSource.SSDP,
            confidence = Confidence.HIGH,
            observedAtEpochMillis = observedAtEpochMillis,
        )
    }
}
