package com.netscope.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Ipv4AddressTest {

    @Test
    fun `parses dotted quad`() {
        val address = Ipv4Address.parse("10.0.2.37")
        assertThat(address).isNotNull()
        assertThat(address!!.value).isEqualTo(0x0A000225L)
        assertThat(address.toCanonicalString()).isEqualTo("10.0.2.37")
    }

    @Test
    fun `parses boundary addresses`() {
        assertThat(Ipv4Address.parse("0.0.0.0")!!.value).isEqualTo(0L)
        assertThat(Ipv4Address.parse("255.255.255.255")!!.value).isEqualTo(0xFFFFFFFFL)
    }

    @Test
    fun `rejects malformed input`() {
        val bad = listOf(
            "", "10.0.2", "10.0.2.37.1", "256.0.0.1", "10.0.2.-1", "10.0.2.a",
            "10..2.37", "10.0.2.37.", ".10.0.2.37", "10.0.2.037", "1000.0.0.1",
        )
        for (text in bad) {
            assertThat(Ipv4Address.parse(text)).isNull()
        }
    }

    @Test
    fun `classifies private ranges`() {
        assertThat(Ipv4Address.parse("10.0.2.37")!!.isRfc1918).isTrue()
        assertThat(Ipv4Address.parse("172.16.0.1")!!.isRfc1918).isTrue()
        assertThat(Ipv4Address.parse("172.31.255.254")!!.isRfc1918).isTrue()
        assertThat(Ipv4Address.parse("172.32.0.1")!!.isRfc1918).isFalse()
        assertThat(Ipv4Address.parse("192.168.1.1")!!.isRfc1918).isTrue()
        assertThat(Ipv4Address.parse("8.8.8.8")!!.isRfc1918).isFalse()
        assertThat(Ipv4Address.parse("169.254.1.1")!!.isLinkLocal).isTrue()
        assertThat(Ipv4Address.parse("100.64.0.1")!!.isCarrierGradeNat).isTrue()
    }

    @Test
    fun `round trips through bytes`() {
        val address = Ipv4Address.parse("192.168.178.24")!!
        assertThat(Ipv4Address.fromBytes(address.bytes)).isEqualTo(address)
    }
}

class Ipv4CidrTest {

    @Test
    fun `computes a standard 24`() {
        val cidr = Ipv4Cidr.parse("10.0.2.37/24")!!
        assertThat(cidr.toString()).isEqualTo("10.0.2.0/24")
        assertThat(cidr.maskAddress.toCanonicalString()).isEqualTo("255.255.255.0")
        assertThat(cidr.wildcardAddress.toCanonicalString()).isEqualTo("0.0.0.255")
        assertThat(cidr.broadcastAddress!!.toCanonicalString()).isEqualTo("10.0.2.255")
        assertThat(cidr.firstUsableHost!!.toCanonicalString()).isEqualTo("10.0.2.1")
        assertThat(cidr.lastUsableHost!!.toCanonicalString()).isEqualTo("10.0.2.254")
        assertThat(cidr.usableHostCount).isEqualTo(254L)
        assertThat(cidr.addressCount).isEqualTo(256L)
    }

    @Test
    fun `handles slash 31 as a point to point link`() {
        val cidr = Ipv4Cidr.parse("10.0.2.4/31")!!
        // RFC 3021: both addresses are usable and there is no broadcast address.
        assertThat(cidr.broadcastAddress).isNull()
        assertThat(cidr.usableHostCount).isEqualTo(2L)
        assertThat(cidr.firstUsableHost!!.toCanonicalString()).isEqualTo("10.0.2.4")
        assertThat(cidr.lastUsableHost!!.toCanonicalString()).isEqualTo("10.0.2.5")
        assertThat(cidr.hostAddresses().toList()).hasSize(2)
    }

    @Test
    fun `handles slash 32 as a single host`() {
        val cidr = Ipv4Cidr.parse("10.0.2.37/32")!!
        assertThat(cidr.broadcastAddress).isNull()
        assertThat(cidr.usableHostCount).isEqualTo(1L)
        assertThat(cidr.firstUsableHost!!.toCanonicalString()).isEqualTo("10.0.2.37")
        assertThat(cidr.lastUsableHost!!.toCanonicalString()).isEqualTo("10.0.2.37")
        assertThat(cidr.hostAddresses().toList()).hasSize(1)
    }

    @Test
    fun `handles slash 30`() {
        val cidr = Ipv4Cidr.parse("192.168.1.4/30")!!
        assertThat(cidr.usableHostCount).isEqualTo(2L)
        assertThat(cidr.broadcastAddress!!.toCanonicalString()).isEqualTo("192.168.1.7")
        assertThat(cidr.hostAddresses().map { it.toCanonicalString() }.toList())
            .containsExactly("192.168.1.5", "192.168.1.6").inOrder()
    }

    @Test
    fun `handles slash 0`() {
        val cidr = Ipv4Cidr.parse("0.0.0.0/0")!!
        assertThat(cidr.addressCount).isEqualTo(4294967296L)
        assertThat(cidr.mask).isEqualTo(0L)
        assertThat(Ipv4Address.parse("8.8.8.8")!! in cidr).isTrue()
    }

    @Test
    fun `masks host bits on construction`() {
        assertThat(Ipv4Cidr.parse("10.0.2.37/24")).isEqualTo(Ipv4Cidr.parse("10.0.2.0/24"))
    }

    @Test
    fun `containment works for addresses and blocks`() {
        val slash16 = Ipv4Cidr.parse("10.0.0.0/16")!!
        val slash24 = Ipv4Cidr.parse("10.0.2.0/24")!!
        assertThat(Ipv4Address.parse("10.0.2.37")!! in slash16).isTrue()
        assertThat(Ipv4Address.parse("10.1.2.37")!! in slash16).isFalse()
        assertThat(slash24 in slash16).isTrue()
        assertThat(slash16 in slash24).isFalse()
    }

    @Test
    fun `converts masks to prefix lengths`() {
        assertThat(Ipv4Cidr.prefixLengthFromMask(Ipv4Address.parse("255.255.255.0")!!)).isEqualTo(24)
        assertThat(Ipv4Cidr.prefixLengthFromMask(Ipv4Address.parse("255.255.255.252")!!)).isEqualTo(30)
        assertThat(Ipv4Cidr.prefixLengthFromMask(Ipv4Address.parse("0.0.0.0")!!)).isEqualTo(0)
        assertThat(Ipv4Cidr.prefixLengthFromMask(Ipv4Address.parse("255.255.255.255")!!)).isEqualTo(32)
        // Not a contiguous mask.
        assertThat(Ipv4Cidr.prefixLengthFromMask(Ipv4Address.parse("255.0.255.0")!!)).isNull()
    }

    @Test
    fun `rejects invalid cidr text`() {
        assertThat(Ipv4Cidr.parse("10.0.2.0")).isNull()
        assertThat(Ipv4Cidr.parse("10.0.2.0/33")).isNull()
        assertThat(Ipv4Cidr.parse("10.0.2.0/-1")).isNull()
        assertThat(Ipv4Cidr.parse("10.0.2.0/abc")).isNull()
    }

    @Test
    fun `host enumeration excludes network and broadcast`() {
        val hosts = Ipv4Cidr.parse("192.168.0.0/29")!!.hostAddresses().map { it.toCanonicalString() }.toList()
        assertThat(hosts).hasSize(6)
        assertThat(hosts.first()).isEqualTo("192.168.0.1")
        assertThat(hosts.last()).isEqualTo("192.168.0.6")
    }
}
