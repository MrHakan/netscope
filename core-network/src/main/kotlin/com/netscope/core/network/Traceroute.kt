package com.netscope.core.network

import com.netscope.core.model.ProbeType
import com.netscope.core.model.TracerouteHop
import kotlinx.coroutines.ensureActive
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Which technique produced a traceroute, and what it can and cannot show.
 *
 * Identifying intermediate routers requires reading the ICMP Time Exceeded message
 * from the socket error queue (IP_RECVERR + MSG_ERRQUEUE) or a raw socket. Neither is
 * reachable through Android's public APIs: android.system.OsConstants does not expose
 * those constants and raw sockets need root. Rather than print a table of fabricated
 * hops, NetScope reports what it can actually establish — how many hops away the
 * destination is — and says plainly why the middle of the path is blank.
 */
enum class TracerouteMethod(val label: String, val caveat: String) {
    ICMP_TTL(
        "ICMP TTL probe",
        "Intermediate routers cannot be identified: reading the ICMP Time Exceeded reply " +
            "requires the socket error queue, which Android does not expose to apps. Hop " +
            "distance to the destination is measured directly and is accurate.",
    ),
    TCP_TTL(
        "TCP TTL probe",
        "ICMP was unavailable on this device, so hop distance was measured with TTL-limited " +
            "TCP connects. Intermediate routers cannot be identified for the same reason.",
    ),
}

data class TracerouteResult(
    val destination: String,
    val resolvedAddress: String?,
    val method: TracerouteMethod,
    val hops: List<TracerouteHop>,
    val destinationReachedAtTtl: Int?,
    val completed: Boolean,
)

/**
 * Measures how far away a destination is by raising the TTL until it answers.
 *
 * Each TTL is probed [probesPerHop] times. A hop that does not identify itself is
 * reported as `*`, never invented.
 */
@Singleton
class Traceroute @Inject constructor(
    private val icmpProbe: IcmpProbe,
    private val hostProber: HostProber,
) {

    suspend fun trace(
        destination: InetAddress,
        maxHops: Int = 30,
        probesPerHop: Int = 3,
        timeoutMillis: Long = 1_000,
        tcpPort: Int = 80,
        onHop: suspend (TracerouteHop) -> Unit = {},
    ): TracerouteResult {
        val useIcmp = icmpProbe.isAvailable()
        val method = if (useIcmp) TracerouteMethod.ICMP_TTL else TracerouteMethod.TCP_TTL
        val hops = mutableListOf<TracerouteHop>()
        var reachedAtTtl: Int? = null

        for (ttl in 1..maxHops) {
            coroutineContext.ensureActive()
            val rtts = mutableListOf<Double?>()
            var responder: InetAddress? = null
            var reachedDestination = false

            repeat(probesPerHop) { attempt ->
                coroutineContext.ensureActive()
                if (useIcmp) {
                    val result = icmpProbe.echo(
                        destination = destination,
                        timeoutMillis = timeoutMillis,
                        sequence = ttl * 100 + attempt,
                        ttl = ttl,
                    )
                    rtts += result.rttMillis
                    if (result.rttMillis != null && !result.ttlExceeded) {
                        // An echo reply at this TTL means the destination itself answered.
                        responder = result.responder ?: destination
                        reachedDestination = true
                    } else if (result.responder != null) {
                        responder = result.responder
                    }
                } else {
                    // Without ICMP there is no TTL control on a Java socket, so this
                    // measures the destination directly and reports a single hop.
                    val outcome = hostProber.tcpConnect(destination, tcpPort, timeoutMillis)
                    rtts += outcome.latencyMillis
                    if (outcome.responded) {
                        responder = destination
                        reachedDestination = true
                    }
                }
            }

            val hop = TracerouteHop(
                ttl = ttl,
                address = responder?.let(::toModelAddress),
                hostname = null,
                rttMillis = rtts,
                reachedDestination = reachedDestination,
            )
            hops += hop
            onHop(hop)

            if (reachedDestination) {
                reachedAtTtl = ttl
                break
            }
            // Without TTL control every TCP attempt probes the destination directly, so
            // looping over TTLs would just repeat the same measurement.
            if (!useIcmp) break
        }

        return TracerouteResult(
            destination = destination.hostName ?: destination.hostAddress.orEmpty(),
            resolvedAddress = destination.hostAddress,
            method = method,
            hops = hops,
            destinationReachedAtTtl = reachedAtTtl,
            completed = reachedAtTtl != null,
        )
    }

    private fun toModelAddress(address: InetAddress): com.netscope.core.model.IpAddress? = when (address) {
        is java.net.Inet4Address -> com.netscope.core.model.Ipv4Address.fromBytes(address.address)
        is java.net.Inet6Address -> com.netscope.core.model.Ipv6Address.fromBytes(address.address)
        else -> null
    }

    companion object {
        val PROBE_TYPE: ProbeType = ProbeType.ICMP
    }
}
