package com.netscope.core.model

/**
 * IP address handling implemented without java.net, so that every calculation in this
 * module is unit-testable on a plain JVM and can never trigger a name lookup.
 */
sealed interface IpAddress : Comparable<IpAddress> {
    val bytes: ByteArray
    val bitLength: Int
    fun toCanonicalString(): String
}

/** An IPv4 address held as an unsigned 32-bit value in a Long. */
@JvmInline
value class Ipv4Address(val value: Long) : IpAddress {

    init {
        require(value in 0L..MAX) { "IPv4 value out of range: $value" }
    }

    override val bitLength: Int get() = 32

    override val bytes: ByteArray
        get() = byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )

    override fun toCanonicalString(): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}." +
            "${(value ushr 8) and 0xFF}.${value and 0xFF}"

    override fun compareTo(other: IpAddress): Int = when (other) {
        is Ipv4Address -> value.compareTo(other.value)
        is Ipv6Address -> -1
    }

    /** True for 10/8, 172.16/12, 192.168/16 (RFC 1918). */
    val isRfc1918: Boolean
        get() = (value ushr 24) == 10L ||
            (value ushr 20) == 0xAC1L ||
            (value ushr 16) == 0xC0A8L

    /** True for 100.64/10 (RFC 6598 carrier-grade NAT). */
    val isCarrierGradeNat: Boolean get() = (value ushr 22) == 0x191L

    /** True for 169.254/16 (RFC 3927 link-local). */
    val isLinkLocal: Boolean get() = (value ushr 16) == 0xA9FEL

    val isLoopback: Boolean get() = (value ushr 24) == 127L

    /** Ranges that may be scanned without the explicit non-private scope warning. */
    val isPrivateScope: Boolean get() = isRfc1918 || isCarrierGradeNat || isLinkLocal || isLoopback

    companion object {
        const val MAX: Long = 0xFFFFFFFFL

        /** Parses strict dotted-quad. Returns null on anything else — no partial forms. */
        fun parse(text: String): Ipv4Address? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > 15) return null
            var acc = 0L
            var octetValue = 0
            var octetDigits = 0
            var octets = 0
            for (ch in trimmed) {
                when {
                    ch in '0'..'9' -> {
                        if (octetDigits == 3) return null
                        // Reject leading zeros: "010" is ambiguous (octal in some parsers).
                        if (octetDigits == 1 && octetValue == 0) return null
                        octetValue = octetValue * 10 + (ch - '0')
                        octetDigits++
                        if (octetValue > 255) return null
                    }
                    ch == '.' -> {
                        if (octetDigits == 0) return null
                        acc = (acc shl 8) or octetValue.toLong()
                        octets++
                        if (octets > 3) return null
                        octetValue = 0
                        octetDigits = 0
                    }
                    else -> return null
                }
            }
            if (octetDigits == 0 || octets != 3) return null
            acc = (acc shl 8) or octetValue.toLong()
            return Ipv4Address(acc)
        }

        fun fromBytes(bytes: ByteArray): Ipv4Address? {
            if (bytes.size != 4) return null
            var acc = 0L
            for (b in bytes) acc = (acc shl 8) or (b.toLong() and 0xFF)
            return Ipv4Address(acc)
        }
    }
}

