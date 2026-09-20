package com.netscope.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServiceAccessTest {

    @Test
    fun `well known access ports map to expected protocols`() {
        assertThat(ServiceCatalog.protocolsForPort(21)).containsExactly(AccessProtocol.FTP)
        assertThat(ServiceCatalog.protocolsForPort(22))
            .containsExactly(AccessProtocol.SSH, AccessProtocol.SFTP)
            .inOrder()
        assertThat(ServiceCatalog.protocolsForPort(445)).containsExactly(AccessProtocol.SMB)
        assertThat(ServiceCatalog.protocolsForPort(3389)).containsExactly(AccessProtocol.RDP)
    }

    @Test
    fun `unknown port is not guessed`() {
        assertThat(ServiceCatalog.protocolsForPort(42424)).isEmpty()
    }

    @Test
    fun `uri omits default port and keeps alternate ports`() {
        assertThat(ServiceCatalog.uriFor(AccessProtocol.FTP, "10.0.7.5", 21))
            .isEqualTo("ftp://10.0.7.5/")
        assertThat(ServiceCatalog.uriFor(AccessProtocol.HTTP, "10.0.7.5", 8080))
            .isEqualTo("http://10.0.7.5:8080/")
    }

    @Test
    fun `raw printer has no invented uri scheme`() {
        assertThat(ServiceCatalog.uriFor(AccessProtocol.RAW_PRINT, "10.0.7.9", 9100)).isNull()
    }

    @Test
    fun `ipv6 uri authority is bracketed`() {
        assertThat(ServiceCatalog.uriFor(AccessProtocol.HTTPS, "fd00::7", 443))
            .isEqualTo("https://[fd00::7]/")
    }
}
