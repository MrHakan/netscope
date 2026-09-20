package com.netscope.core.model

import java.math.BigInteger

/**
 * An IPv4 network in CIDR form.
 *
 * [networkAddress] is always already masked, so two blocks written as 10.0.2.37/24 and
 * 10.0.2.0/24 compare equal.
 */
data class Ipv4Cidr(
    val networkAddress: Ipv4Address,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..32) { "IPv4 prefix length out of range: $prefixLength" }
    }

    val mask: Long
        get() = if (prefixLength == 0) 0L else (Ipv4Address.MAX shl (32 - prefixLength)) and Ipv4Address.MAX

    val maskAddress: Ipv4Address get() = Ipv4Address(mask)

    val wildcardAddress: Ipv4Address get() = Ipv4Address(Ipv4Address.MAX xor mask)

    /** Highest address in the block. For /32 this equals the network address. */
    val lastAddress: Ipv4Address get() = Ipv4Address(networkAddress.value or (Ipv4Address.MAX xor mask))

    /** Total addresses in the block, including network and broadcast where they exist. */
    val addressCount: Long get() = 1L shl (32 - prefixLength)

    /**
     * The broadcast address, which only exists for /30 and shorter.
     *
     * A /31 is a point-to-point link (RFC 3021) and a /32 is a single host; neither has
     * a broadcast address, and pretending otherwise is a classic subnet-calculator bug.
     */
    val broadcastAddress: Ipv4Address? get() = if (prefixLength <= 30) lastAddress else null

    /** First address a host may use. */
    val firstUsableHost: Ipv4Address?
        get() = when (prefixLength) {
            32 -> networkAddress                       // the host itself
            31 -> networkAddress                       // RFC 3021: both addresses are usable
            else -> Ipv4Address(networkAddress.value + 1)
        }

    /** Last address a host may use. */
    val lastUsableHost: Ipv4Address?
        get() = when (prefixLength) {
            32 -> networkAddress
            31 -> lastAddress
            else -> Ipv4Address(lastAddress.value - 1)
        }

    /** Number of addresses actually assignable to hosts. */
    val usableHostCount: Long
        get() = when (prefixLength) {
            32 -> 1L
            31 -> 2L
            else -> addressCount - 2
        }

    operator fun contains(address: Ipv4Address): Boolean =
        (address.value and mask) == networkAddress.value

    operator fun contains(other: Ipv4Cidr): Boolean =
        other.prefixLength >= prefixLength && contains(other.networkAddress)

    /**
     * Every address in the block that a scan should probe.
     *
     * Network and broadcast addresses are excluded for /30 and shorter because probing
     * them is noisy and tells you nothing about a host.
     */
    fun hostAddresses(): Sequence<Ipv4Address> {
        val first = firstUsableHost ?: return emptySequence()
        val last = lastUsableHost ?: return emptySequence()
        return generateSequence(first) { current ->
            if (current.value >= last.value) null else Ipv4Address(current.value + 1)
        }
    }

    override fun toString(): String = "${networkAddress.toCanonicalString()}/$prefixLength"

    companion object {
        /** Parses "10.0.2.0/24". Host bits in the input are masked off, not rejected. */
        fun parse(text: String): Ipv4Cidr? {
            val slash = text.indexOf('/')
            if (slash < 0) return null
            val address = Ipv4Address.parse(text.substring(0, slash)) ?: return null
            val prefix = text.substring(slash + 1).trim().toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            return of(address, prefix)
        }

        /** Builds a block from any address inside it plus a prefix length. */
        fun of(address: Ipv4Address, prefixLength: Int): Ipv4Cidr {
            require(prefixLength in 0..32) { "IPv4 prefix length out of range: $prefixLength" }
            val mask = if (prefixLength == 0) 0L else (Ipv4Address.MAX shl (32 - prefixLength)) and Ipv4Address.MAX
            return Ipv4Cidr(Ipv4Address(address.value and mask), prefixLength)
        }

        /** Converts a dotted-decimal mask such as 255.255.255.0 to a prefix length. */
        fun prefixLengthFromMask(mask: Ipv4Address): Int? {
            val v = mask.value
            // A valid mask is a run of ones followed by a run of zeros.
            val inverted = v.inv() and Ipv4Address.MAX
            if ((inverted + 1) and inverted != 0L) return null
            var count = 0
            var bit = 31
            while (bit >= 0 && (v shr bit) and 1L == 1L) { count++; bit-- }
            return count
        }
    }
}

/** An IPv6 prefix. Kept separate from the IPv4 type: the maths genuinely differs. */
data class Ipv6Cidr(
    val prefixAddress: Ipv6Address,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..128) { "IPv6 prefix length out of range: $prefixLength" }
    }

    val addressCount: BigInteger get() = BigInteger.TWO.pow(128 - prefixLength)

    val lastAddress: Ipv6Address
        get() {
            val out = prefixAddress.bytes.copyOf()
            for (i in 0 until 128) {
                if (i >= prefixLength) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            return Ipv6Address(out, prefixAddress.scopeId)
        }

    operator fun contains(address: Ipv6Address): Boolean {
        val a = prefixAddress.bytes
        val b = address.bytes
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) if (a[i] != b[i]) return false
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
    }

    override fun toString(): String = "${prefixAddress.toCanonicalString()}/$prefixLength"

    companion object {
        fun parse(text: String): Ipv6Cidr? {
            val slash = text.lastIndexOf('/')
            if (slash < 0) return null
            val address = Ipv6Address.parse(text.substring(0, slash)) ?: return null
            val prefix = text.substring(slash + 1).trim().toIntOrNull() ?: return null
            if (prefix !in 0..128) return null
            return of(address, prefix)
        }

        fun of(address: Ipv6Address, prefixLength: Int): Ipv6Cidr {
            require(prefixLength in 0..128) { "IPv6 prefix length out of range: $prefixLength" }
            val out = address.bytes.copyOf()
            for (i in 0 until 128) {
                if (i >= prefixLength) out[i / 8] = (out[i / 8].toInt() and (1 shl (7 - i % 8)).inv()).toByte()
            }
            return Ipv6Cidr(Ipv6Address(out, address.scopeId), prefixLength)
        }
    }
}

/** Parses either family from "address/prefix" text. */
fun parseCidr(text: String): Any? =
    if (text.contains(':')) Ipv6Cidr.parse(text) else Ipv4Cidr.parse(text)
