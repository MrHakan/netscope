package com.netscope.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Ipv6AddressTest {

    @Test
    fun `parses full form`() {
        val address = Ipv6Address.parse("2001:0db8:0000:0000:0000:ff00:0042:8329")
        assertThat(address).isNotNull()
        assertThat(address!!.toCanonicalString()).isEqualTo("2001:db8::ff00:42:8329")
    }

    @Test
    fun `parses compressed form`() {
        assertThat(Ipv6Address.parse("2001:db8::1")!!.toCanonicalString()).isEqualTo("2001:db8::1")
        assertThat(Ipv6Address.parse("::1")!!.toCanonicalString()).isEqualTo("::1")
        assertThat(Ipv6Address.parse("::")!!.toCanonicalString()).isEqualTo("::")
        assertThat(Ipv6Address.parse("fe80::1")!!.toCanonicalString()).isEqualTo("fe80::1")
    }

    @Test
    fun `normalises to rfc 5952 canonical form`() {
        // Uppercase in, lowercase out; leading zeros dropped; longest run compressed.
        assertThat(Ipv6Address.parse("2001:DB8:0:0:1:0:0:1")!!.toCanonicalString())
            .isEqualTo("2001:db8::1:0:0:1")
        // A single zero group is not compressed.
        assertThat(Ipv6Address.parse("2001:db8:0:1:1:1:1:1")!!.toCanonicalString())
            .isEqualTo("2001:db8:0:1:1:1:1:1")
    }

    @Test
    fun `parses an ipv4 mapped tail`() {
        val address = Ipv6Address.parse("::ffff:192.168.1.1")
        assertThat(address).isNotNull()
        assertThat(address!!.bytes[12].toInt() and 0xFF).isEqualTo(192)
        assertThat(address.bytes[15].toInt() and 0xFF).isEqualTo(1)
    }

    @Test
    fun `retains a scope id`() {
        val address = Ipv6Address.parse("fe80::1%wlan0")
        assertThat(address).isNotNull()
        assertThat(address!!.scopeId).isEqualTo("wlan0")
        assertThat(address.toCanonicalString()).isEqualTo("fe80::1%wlan0")
        assertThat(address.isLinkLocal).isTrue()
    }

    @Test
    fun `strips url brackets`() {
        assertThat(Ipv6Address.parse("[2001:db8::1]")!!.toCanonicalString()).isEqualTo("2001:db8::1")
    }

    @Test
    fun `rejects malformed input`() {
        val bad = listOf(
            "", "2001:db8::1::2", "2001:db8:::1", "12345::1", "2001:db8:0:0:0:0:0:0:1",
            "2001:db8", "gggg::1", "fe80::1%",
        )
        for (text in bad) {
            assertThat(Ipv6Address.parse(text)).isNull()
        }
    }

    @Test
    fun `classifies scopes`() {
        assertThat(Ipv6Address.parse("fe80::1")!!.scope).isEqualTo(Ipv6Scope.LINK_LOCAL)
        assertThat(Ipv6Address.parse("fd00::1")!!.scope).isEqualTo(Ipv6Scope.UNIQUE_LOCAL)
        assertThat(Ipv6Address.parse("2001:db8::1")!!.scope).isEqualTo(Ipv6Scope.GLOBAL)
        assertThat(Ipv6Address.parse("::1")!!.scope).isEqualTo(Ipv6Scope.LOOPBACK)
        assertThat(Ipv6Address.parse("ff02::1")!!.scope).isEqualTo(Ipv6Scope.MULTICAST)
        assertThat(Ipv6Address.parse("::")!!.scope).isEqualTo(Ipv6Scope.UNSPECIFIED)
    }

    @Test
    fun `equality is by value`() {
        assertThat(Ipv6Address.parse("2001:db8::1")).isEqualTo(Ipv6Address.parse("2001:0db8:0:0:0:0:0:1"))
    }
}

class Ipv6CidrTest {

    @Test
    fun `masks the prefix`() {
        val cidr = Ipv6Cidr.parse("2001:db8::1234/64")!!
        assertThat(cidr.prefixAddress.toCanonicalString()).isEqualTo("2001:db8::")
    }

    @Test
    fun `computes containment`() {
        val cidr = Ipv6Cidr.parse("2001:db8::/32")!!
        assertThat(Ipv6Address.parse("2001:db8:1::1")!! in cidr).isTrue()
        assertThat(Ipv6Address.parse("2001:db9::1")!! in cidr).isFalse()
    }

    @Test
    fun `handles non byte aligned prefixes`() {
        val cidr = Ipv6Cidr.parse("2001:db8::/33")!!
        assertThat(Ipv6Address.parse("2001:db8:7fff::1")!! in cidr).isTrue()
        assertThat(Ipv6Address.parse("2001:db8:8000::1")!! in cidr).isFalse()
    }

    @Test
    fun `computes the last address`() {
        val cidr = Ipv6Cidr.parse("2001:db8::/126")!!
        assertThat(cidr.lastAddress.toCanonicalString()).isEqualTo("2001:db8::3")
    }

    @Test
    fun `counts addresses`() {
        assertThat(Ipv6Cidr.parse("2001:db8::/64")!!.addressCount.toString())
            .isEqualTo("18446744073709551616")
    }
}
