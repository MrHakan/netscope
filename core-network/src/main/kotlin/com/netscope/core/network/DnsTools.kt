package com.netscope.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * DNS record types from RFC 1035 and friends.
 *
 * These are protocol constants, not Android constants: the platform only publishes
 * TYPE_A and TYPE_AAAA, but rawQuery takes the numeric type directly.
 */
enum class DnsRecordType(val code: Int) {
    A(1), NS(2), CNAME(5), SOA(6), PTR(12), MX(15), TXT(16), AAAA(28), SRV(33),
    SVCB(64), HTTPS(65), SPF(99), CAA(257);
}

data class DnsAnswer(val type: DnsRecordType, val name: String, val value: String, val ttlSeconds: Long)

data class DnsLookupResult(
    val query: String,
    val type: DnsRecordType,
    val answers: List<DnsAnswer>,
    val durationMillis: Long,
    val resolverNote: String,
    val error: String? = null,
)

/**
 * DNS lookups.
 *
 * Record types beyond A/AAAA need DnsResolver.rawQuery, which arrived in Android 10;
 * below that only forward and reverse lookups are possible and the UI says so rather
 * than showing an empty table.
 */
@Singleton
class DnsTools @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val executor = Executor { it.run() }

    val supportsArbitraryRecordTypes: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Which resolver answered.
     *
     * Android does not report this, and claiming a specific server would be a guess, so
     * the configured resolvers are listed with an explicit caveat instead.
     */
    private fun resolverNote(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val servers = runCatching {
            manager?.activeNetwork
                ?.let { manager.getLinkProperties(it) }
                ?.dnsServers
                ?.mapNotNull { it.hostAddress }
        }.getOrNull().orEmpty()
        return if (servers.isEmpty()) {
            "The resolver that answered is not determinable on Android."
        } else {
            "Configured resolvers: ${servers.joinToString(", ")}. Android does not report which " +
                "one answered a given query."
        }
    }

    /** Forward lookup. Works on every supported release. */
    suspend fun resolve(hostname: String, timeoutMillis: Long = 5_000): DnsLookupResult =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            val answers = withTimeoutOrNull(timeoutMillis) {
                runCatching { InetAddress.getAllByName(hostname).toList() }.getOrDefault(emptyList())
            }.orEmpty()
            DnsLookupResult(
                query = hostname,
                type = DnsRecordType.A,
                answers = answers.map {
                    DnsAnswer(
                        type = if (it is Inet6Address) DnsRecordType.AAAA else DnsRecordType.A,
                        name = hostname,
                        value = it.hostAddress ?: "",
                        ttlSeconds = -1,
                    )
                },
                durationMillis = (System.nanoTime() - start) / 1_000_000,
                resolverNote = resolverNote(),
                error = if (answers.isEmpty()) "No address records were returned." else null,
            )
        }

    /**
     * Reverse lookup.
     *
     * getCanonicalHostName returns the literal address back when there is no PTR
     * record, which is reported as "not discovered" rather than as a hostname.
     */
    suspend fun reverseLookup(address: InetAddress, timeoutMillis: Long = 3_000): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) {
                runCatching {
                    val name = address.canonicalHostName
                    if (name.isNullOrEmpty() || name == address.hostAddress) null else sanitize(name)
                }.getOrNull()
            }
        }

    /** Arbitrary record lookup. Requires Android 10 or newer. */
    suspend fun query(
        name: String,
        type: DnsRecordType,
        timeoutMillis: Long = 5_000,
        recursionDesired: Boolean = true,
    ): DnsLookupResult {
        val start = System.nanoTime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DnsLookupResult(
                query = name, type = type, answers = emptyList(),
                durationMillis = 0, resolverNote = resolverNote(),
                error = "Looking up ${type.name} records requires Android 10 or newer. " +
                    "Address lookups still work on this device.",
            )
        }
        val raw = withTimeoutOrNull(timeoutMillis) {
            if (recursionDesired) rawQuery(name, type) else rawUdpQuery(name, type, false)
        }
        val duration = (System.nanoTime() - start) / 1_000_000
        if (raw == null) {
            return DnsLookupResult(
                name, type, emptyList(), duration, resolverNote(),
                error = "The query timed out after $timeoutMillis ms.",
            )
        }
        val parsed = runCatching { DnsResponseParser.parse(raw) }.getOrNull()
        if (parsed == null) {
            return DnsLookupResult(
                name, type, emptyList(), duration, resolverNote(),
                error = "The response could not be parsed.",
            )
        }
        return DnsLookupResult(
            query = name,
            type = type,
            answers = parsed,
            durationMillis = duration,
            resolverNote = resolverNote(),
            error = if (parsed.isEmpty()) "No ${type.name} records were returned." else null,
        )
    }

    /**
     * Direct DNS query used for +norec because DnsResolver does not expose the RD bit.
     */
    private suspend fun rawUdpQuery(
        name: String,
        type: DnsRecordType,
        recursionDesired: Boolean,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val resolver = runCatching {
            manager?.activeNetwork
                ?.let { manager.getLinkProperties(it) }
                ?.dnsServers
                ?.firstOrNull()
        }.getOrNull() ?: return@withContext null

        val query = buildDnsQuery(name, type, recursionDesired)
        val socket = java.net.DatagramSocket().apply { soTimeout = 4_500 }
        try {
            socket.send(java.net.DatagramPacket(query, query.size, resolver, 53))
            val packet = java.net.DatagramPacket(ByteArray(64 * 1024), 64 * 1024)
            socket.receive(packet)
            packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        } catch (_: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    private fun buildDnsQuery(
        name: String,
        type: DnsRecordType,
        recursionDesired: Boolean,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        output.write(byteArrayOf(0x4e, 0x53))
        output.write(if (recursionDesired) 0x01 else 0x00)
        output.write(0x00)
        output.write(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "DNS label is too long." }
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        output.write((type.code shr 8) and 0xFF)
        output.write(type.code and 0xFF)
        output.write(byteArrayOf(0x00, 0x01))
        return output.toByteArray()
    }

    private suspend fun rawQuery(name: String, type: DnsRecordType): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { runCatching { signal.cancel() } }
            val resolver = runCatching { DnsResolver.getInstance() }.getOrNull()
            if (resolver == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val callback = object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (continuation.isActive) continuation.resume(if (rcode == 0) answer else null)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            runCatching {
                resolver.rawQuery(
                    null, name, DnsResolver.CLASS_IN, type.code, 0, executor, signal, callback,
                )
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }

    private fun sanitize(raw: String): String =
        raw.filter { !it.isISOControl() }.take(253)

    companion object {
        /** Builds the in-addr.arpa / ip6.arpa name for a reverse query. */
        fun reversePointerName(address: InetAddress): String? = when (address) {
            is Inet4Address -> address.address.reversed()
                .joinToString(".") { (it.toInt() and 0xFF).toString() } + ".in-addr.arpa"
            is Inet6Address -> address.address
                .flatMap { listOf(it.toInt() and 0x0F, (it.toInt() shr 4) and 0x0F) }
                .reversed()
                .joinToString(".") { it.toString(16) } + ".ip6.arpa"
            else -> null
        }
    }
}

/**
 * Minimal DNS response parser.
 *
 * Responses come from the network and are therefore hostile input: every offset is
 * bounds-checked, name compression pointers are followed only a bounded number of
 * times so a self-referential response cannot loop forever, and unknown record types
 * are skipped rather than guessed at.
 */
internal object DnsResponseParser {

    private const val MAX_POINTER_HOPS = 16
    private const val MAX_NAME_LENGTH = 255

    fun parse(response: ByteArray): List<DnsAnswer> {
        if (response.size < 12) return emptyList()
        val answerCount = readUnsignedShort(response, 6)
        if (answerCount == 0) return emptyList()

        var offset = 12
        val questionCount = readUnsignedShort(response, 4)
        repeat(questionCount) {
            offset = skipName(response, offset) ?: return emptyList()
            offset += 4 // type + class
        }

        val answers = mutableListOf<DnsAnswer>()
        repeat(answerCount) {
            if (offset >= response.size) return answers
            val nameResult = readName(response, offset) ?: return answers
            offset = nameResult.second
            if (offset + 10 > response.size) return answers
            val type = readUnsignedShort(response, offset)
            val ttl = readUnsignedInt(response, offset + 4)
            val dataLength = readUnsignedShort(response, offset + 8)
            offset += 10
            if (offset + dataLength > response.size) return answers

            val recordType = DnsRecordType.entries.firstOrNull { it.code == type }
            if (recordType != null) {
                decode(response, offset, dataLength, recordType)?.let { value ->
                    answers += DnsAnswer(recordType, nameResult.first, value, ttl)
                }
            }
            offset += dataLength
        }
        return answers
    }

    private fun decode(data: ByteArray, offset: Int, length: Int, type: DnsRecordType): String? = when (type) {
        DnsRecordType.A ->
            if (length == 4) {
                (0 until 4).joinToString(".") { (data[offset + it].toInt() and 0xFF).toString() }
            } else {
                null
            }

        DnsRecordType.AAAA ->
            if (length == 16) {
                (0 until 8).joinToString(":") {
                    readUnsignedShort(data, offset + it * 2).toString(16)
                }
            } else {
                null
            }

        DnsRecordType.NS, DnsRecordType.CNAME, DnsRecordType.PTR ->
            readName(data, offset)?.first

        DnsRecordType.MX -> {
            val preference = readUnsignedShort(data, offset)
            readName(data, offset + 2)?.first?.let { "$preference $it" }
        }

        DnsRecordType.SRV -> {
            if (length < 7) {
                null
            } else {
                val priority = readUnsignedShort(data, offset)
                val weight = readUnsignedShort(data, offset + 2)
                val port = readUnsignedShort(data, offset + 4)
                readName(data, offset + 6)?.first?.let { "$priority $weight $port $it" }
            }
        }

        DnsRecordType.TXT, DnsRecordType.SPF -> decodeCharacterStrings(data, offset, length)

        DnsRecordType.CAA -> {
            if (length < 3) null else {
                val flags = data[offset].toInt() and 0xFF
                val tagLength = data[offset + 1].toInt() and 0xFF
                if (tagLength <= 0 || 2 + tagLength > length) null else {
                    val tag = String(data, offset + 2, tagLength, Charsets.US_ASCII)
                        .filter { !it.isISOControl() }
                    val valueLength = length - 2 - tagLength
                    val value = String(data, offset + 2 + tagLength, valueLength, Charsets.UTF_8)
                        .filter { !it.isISOControl() }.take(512)
                    flags.toString() + " " + tag + " " + value
                }
            }
        }

        DnsRecordType.SVCB, DnsRecordType.HTTPS -> decodeSvcb(data, offset, length)

        DnsRecordType.SOA -> readName(data, offset)?.first
    }

    private fun decodeCharacterStrings(data: ByteArray, offset: Int, length: Int): String? {
        val builder = StringBuilder()
        var cursor = offset
        val end = offset + length
        while (cursor < end && cursor < data.size) {
            val partLength = data[cursor].toInt() and 0xFF
            cursor++
            if (cursor + partLength > end || cursor + partLength > data.size) break
            if (builder.isNotEmpty()) builder.append(" ")
            builder.append(
                String(data, cursor, partLength, Charsets.UTF_8)
                    .filter { !it.isISOControl() },
            )
            cursor += partLength
        }
        return builder.toString().take(1024).ifEmpty { null }
    }

    private fun decodeSvcb(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 3 || offset + length > data.size) return null
        val end = offset + length
        val priority = readUnsignedShort(data, offset)
        val target = readName(data, offset + 2) ?: return null
        var cursor = target.second
        val params = mutableListOf<String>()
        while (cursor + 4 <= end && params.size < 24) {
            val key = readUnsignedShort(data, cursor)
            val valueLength = readUnsignedShort(data, cursor + 2)
            cursor += 4
            if (cursor + valueLength > end) break
            val value = data.copyOfRange(cursor, cursor + valueLength)
            val label = when (key) {
                0 -> "mandatory"
                1 -> "alpn"
                2 -> "no-default-alpn"
                3 -> "port"
                4 -> "ipv4hint"
                5 -> "ech"
                6 -> "ipv6hint"
                else -> "key" + key
            }
            val rendered = when (key) {
                1 -> decodeAlpn(value)
                3 -> if (value.size == 2) readUnsignedShort(value, 0).toString() else hex(value)
                4 -> if (value.size % 4 == 0) value.asList().chunked(4).joinToString(",") { chunk ->
                    chunk.joinToString(".") { (it.toInt() and 0xFF).toString() }
                } else hex(value)
                6 -> if (value.size % 16 == 0) value.asList().chunked(16).joinToString(",") { chunk ->
                    runCatching { java.net.InetAddress.getByAddress(chunk.toByteArray()).hostAddress }.getOrNull()
                        ?: hex(chunk.toByteArray())
                } else hex(value)
                else -> hex(value)
            }
            params += label + "=" + rendered
            cursor += valueLength
        }
        return (
            priority.toString() + " " + target.first +
                if (params.isEmpty()) "" else " " + params.joinToString(" ")
            ).take(1536)
    }

    private fun decodeAlpn(value: ByteArray): String {
        val parts = mutableListOf<String>()
        var cursor = 0
        while (cursor < value.size && parts.size < 16) {
            val size = value[cursor].toInt() and 0xFF
            cursor++
            if (size <= 0 || cursor + size > value.size) break
            parts += String(value, cursor, size, Charsets.US_ASCII).filter { !it.isISOControl() }
            cursor += size
        }
        return parts.joinToString(",")
    }

    private fun hex(value: ByteArray): String =
        value.take(64).joinToString("") { "%02x".format(it.toInt() and 0xFF) } +
            if (value.size > 64) "…" else ""


    /** Reads a possibly compressed name, returning the text and the offset after it. */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var offset = start
        var offsetAfter = -1
        var hops = 0
        var totalLength = 0

        while (true) {
            if (offset < 0 || offset >= data.size) return null
            val length = data[offset].toInt() and 0xFF
            when {
                length == 0 -> {
                    offset++
                    if (offsetAfter < 0) offsetAfter = offset
                    return (if (labels.isEmpty()) "." else labels.joinToString(".")) to offsetAfter
                }
                length and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= data.size) return null
                    if (++hops > MAX_POINTER_HOPS) return null
                    val pointer = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (offsetAfter < 0) offsetAfter = offset + 2
                    offset = pointer
                }
                else -> {
                    if (offset + 1 + length > data.size) return null
                    totalLength += length + 1
                    if (totalLength > MAX_NAME_LENGTH) return null
                    labels += String(data, offset + 1, length, Charsets.UTF_8)
                        .filter { !it.isISOControl() }
                    offset += 1 + length
                }
            }
        }
    }

    private fun skipName(data: ByteArray, start: Int): Int? = readName(data, start)?.second

    private fun readUnsignedShort(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUnsignedInt(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }
}
