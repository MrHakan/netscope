package com.netscope.core.network

import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.PortState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * A protocol the user can use to access a service discovered on a host.
 *
 * OPEN here means the TCP port accepted a connection. It does not mean authentication
 * will succeed, and NetScope never attempts credentials during service discovery.
 */
enum class AccessProtocol(
    val label: String,
    val scheme: String?,
    val defaultPort: Int?,
    val builtInBrowser: Boolean = false,
) {
    FTP("FTP", "ftp", 21, builtInBrowser = true),
    FTPS("FTPS", "ftps", 990),
    SSH("SSH", "ssh", 22),
    SFTP("SFTP", "sftp", 22),
    TELNET("Telnet", "telnet", 23),
    HTTP("HTTP", "http", 80),
    HTTPS("HTTPS", "https", 443),
    SMB("SMB", "smb", 445),
    AFP("AFP", "afp", 548),
    RTSP("RTSP", "rtsp", 554),
    IPP("IPP", "ipp", 631),
    NFS("NFS", "nfs", 2049),
    RDP("RDP", "rdp", 3389),
    VNC("VNC", "vnc", 5900),
    RAW_PRINT("Raw printer", null, 9100),
}

/** One open service endpoint found by the service explorer. */
data class ServiceEndpoint(
    val host: String,
    val port: Int,
    val protocol: AccessProtocol,
    val latencyMillis: Long?,
    val banner: String? = null,
) {
    val uri: String? get() = ServiceCatalog.uriFor(protocol, host, port)
}

/** Mapping between well-known access ports and user-facing protocols. */
object ServiceCatalog {

    val scanPorts: List<Int> = listOf(
        21, 22, 23, 80, 139, 443, 445, 548, 554, 631, 990, 2049, 3389,
        5000, 5900, 5901, 8000, 8008, 8080, 8443, 9000, 9100,
    )

    fun protocolsForPort(port: Int): List<AccessProtocol> = when (port) {
        21 -> listOf(AccessProtocol.FTP)
        22 -> listOf(AccessProtocol.SSH, AccessProtocol.SFTP)
        23 -> listOf(AccessProtocol.TELNET)
        80, 5000, 8000, 8008, 8080, 9000 -> listOf(AccessProtocol.HTTP)
        443, 8443 -> listOf(AccessProtocol.HTTPS)
        139, 445 -> listOf(AccessProtocol.SMB)
        548 -> listOf(AccessProtocol.AFP)
        554 -> listOf(AccessProtocol.RTSP)
        631 -> listOf(AccessProtocol.IPP)
        990 -> listOf(AccessProtocol.FTPS)
        2049 -> listOf(AccessProtocol.NFS)
        3389 -> listOf(AccessProtocol.RDP)
        5900, 5901 -> listOf(AccessProtocol.VNC)
        9100 -> listOf(AccessProtocol.RAW_PRINT)
        else -> emptyList()
    }

    fun uriFor(protocol: AccessProtocol, host: String, port: Int): String? {
        val scheme = protocol.scheme ?: return null
        val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val portPart = if (protocol.defaultPort == port) "" else ":$port"
        return "$scheme://$authority$portPart/"
    }
}

data class ServiceScanProgress(
    val completedProbes: Long = 0,
    val totalProbes: Long = 0,
    val openEndpoints: Int = 0,
)

/**
 * Discovers access-oriented services on one host or a small private subnet.
 *
 * Unlike the general scanner this uses a fixed worker pool and a bounded Channel.
 * A /24 therefore does not create one coroutine per host or per port.
 */
@Singleton
class ServiceAccessScanner @Inject constructor(
    private val hostProber: HostProber,
) {
    suspend fun scanHost(
        address: Ipv4Address,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onEndpoint: suspend (ServiceEndpoint) -> Unit = {},
        onProgress: suspend (ServiceScanProgress) -> Unit = {},
    ): List<ServiceEndpoint> = coroutineScope {
        val host = InetAddress.getByAddress(address.bytes)
        val found = mutableListOf<ServiceEndpoint>()
        var completed = 0L
        for (port in ServiceCatalog.scanPorts) {
            coroutineContext.ensureActive()
            val result = hostProber.scanPort(host, port, timeoutMillis)
            completed++
            if (result.state == PortState.OPEN) {
                val endpoints = ServiceCatalog.protocolsForPort(port).map { protocol ->
                    ServiceEndpoint(
                        host = address.toCanonicalString(),
                        port = port,
                        protocol = protocol,
                        latencyMillis = result.latencyMillis,
                        banner = result.banner,
                    )
                }
                found += endpoints
                endpoints.forEach { onEndpoint(it) }
            }
            onProgress(ServiceScanProgress(completed, ServiceCatalog.scanPorts.size.toLong(), found.size))
        }
        found
    }

    suspend fun scanSubnet(
        target: Ipv4Cidr,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        workers: Int = DEFAULT_WORKERS,
        onEndpoint: suspend (ServiceEndpoint) -> Unit = {},
        onProgress: suspend (ServiceScanProgress) -> Unit = {},
    ): List<ServiceEndpoint> = coroutineScope {
        require(target.usableHostCount <= MAX_SUBNET_HOSTS) {
            "Service Explorer is intentionally limited to $MAX_SUBNET_HOSTS hosts per run. " +
                "Use a smaller CIDR or scan a specific host."
        }

        data class Task(val address: Ipv4Address, val port: Int)

        val total = target.usableHostCount * ServiceCatalog.scanPorts.size.toLong()
        val completed = AtomicLong(0)
        val results = java.util.concurrent.ConcurrentLinkedQueue<ServiceEndpoint>()
        val queue = Channel<Task>(capacity = workers.coerceIn(1, MAX_WORKERS) * 2)

        val producer = launch {
            try {
                for (address in target.hostAddresses()) {
                    coroutineContext.ensureActive()
                    for (port in ServiceCatalog.scanPorts) queue.send(Task(address, port))
                }
            } finally {
                queue.close()
            }
        }

        val workerJobs = List(workers.coerceIn(1, MAX_WORKERS)) {
            launch(Dispatchers.IO) {
                for (task in queue) {
                    coroutineContext.ensureActive()
                    val inet = InetAddress.getByAddress(task.address.bytes)
                    val result = hostProber.scanPort(inet, task.port, timeoutMillis)
                    if (result.state == PortState.OPEN) {
                        val endpoints = ServiceCatalog.protocolsForPort(task.port).map { protocol ->
                            ServiceEndpoint(
                                host = task.address.toCanonicalString(),
                                port = task.port,
                                protocol = protocol,
                                latencyMillis = result.latencyMillis,
                                banner = result.banner,
                            )
                        }
                        endpoints.forEach {
                            results += it
                            onEndpoint(it)
                        }
                    }
                    val done = completed.incrementAndGet()
                    if (done == total || done % PROGRESS_EVERY == 0L) {
                        onProgress(ServiceScanProgress(done, total, results.size))
                    }
                }
            }
        }

        producer.join()
        workerJobs.joinAll()
        onProgress(ServiceScanProgress(total, total, results.size))

        results
            .distinctBy { Triple(it.host, it.port, it.protocol) }
            .sortedWith(compareBy<ServiceEndpoint>({ Ipv4Address.parse(it.host)?.value ?: Long.MAX_VALUE }, { it.port }, { it.protocol.name }))
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 450L
        private const val DEFAULT_WORKERS = 48
        private const val MAX_WORKERS = 64
        private const val MAX_SUBNET_HOSTS = 4096L
        private const val PROGRESS_EVERY = 16L
    }
}
