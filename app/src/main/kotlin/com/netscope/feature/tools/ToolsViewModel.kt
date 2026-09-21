package com.netscope.feature.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.Ipv6Cidr
import com.netscope.core.model.MacAddress
import com.netscope.core.model.PingStatistics
import com.netscope.core.model.PortResult
import com.netscope.core.model.ProbeType
import com.netscope.core.model.TracerouteHop
import com.netscope.core.network.DnsLookupResult
import com.netscope.core.network.DnsRecordType
import com.netscope.core.network.DnsTools
import com.netscope.core.network.HostProber
import com.netscope.core.network.IcmpProbe
import com.netscope.core.network.NetworkInspector
import com.netscope.core.network.PortScanner
import com.netscope.core.network.ReachabilityService
import com.netscope.core.network.RouteExplanation
import com.netscope.core.network.Traceroute
import com.netscope.core.network.TracerouteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject

/** Which tool is on screen. */
enum class Tool(val label: String) {
    PING("Ping"),
    TCP_PING("TCP ping"),
    TRACEROUTE("Traceroute"),
    DNS("DNS"),
    PORT_SCAN("Port scanner"),
    SUBNET_CALCULATOR("Subnet calculator"),
    ROUTE_DIAGNOSTICS("Route diagnostics"),
    WAKE_ON_LAN("Wake-on-LAN"),
}

/** Everything the subnet calculator derived, or the reason it could not. */
data class SubnetCalculation(
    val cidr: String,
    val networkAddress: String,
    val maskAddress: String,
    val wildcardAddress: String,
    val broadcastAddress: String,
    val firstHost: String,
    val lastHost: String,
    val totalAddresses: String,
    val usableHosts: String,
    val note: String?,
)

