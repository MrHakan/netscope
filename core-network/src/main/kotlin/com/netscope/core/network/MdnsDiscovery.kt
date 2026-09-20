package com.netscope.core.network

import com.netscope.core.model.Confidence
import com.netscope.core.model.DiscoveredService
import com.netscope.core.model.EvidenceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.coroutines.coroutineContext

/** What one mDNS advertisement told us about a host. */
data class MdnsRecord(
    val address: InetAddress,
    val hostname: String?,
    val serviceName: String,
    val serviceType: String,
    val port: Int,
    val properties: Map<String, String>,
)

/**
 * mDNS / Bonjour discovery.
 *
 * Android's built-in NsdManager is convenient but historically unreliable for browsing
 * several service types at once, so JmDNS is used instead: it lets us bind explicitly
 * to the interface carrying the network under test, which matters on a device with a
 * VPN or a second interface up.
 */
@Singleton
class MdnsDiscovery @Inject constructor(
    private val multicastLockHolder: MulticastLockHolder,
) {

    /**
     * Service types worth browsing.
     *
     * Browsing "_services._dns-sd._udp" first would be more complete, but many consumer
     * devices answer it poorly; this fixed list is what actually produces results in a
     * few seconds on a normal LAN.
     */
    private val serviceTypes = listOf(
        "_http._tcp.local.",
        "_https._tcp.local.",
        "_ssh._tcp.local.",
        "_sftp-ssh._tcp.local.",
        "_smb._tcp.local.",
        "_afpovertcp._tcp.local.",
        "_nfs._tcp.local.",
        "_ipp._tcp.local.",
        "_ipps._tcp.local.",
        "_printer._tcp.local.",
        "_pdl-datastream._tcp.local.",
        "_scanner._tcp.local.",
        "_airplay._tcp.local.",
        "_raop._tcp.local.",
        "_googlecast._tcp.local.",
        "_spotify-connect._tcp.local.",
        "_homekit._tcp.local.",
        "_hap._tcp.local.",
        "_workstation._tcp.local.",
        "_companion-link._tcp.local.",
        "_rdp._tcp.local.",
        "_rtsp._tcp.local.",
        "_device-info._tcp.local.",
    )

    /**
     * Browses the LAN for [timeoutMillis].
     *
     * The multicast lock is held for exactly the duration of the browse and released in
     * a finally block, and JmDNS is always closed.
     */
    suspend fun discover(
        boundAddress: InetAddress?,
        timeoutMillis: Long,
        onRecord: suspend (MdnsRecord) -> Unit,
    ) = withContext(Dispatchers.IO) {
        multicastLockHolder.acquire()
        var jmdns: JmDNS? = null
        try {
            jmdns = runCatching {
                if (boundAddress != null) JmDNS.create(boundAddress, HOST_LABEL) else JmDNS.create()
            }.getOrNull() ?: return@withContext

            // Split the budget across types so one silent type cannot eat the whole scan.
            val perTypeTimeout = (timeoutMillis / serviceTypes.size).coerceIn(80L, 600L)
            for (type in serviceTypes) {
                coroutineContext.ensureActive()
                val found = runCatching { jmdns.list(type, perTypeTimeout) }.getOrNull() ?: continue
                for (info in found) {
                    coroutineContext.ensureActive()
                    emitRecords(info, onRecord)
                }
            }
        } finally {
            runCatching { jmdns?.close() }
            multicastLockHolder.release()
        }
    }

    private suspend fun emitRecords(info: ServiceInfo, onRecord: suspend (MdnsRecord) -> Unit) {
        val addresses = runCatching { info.inetAddresses }.getOrNull() ?: return
        // mDNS payloads are remote input; every string is sanitised before it is stored.
        val serviceName = sanitize(runCatching { info.name }.getOrNull())
        val serviceType = sanitize(runCatching { info.type }.getOrNull())
        val server = sanitize(runCatching { info.server }.getOrNull())?.removeSuffix(".")
        val properties = runCatching {
            info.propertyNames.toList().take(MAX_PROPERTIES).associate { key ->
                sanitize(key).orEmpty() to sanitize(info.getPropertyString(key)).orEmpty()
            }
        }.getOrDefault(emptyMap())

        for (address in addresses) {
            if (address == null) continue
            onRecord(
                MdnsRecord(
                    address = address,
                    hostname = server,
                    serviceName = serviceName ?: "unknown",
                    serviceType = serviceType ?: "unknown",
                    port = runCatching { info.port }.getOrDefault(0),
                    properties = properties,
                ),
            )
        }
    }

    /** Caps length and removes control characters from remotely supplied text. */
    private fun sanitize(raw: String?): String? = raw
        ?.filter { it == ' ' || !it.isISOControl() }
        ?.trim()
        ?.take(MAX_STRING_LENGTH)
        ?.ifEmpty { null }

    companion object {
        private const val HOST_LABEL = "netscope"
        private const val MAX_STRING_LENGTH = 128
        private const val MAX_PROPERTIES = 16

        /** Builds the service record stored against a device. */
        fun toService(record: MdnsRecord, observedAtEpochMillis: Long) = DiscoveredService(
            port = record.port.takeIf { it > 0 },
            protocol = if (record.serviceType.contains("_udp")) "udp" else "tcp",
            name = record.serviceName,
            serviceType = record.serviceType,
            detail = "Advertised over mDNS" + (record.hostname?.let { " by $it" } ?: "") + ".",
            source = EvidenceSource.MDNS,
            confidence = Confidence.HIGH,
            observedAtEpochMillis = observedAtEpochMillis,
        )

        fun isIpv4(address: InetAddress): Boolean = address is Inet4Address
    }
}