/** An IPv6 address held as 16 bytes, with an optional scope id for link-local addresses. */
class Ipv6Address(
    override val bytes: ByteArray,
    val scopeId: String? = null,
) : IpAddress {

    init {
        require(bytes.size == 16) { "IPv6 needs 16 bytes, got ${bytes.size}" }
    }

    override val bitLength: Int get() = 128

    val isLinkLocal: Boolean
        get() = (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80

    /** Unique Local Address, fc00::/7. */
    val isUniqueLocal: Boolean get() = (bytes[0].toInt() and 0xFE) == 0xFC

    val isLoopback: Boolean
        get() = bytes.dropLast(1).all { it.toInt() == 0 } && bytes[15].toInt() == 1

    val isMulticast: Boolean get() = (bytes[0].toInt() and 0xFF) == 0xFF

    val isUnspecified: Boolean get() = bytes.all { it.toInt() == 0 }

    val isGlobalUnicast: Boolean
        get() = !isLinkLocal && !isUniqueLocal && !isLoopback && !isMulticast && !isUnspecified

    /** Which address scope this falls in, for display grouping. */
    val scope: Ipv6Scope
        get() = when {
            isLoopback -> Ipv6Scope.LOOPBACK
            isLinkLocal -> Ipv6Scope.LINK_LOCAL
            isUniqueLocal -> Ipv6Scope.UNIQUE_LOCAL
            isMulticast -> Ipv6Scope.MULTICAST
            isUnspecified -> Ipv6Scope.UNSPECIFIED
            else -> Ipv6Scope.GLOBAL
        }

    private fun groups(): IntArray = IntArray(8) { i ->
        ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
    }

    /** RFC 5952 canonical text: lowercase, no leading zeros, longest zero-run compressed. */
    override fun toCanonicalString(): String {
        val groups = groups()
        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        var runLength = 0
        for (i in 0..8) {
            val isZero = i < 8 && groups[i] == 0
            if (isZero) {
                if (runStart < 0) runStart = i
                runLength++
            } else {
                // RFC 5952: only compress runs of two or more groups.
                if (runLength > bestLength && runLength >= 2) {
                    bestStart = runStart
                    bestLength = runLength
                }
                runStart = -1
                runLength = 0
            }
        }
        val sb = StringBuilder()
        var i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLength
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
            sb.append(groups[i].toString(16))
            i++
        }
        val text = if (sb.isEmpty()) "::" else sb.toString()
        return if (scopeId != null) "$text%$scopeId" else text
    }

    override fun compareTo(other: IpAddress): Int = when (other) {
        is Ipv4Address -> 1
        is Ipv6Address -> {
            var result = 0
            for (i in 0 until 16) {
                val a = bytes[i].toInt() and 0xFF
                val b = other.bytes[i].toInt() and 0xFF
                if (a != b) { result = a.compareTo(b); break }
            }
            result
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Ipv6Address && bytes.contentEquals(other.bytes) && scopeId == other.scopeId

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + (scopeId?.hashCode() ?: 0)

    override fun toString(): String = toCanonicalString()

    companion object {
        /** Parses RFC 4291 text form including "::" compression, %scope and IPv4 tails. */
        fun parse(text: String): Ipv6Address? {
            var body = text.trim()
            if (body.isEmpty()) return null
            // Strip an enclosing [] as used in URLs.
            if (body.startsWith("[") && body.endsWith("]")) body = body.substring(1, body.length - 1)
            var scope: String? = null
            val percent = body.indexOf('%')
            if (percent >= 0) {
                scope = body.substring(percent + 1).takeIf { it.isNotEmpty() } ?: return null
                body = body.substring(0, percent)
            }
            if (body.isEmpty()) return null

            // A trailing dotted-quad is expanded into two groups first.
            val lastColon = body.lastIndexOf(':')
            if (lastColon >= 0 && body.indexOf('.', lastColon) > 0) {
                val v4 = Ipv4Address.parse(body.substring(lastColon + 1)) ?: return null
                val high = ((v4.value ushr 16) and 0xFFFF).toString(16)
                val low = (v4.value and 0xFFFF).toString(16)
                body = body.substring(0, lastColon + 1) + high + ":" + low
            }

            val doubleColon = body.indexOf("::")
            if (doubleColon != body.lastIndexOf("::")) return null

            val head: List<String>
            val tail: List<String>
            if (doubleColon >= 0) {
                val headText = body.substring(0, doubleColon)
                val tailText = body.substring(doubleColon + 2)
                head = if (headText.isEmpty()) emptyList() else headText.split(':')
                tail = if (tailText.isEmpty()) emptyList() else tailText.split(':')
                if (head.size + tail.size > 7) return null
            } else {
                head = body.split(':')
                tail = emptyList()
                if (head.size != 8) return null
            }

            val groups = IntArray(8)
            for ((i, g) in head.withIndex()) {
                groups[i] = parseGroup(g) ?: return null
            }
            for ((i, g) in tail.withIndex()) {
                groups[8 - tail.size + i] = parseGroup(g) ?: return null
            }

            val out = ByteArray(16)
            for (i in 0 until 8) {
                out[i * 2] = ((groups[i] ushr 8) and 0xFF).toByte()
                out[i * 2 + 1] = (groups[i] and 0xFF).toByte()
            }
            return Ipv6Address(out, scope)
        }

        private fun parseGroup(text: String): Int? {
            if (text.isEmpty() || text.length > 4) return null
            var acc = 0
            for (ch in text) {
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> return null
                }
                acc = (acc shl 4) or digit
            }
            return acc
        }

        fun fromBytes(bytes: ByteArray, scopeId: String? = null): Ipv6Address? =
            if (bytes.size == 16) Ipv6Address(bytes, scopeId) else null
    }
}

enum class Ipv6Scope { LOOPBACK, LINK_LOCAL, UNIQUE_LOCAL, GLOBAL, MULTICAST, UNSPECIFIED }

/** Parses either family. Used wherever the user may type any address. */
fun parseIpAddress(text: String): IpAddress? =
    if (text.contains(':')) Ipv6Address.parse(text) else Ipv4Address.parse(text)