data class ToolsUiState(
    val selectedTool: Tool = Tool.PING,
    val icmpAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,

    val pingHost: String = "",
    val pingResults: List<Double?> = emptyList(),
    val pingStatistics: PingStatistics? = null,
    val pingProbeDetail: String? = null,

    val tcpHost: String = "",
    val tcpPort: String = "443",

    val tracerouteHost: String = "",
    val tracerouteHops: List<TracerouteHop> = emptyList(),
    val tracerouteResult: TracerouteResult? = null,

    val dnsQuery: String = "",
    val dnsRecordType: DnsRecordType = DnsRecordType.A,
    val dnsResult: DnsLookupResult? = null,
    val dnsSupportsAllTypes: Boolean = true,
    val dnsRecursionDesired: Boolean = true,

    val portHost: String = "",
    val portProfile: PortScanner.Profile = PortScanner.Profile.QUICK,
    val portSpec: String = "",
    val portResults: List<PortResult> = emptyList(),

    val calculatorInput: String = "",
    val calculation: SubnetCalculation? = null,
    val calculatorError: String? = null,

    val routeDestination: String = "",
    val routeExplanation: RouteExplanation? = null,

    val wolMac: String = "",
    val wolBroadcast: String = "255.255.255.255",
    val wolPort: String = "9",
    val wolMessage: String? = null,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val hostProber: HostProber,
    private val icmpProbe: IcmpProbe,
    private val traceroute: Traceroute,
    private val dnsTools: DnsTools,
    private val portScanner: PortScanner,
    private val reachabilityService: ReachabilityService,
    private val networkInspector: NetworkInspector,
) : ViewModel() {

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    private var activeJob: Job? = null

    init {
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { icmpProbe.isAvailable() }
            _state.value = _state.value.copy(
                icmpAvailable = available,
                dnsSupportsAllTypes = dnsTools.supportsArbitraryRecordTypes,
            )
        }
    }

    fun selectTool(tool: Tool) {
        cancel()
        _state.value = _state.value.copy(selectedTool = tool, error = null)
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = _state.value.copy(busy = false)
    }

    // ---- Ping -------------------------------------------------------------------

    fun setPingHost(value: String) { _state.value = _state.value.copy(pingHost = value) }

    /**
     * Runs a ping.
     *
     * The probe type is decided once and displayed with the result: a TCP-connect round
     * trip is never labelled as a ping, because the two measure different things.
     */
    fun runPing(count: Int = 5) {
        val host = _state.value.pingHost.trim()
        if (host.isEmpty()) {
            _state.value = _state.value.copy(error = "Enter a hostname or IP address.")
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, pingResults = emptyList())
            try {
                val address = withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(host) }.getOrNull()
                }
                if (address == null) {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = "$host could not be resolved. Check the name or the DNS settings.",
                    )
                    return@launch
                }

                val useIcmp = icmpProbe.isAvailable()
                val probeType = if (useIcmp) ProbeType.ICMP else ProbeType.TCP_CONNECT
                val results = mutableListOf<Double?>()

                repeat(count) { index ->
                    val rtt = if (useIcmp) {
                        icmpProbe.echo(address, PROBE_TIMEOUT_MILLIS, index + 1).rttMillis
                    } else {
                        hostProber.tcpConnect(address, FALLBACK_TCP_PORT, PROBE_TIMEOUT_MILLIS)
                            .latencyMillis
                    }
                    results += rtt
                    _state.value = _state.value.copy(
                        pingResults = results.toList(),
                        pingStatistics = PingStatistics.from(host, probeType, results.toList()),
                    )
                    if (index < count - 1) delay(PING_INTERVAL_MILLIS)
                }

                _state.value = _state.value.copy(
                    busy = false,
                    pingStatistics = PingStatistics.from(host, probeType, results),
                    pingProbeDetail = if (useIcmp) {
                        "Measured with real ICMP echo over an unprivileged datagram socket."
                    } else {
                        "Unprivileged ICMP is unavailable on this device, so this is a TCP connect " +
                            "round trip to port $FALLBACK_TCP_PORT — not a ping. A firewall may " +
                            "block it even where ICMP would succeed."
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    // ---- TCP ping ---------------------------------------------------------------

    fun setTcpHost(value: String) { _state.value = _state.value.copy(tcpHost = value) }
    fun setTcpPort(value: String) { _state.value = _state.value.copy(tcpPort = value) }

    fun runTcpPing(count: Int = 5) {
        val host = _state.value.tcpHost.trim()
        val port = _state.value.tcpPort.trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            _state.value = _state.value.copy(error = "Enter a host and a port between 1 and 65535.")
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, pingResults = emptyList())
            try {
                val address = withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(host) }.getOrNull()
                }
                if (address == null) {
                    _state.value = _state.value.copy(busy = false, error = "$host could not be resolved.")
                    return@launch
                }
                val results = mutableListOf<Double?>()
                repeat(count) { index ->
                    val outcome = hostProber.tcpConnect(address, port, PROBE_TIMEOUT_MILLIS)
                    results += outcome.latencyMillis
                    _state.value = _state.value.copy(
                        pingResults = results.toList(),
                        pingStatistics = PingStatistics.from(
                            "$host:$port", ProbeType.TCP_CONNECT, results.toList(),
                        ),
                    )
                    if (index < count - 1) delay(PING_INTERVAL_MILLIS)
                }
                _state.value = _state.value.copy(
                    busy = false,
                    pingProbeDetail = "TCP connect round trip to $host:$port. This is the correct " +
                        "tool when ICMP is filtered, and it measures connection setup, not a ping.",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    // ---- Traceroute -------------------------------------------------------------

    fun setTracerouteHost(value: String) {
        _state.value = _state.value.copy(tracerouteHost = value)
    }

    fun runTraceroute() {
        val host = _state.value.tracerouteHost.trim()
        if (host.isEmpty()) {
            _state.value = _state.value.copy(error = "Enter a hostname or IP address.")
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, tracerouteHops = emptyList())
            try {
                val address = withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(host) }.getOrNull()
                }
                if (address == null) {
                    _state.value = _state.value.copy(busy = false, error = "$host could not be resolved.")
                    return@launch
                }
                val result = traceroute.trace(address) { hop ->
                    _state.value = _state.value.copy(
                        tracerouteHops = _state.value.tracerouteHops + hop,
                    )
                }
                _state.value = _state.value.copy(busy = false, tracerouteResult = result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    // ---- DNS --------------------------------------------------------------------

    fun setDnsQuery(value: String) { _state.value = _state.value.copy(dnsQuery = value) }
    fun setDnsRecordType(type: DnsRecordType) { _state.value = _state.value.copy(dnsRecordType = type) }
    fun setDnsRecursionDesired(value: Boolean) { _state.value = _state.value.copy(dnsRecursionDesired = value) }

    fun runDnsLookup() {
        val query = _state.value.dnsQuery.trim()
        if (query.isEmpty()) {
            _state.value = _state.value.copy(error = "Enter a hostname, or an IP address for a reverse lookup.")
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, dnsResult = null)
            try {
                val type = _state.value.dnsRecordType
                val result = if (type == DnsRecordType.PTR) {
                    // A reverse lookup takes an address and asks for the name behind it.
                    val address = withContext(Dispatchers.IO) {
                        runCatching { InetAddress.getByName(query) }.getOrNull()
                    }
                    val pointerName = address?.let { DnsTools.reversePointerName(it) }
                    if (pointerName == null) {
                        _state.value = _state.value.copy(
                            busy = false,
                            error = "Enter an IP address to look up its PTR record.",
                        )
                        return@launch
                    }
                    dnsTools.query(pointerName, DnsRecordType.PTR, recursionDesired = _state.value.dnsRecursionDesired)
                } else if (type == DnsRecordType.A || type == DnsRecordType.AAAA) {
                    dnsTools.resolve(query)
                } else {
                    dnsTools.query(query, type, recursionDesired = _state.value.dnsRecursionDesired)
                }
                _state.value = _state.value.copy(busy = false, dnsResult = result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    // ---- Port scanner -----------------------------------------------------------

    fun setPortHost(value: String) { _state.value = _state.value.copy(portHost = value) }
    fun setPortProfile(profile: PortScanner.Profile) {
        _state.value = _state.value.copy(portProfile = profile)
    }
    fun setPortSpec(value: String) { _state.value = _state.value.copy(portSpec = value) }

    fun runPortScan() {
        val host = _state.value.portHost.trim()
        if (host.isEmpty()) {
            _state.value = _state.value.copy(error = "Enter a host to scan.")
            return
        }
        val profile = _state.value.portProfile
        val ports = if (profile == PortScanner.Profile.CUSTOM) {
            PortScanner.parsePortSpec(_state.value.portSpec)
        } else {
            profile.ports
        }
        if (ports.isEmpty()) {
            _state.value = _state.value.copy(
                error = "No valid ports. Use a list or ranges, for example 22, 80, 8000-8010.",
            )
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, portResults = emptyList())
            try {
                val address = withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(host) }.getOrNull()
                }
                if (address == null) {
                    _state.value = _state.value.copy(busy = false, error = "$host could not be resolved.")
                    return@launch
                }
                portScanner.scan(address, ports) { result ->
                    _state.value = _state.value.copy(
                        portResults = (_state.value.portResults + result).sortedBy { it.port },
                    )
                }
                _state.value = _state.value.copy(busy = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    // ---- Subnet calculator ------------------------------------------------------

    fun setCalculatorInput(value: String) {
        _state.value = _state.value.copy(calculatorInput = value, calculatorError = null)
    }

    /** Calculates an IPv4 or IPv6 block. Runs entirely on core-model's tested maths. */
    fun calculate() {
        val input = _state.value.calculatorInput.trim()
        if (input.contains(':')) {
            val cidr = Ipv6Cidr.parse(input)
            if (cidr == null) {
                _state.value = _state.value.copy(
                    calculatorError = "Enter an IPv6 prefix, for example 2001:db8::/64.",
                    calculation = null,
                )
                return
            }
            _state.value = _state.value.copy(
                calculation = SubnetCalculation(
                    cidr = cidr.toString(),
                    networkAddress = cidr.prefixAddress.toCanonicalString(),
                    maskAddress = "NOT APPLICABLE — IPv6 uses prefix lengths, not masks",
                    wildcardAddress = "NOT APPLICABLE",
                    broadcastAddress = "NOT APPLICABLE — IPv6 has no broadcast address",
                    firstHost = cidr.prefixAddress.toCanonicalString(),
                    lastHost = cidr.lastAddress.toCanonicalString(),
                    totalAddresses = cidr.addressCount.toString(),
                    usableHosts = cidr.addressCount.toString(),
                    note = "IPv6 reserves no network or broadcast address, so every address in the " +
                        "prefix is usable.",
                ),
                calculatorError = null,
            )
            return
        }

        val cidr = Ipv4Cidr.parse(input)
        if (cidr == null) {
            _state.value = _state.value.copy(
                calculatorError = "Enter an address and prefix, for example 192.168.1.10/24.",
                calculation = null,
            )
            return
        }
        _state.value = _state.value.copy(
            calculation = SubnetCalculation(
                cidr = cidr.toString(),
                networkAddress = cidr.networkAddress.toCanonicalString(),
                maskAddress = cidr.maskAddress.toCanonicalString(),
                wildcardAddress = cidr.wildcardAddress.toCanonicalString(),
                broadcastAddress = cidr.broadcastAddress?.toCanonicalString()
                    ?: "NOT APPLICABLE for a /${cidr.prefixLength}",
                firstHost = cidr.firstUsableHost?.toCanonicalString() ?: "None",
                lastHost = cidr.lastUsableHost?.toCanonicalString() ?: "None",
                totalAddresses = "%,d".format(cidr.addressCount),
                usableHosts = "%,d".format(cidr.usableHostCount),
                note = when (cidr.prefixLength) {
                    31 -> "A /31 is a point-to-point link (RFC 3021): both addresses are usable and " +
                        "there is no broadcast address."
                    32 -> "A /32 is a single host, so there is no broadcast address and no host range."
                    else -> null
                },
            ),
            calculatorError = null,
        )
    }

    // ---- Route diagnostics ------------------------------------------------------

    fun setRouteDestination(value: String) {
        _state.value = _state.value.copy(routeDestination = value)
    }

    fun runRouteDiagnostics() {
        val destination = Ipv4Address.parse(_state.value.routeDestination.trim())
        if (destination == null) {
            _state.value = _state.value.copy(error = "Enter an IPv4 address, for example 10.0.7.5.")
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, routeExplanation = null)
            try {
                val network = withContext(Dispatchers.IO) { networkInspector.activeSnapshot() }
                val explanation = reachabilityService.explainRoute(destination, network)
                _state.value = _state.value.copy(busy = false, routeExplanation = explanation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = friendly(e))
            }
        }
    }

    // ---- Wake-on-LAN ------------------------------------------------------------

    fun setWolMac(value: String) { _state.value = _state.value.copy(wolMac = value) }
    fun setWolBroadcast(value: String) { _state.value = _state.value.copy(wolBroadcast = value) }
    fun setWolPort(value: String) { _state.value = _state.value.copy(wolPort = value) }

    /**
     * Sends a magic packet.
     *
     * Wake-on-LAN is fire-and-forget: the sender gets no acknowledgement, so this
     * reports that the packet was sent and explicitly does not claim the target woke up.
     */
    fun sendWakeOnLan() {
        val mac = MacAddress.parse(_state.value.wolMac.trim())
        val port = _state.value.wolPort.trim().toIntOrNull()
        if (mac == null) {
            _state.value = _state.value.copy(error = "Enter a MAC address, for example AA:BB:CC:DD:EE:FF.")
            return
        }
        if (port == null || port !in 1..65535) {
            _state.value = _state.value.copy(error = "Enter a port between 1 and 65535. WOL usually uses 9.")
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, wolMessage = null)
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    // A magic packet is six 0xFF bytes followed by the MAC sixteen times.
                    val payload = ByteArray(6) { 0xFF.toByte() } +
                        ByteArray(16 * 6) { index -> mac.bytes[index % 6] }
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        val address = InetAddress.getByName(_state.value.wolBroadcast.trim())
                        socket.send(DatagramPacket(payload, payload.size, address, port))
                    }
                    true
                }.getOrDefault(false)
            }
            _state.value = _state.value.copy(
                busy = false,
                wolMessage = if (sent) {
                    "Magic packet sent to ${_state.value.wolBroadcast}:$port for $mac. Wake-on-LAN " +
                        "gives the sender no acknowledgement, so NetScope cannot confirm the target " +
                        "woke up. The target must have WOL enabled in its firmware and operating " +
                        "system, and the packet must be able to reach its network segment — " +
                        "broadcasts do not normally cross a router."
                } else {
                    "The packet could not be sent. Check the broadcast address for this network."
                },
            )
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "That name could not be resolved."
        is java.net.SocketException -> "A socket could not be opened for this operation."
        is SecurityException -> "Android refused this operation."
        else -> e.message ?: "The operation failed."
    }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 2_000L
        const val PING_INTERVAL_MILLIS = 700L

        /** HTTPS is the port most likely to be reachable when ICMP is unavailable. */
        const val FALLBACK_TCP_PORT = 443
    }
}
