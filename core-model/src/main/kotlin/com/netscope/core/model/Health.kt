package com.netscope.core.model

enum class HealthStatus { PASS, WARN, FAIL, UNKNOWN }

/**
 * One row of the network health panel.
 *
 * There is deliberately no composite score: a single number would hide exactly the
 * detail an engineer needs, and would have to be invented from unrelated units.
 */
data class HealthRow(
    val label: String,
    val status: HealthStatus,
    val value: String,
    val evidence: String,
)

data class HealthPanel(val rows: List<HealthRow>, val generatedAtEpochMillis: Long)

/** Statistics from a ping run. Always carries the probe type that produced it. */
data class PingStatistics(
    val host: String,
    val probeType: ProbeType,
    val sent: Int,
    val received: Int,
    val minMillis: Double?,
    val avgMillis: Double?,
    val maxMillis: Double?,
    val jitterMillis: Double?,
) {
    val lossPercent: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent

    val probeLabel: String
        get() = when (probeType) {
            ProbeType.ICMP -> "ICMP"
            ProbeType.TCP_CONNECT -> "TCP connect"
            ProbeType.FALLBACK -> "fallback"
        }

    companion object {
        /** Builds statistics from raw round-trip times, treating nulls as losses. */
        fun from(host: String, probeType: ProbeType, results: List<Double?>): PingStatistics {
            val received = results.filterNotNull()
            // Jitter is the mean absolute difference between consecutive round trips
            // (RFC 3550's interarrival jitter uses the same idea).
            val jitter = if (received.size < 2) {
                null
            } else {
                received.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
            }
            return PingStatistics(
                host = host,
                probeType = probeType,
                sent = results.size,
                received = received.size,
                minMillis = received.minOrNull(),
                avgMillis = if (received.isEmpty()) null else received.average(),
                maxMillis = received.maxOrNull(),
                jitterMillis = jitter,
            )
        }
    }
}

/** One hop of a traceroute. An unresponsive hop is null, never fabricated. */
data class TracerouteHop(
    val ttl: Int,
    val address: IpAddress?,
    val hostname: String?,
    val rttMillis: List<Double?>,
    val reachedDestination: Boolean,
) {
    val displayAddress: String get() = address?.toCanonicalString() ?: "*"
}
