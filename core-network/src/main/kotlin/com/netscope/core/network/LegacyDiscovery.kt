package com.netscope.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

data class NetBiosNodeStatus(
    val address: String,
    val names: List<String>,
    val macAddress: String?,
)

data class LlmnrAnswer(
    val name: String,
    val addresses: List<String>,
)

data class SnmpValue(
    val oid: String,
    val label: String,
    val value: String,
)

data class SnmpSnapshot(
    val address: String,
    val values: List<SnmpValue>,
    val error: String? = null,
)

/**
 * Compatibility discovery for networks that still advertise through NetBIOS, LLMNR,
 * or read-only SNMP. This class has no SNMP SET and never guesses credentials.
 */
@Singleton
class LegacyDiscovery @Inject constructor() {

    suspend fun netBiosNodeStatus(
        address: Inet4Address,
        timeoutMillis: Int = 1_500,
    ): NetBiosNodeStatus? = withContext(Dispatchers.IO) {
        val response = udpExchange(
            address,
            137,
            buildNetBiosNodeStatusQuery(),
            timeoutMillis,
            MAX_NETBIOS_BYTES,
        ) ?: return@withContext null
        parseNetBiosNodeStatus(address, response)
    }

    suspend fun llmnr(
        hostname: String,
        timeoutMillis: Int = 1_500,
    ): LlmnrAnswer? = withContext(Dispatchers.IO) {
        val clean = hostname.trim().trimEnd('.')
        if (clean.isEmpty() || clean.length > 253) return@withContext null
        val request = buildLlmnrQuery(clean)
        val group = InetAddress.getByName("224.0.0.252")
        val socket = DatagramSocket().apply {
            soTimeout = 250
            reuseAddress = true
        }
        try {
            socket.send(DatagramPacket(request, request.size, group, 5355))
            val deadline = System.currentTimeMillis() + timeoutMillis
            val addresses = linkedSetOf<String>()
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(ByteArray(MAX_LLMNR_BYTES), MAX_LLMNR_BYTES)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parseLlmnrResponse(packet.data.copyOf(packet.length)).forEach(addresses::add)
            }
            if (addresses.isEmpty()) null else LlmnrAnswer(clean, addresses.toList())
        } finally {
            socket.close()
        }
    }

    suspend fun snmpSystem(
        address: InetAddress,
        community: String,
        timeoutMillis: Int = 1_500,
    ): SnmpSnapshot = withContext(Dispatchers.IO) {
        val cleanCommunity = community.trim().take(MAX_COMMUNITY_LENGTH)
        if (cleanCommunity.isEmpty()) {
            return@withContext SnmpSnapshot(
                address.hostAddress.orEmpty(),
                emptyList(),
                "Community string is empty.",
            )
        }

        val targets = listOf(
            "1.3.6.1.2.1.1.5.0" to "sysName",
            "1.3.6.1.2.1.1.1.0" to "sysDescr",
            "1.3.6.1.2.1.1.3.0" to "sysUpTime",
        )
        val values = mutableListOf<SnmpValue>()
        for ((oid, label) in targets) {
            val response = udpExchange(
                address,
                161,
                buildSnmpGet(cleanCommunity, oid),
                timeoutMillis,
                MAX_SNMP_BYTES,
            ) ?: continue
            parseSnmpValue(response)?.let { values += SnmpValue(oid, label, it) }
        }

        SnmpSnapshot(
            address = address.hostAddress.orEmpty(),
            values = values,
            error = if (values.isEmpty()) "No SNMP v2c response was received." else null,
        )
    }

    private fun udpExchange(
        address: InetAddress,
        port: Int,
        request: ByteArray,
        timeoutMillis: Int,
        maxBytes: Int,
    ): ByteArray? {
        val socket = DatagramSocket().apply { soTimeout = timeoutMillis }
        return try {
            socket.send(DatagramPacket(request, request.size, address, port))
            val response = DatagramPacket(ByteArray(maxBytes), maxBytes)
            socket.receive(response)
            response.data.copyOfRange(response.offset, response.offset + response.length)
        } catch (_: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    private fun buildNetBiosNodeStatusQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4e, 0x53))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        val name = ByteArray(16) { 0x20 }
        name[0] = '*'.code.toByte()
        out.write(0x20)
        name.forEach { value ->
            val unsigned = value.toInt() and 0xFF
            out.write('A'.code + ((unsigned shr 4) and 0x0F))
            out.write('A'.code + (unsigned and 0x0F))
        }
        out.write(0x00)
        out.write(byteArrayOf(0x00, 0x21, 0x00, 0x01))
        return out.toByteArray()
    }

    private fun parseNetBiosNodeStatus(address: Inet4Address, data: ByteArray): NetBiosNodeStatus? {
        if (data.size < 57) return null
        var offset = 12
        offset = skipDnsName(data, offset) ?: return null
        if (offset + 10 > data.size) return null
        val rdLength = u16(data, offset + 8)
        offset += 10
        if (offset + rdLength > data.size || rdLength < 1) return null
        val count = data[offset].toInt() and 0xFF
        offset++
        val names = mutableListOf<String>()
        repeat(count.coerceAtMost(64)) {
            if (offset + 18 <= data.size) {
                val raw = String(data, offset, 15, Charsets.US_ASCII).trim()
                val suffix = data[offset + 15].toInt() and 0xFF
                val flags = u16(data, offset + 16)
                if (raw.isNotBlank()) {
                    names += sanitize(
                        raw + " <" + suffix.toString(16).padStart(2, '0') + ">" +
                            if (flags and 0x8000 != 0) " GROUP" else "",
                        64,
                    )
                }
                offset += 18
            }
        }
        val mac = if (offset + 6 <= data.size) {
            data.copyOfRange(offset, offset + 6)
                .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
                .takeUnless { it == "00:00:00:00:00:00" }
        } else null
        return NetBiosNodeStatus(address.hostAddress.orEmpty(), names.distinct(), mac)
    }

    private fun buildLlmnrQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4e, 0x53, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        name.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8).take(63).toByteArray()
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    private fun parseLlmnrResponse(data: ByteArray): List<String> {
        if (data.size < 12) return emptyList()
        val questionCount = u16(data, 4)
        val answerCount = u16(data, 6)
        var offset = 12
        repeat(questionCount) {
            offset = skipDnsName(data, offset) ?: return emptyList()
            if (offset + 4 > data.size) return emptyList()
            offset += 4
        }
        val result = mutableListOf<String>()
        repeat(answerCount.coerceAtMost(32)) {
            offset = skipDnsName(data, offset) ?: return result
            if (offset + 10 > data.size) return result
            val type = u16(data, offset)
            val length = u16(data, offset + 8)
            offset += 10
            if (offset + length > data.size) return result
            if (type == 1 && length == 4) {
                InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).hostAddress?.let(result::add)
            } else if (type == 28 && length == 16) {
                InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).hostAddress?.let(result::add)
            }
            offset += length
        }
        return result.distinct()
    }

    private fun buildSnmpGet(community: String, oidText: String): ByteArray {
        val variableBinding = sequence(
            oid(oidText),
            tlv(0x05, byteArrayOf()),
        )
        val variableBindings = sequence(variableBinding)
        val pdu = tlv(
            0xA0,
            integer(0x4e530001) + integer(0) + integer(0) + variableBindings,
        )
        return sequence(
            integer(1),
            tlv(0x04, community.toByteArray(Charsets.ISO_8859_1)),
            pdu,
        )
    }

    private fun parseSnmpValue(data: ByteArray): String? = runCatching {
        var index = 0
        val root = readTlv(data, index) ?: return null
        index = root.contentStart
        val version = readTlv(data, index) ?: return null
        index = version.end
        val community = readTlv(data, index) ?: return null
        index = community.end
        val pdu = readTlv(data, index) ?: return null
        var p = pdu.contentStart
        repeat(3) {
            p = readTlv(data, p)?.end ?: return null
        }
        val bindings = readTlv(data, p) ?: return null
        val binding = readTlv(data, bindings.contentStart) ?: return null
        val name = readTlv(data, binding.contentStart) ?: return null
        val value = readTlv(data, name.end) ?: return null
        decodeSnmpValue(data, value)
    }.getOrNull()

    private data class Tlv(val tag: Int, val contentStart: Int, val length: Int, val end: Int)

    private fun readTlv(data: ByteArray, start: Int): Tlv? {
        if (start + 2 > data.size) return null
        val tag = data[start].toInt() and 0xFF
        var index = start + 1
        var length = data[index].toInt() and 0xFF
        index++
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            if (count !in 1..4 || index + count > data.size) return null
            length = 0
            repeat(count) {
                length = (length shl 8) or (data[index++].toInt() and 0xFF)
            }
        }
        if (length < 0 || index + length > data.size) return null
        return Tlv(tag, index, length, index + length)
    }

    private fun decodeSnmpValue(data: ByteArray, value: Tlv): String = when (value.tag) {
        0x04 -> sanitize(String(data, value.contentStart, value.length, Charsets.UTF_8), 512)
        0x02, 0x41, 0x42, 0x43 -> {
            var number = 0L
            for (i in 0 until value.length.coerceAtMost(8)) {
                number = (number shl 8) or (data[value.contentStart + i].toLong() and 0xFF)
            }
            number.toString()
        }
        0x40 -> if (value.length == 4) {
            (0 until 4).joinToString(".") {
                (data[value.contentStart + it].toInt() and 0xFF).toString()
            }
        } else "IP address (" + value.length + " bytes)"
        0x06 -> decodeOid(data.copyOfRange(value.contentStart, value.end))
        0x05 -> "NULL"
        else -> "0x" + value.tag.toString(16) + " (" + value.length + " bytes)"
    }

    private fun sequence(vararg values: ByteArray): ByteArray =
        tlv(0x30, values.fold(byteArrayOf()) { a, b -> a + b })

    private fun tlv(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + encodeLength(body.size) + body

    private fun integer(value: Int): ByteArray {
        val bytes = ByteBuffer.allocate(4).putInt(value).array()
        var start = 0
        while (
            start < bytes.lastIndex &&
            bytes[start] == 0.toByte() &&
            bytes[start + 1].toInt() and 0x80 == 0
        ) {
            start++
        }
        return tlv(0x02, bytes.copyOfRange(start, bytes.size))
    }

    private fun oid(text: String): ByteArray {
        val parts = text.split('.').mapNotNull { it.toLongOrNull() }
        require(parts.size >= 2)
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        parts.drop(2).forEach { component ->
            val stack = mutableListOf<Int>()
            var value = component
            stack += (value and 0x7F).toInt()
            value = value ushr 7
            while (value > 0) {
                stack += ((value and 0x7F).toInt() or 0x80)
                value = value ushr 7
            }
            stack.asReversed().forEach(out::write)
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun decodeOid(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val result = mutableListOf<Long>()
        val first = data[0].toInt() and 0xFF
        val firstPart = (first / 40).coerceAtMost(2)
        result += firstPart.toLong()
        result += (first - firstPart * 40).toLong()
        var value = 0L
        for (i in 1 until data.size) {
            val b = data[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) {
                result += value
                value = 0
            }
        }
        return result.joinToString(".")
    }

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
    }

    private fun skipDnsName(data: ByteArray, start: Int): Int? {
        var offset = start
        var hops = 0
        while (offset < data.size && hops++ < 64) {
            val length = data[offset].toInt() and 0xFF
            when {
                length == 0 -> return offset + 1
                length and 0xC0 == 0xC0 ->
                    return if (offset + 1 < data.size) offset + 2 else null
                length > 63 || offset + 1 + length > data.size -> return null
                else -> offset += 1 + length
            }
        }
        return null
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        if (offset + 1 >= data.size) 0 else
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun sanitize(value: String, limit: Int): String =
        value.filter { it == ' ' || it == '\t' || !it.isISOControl() }.trim().take(limit)

    companion object {
        private const val MAX_NETBIOS_BYTES = 4096
        private const val MAX_LLMNR_BYTES = 4096
        private const val MAX_SNMP_BYTES = 8192
        private const val MAX_COMMUNITY_LENGTH = 128
    }
}
