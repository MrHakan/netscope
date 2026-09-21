package com.netscope.core.network

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

data class SpeedTestProgress(
    val phase: String,
    val transferredBytes: Long,
    val totalBytes: Long,
    val mbps: Double?,
)

data class SpeedTestResult(
    val latencyMillis: Double,
    val jitterMillis: Double?,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val downloadedBytes: Long,
    val uploadedBytes: Long,
    val testedAtEpochMillis: Long,
    val endpoint: String = "speed.cloudflare.com",
)

data class PublicNetworkIdentity(
    val ip: String?,
    val asn: String?,
    val organization: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val colo: String?,
    val source: String,
)

data class IpGeoResult(
    val query: String,
    val ip: String?,
    val success: Boolean,
    val country: String?,
    val region: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String?,
    val asn: String?,
    val organization: String?,
    val source: String,
    val error: String? = null,
)

data class RdapResult(
    val query: String,
    val kind: String,
    val handle: String?,
    val name: String?,
    val startAddress: String?,
    val endAddress: String?,
    val country: String?,
    val status: List<String>,
    val entities: List<String>,
    val events: List<String>,
    val links: List<String>,
    val source: String,
    val rawSummary: String?,
    val error: String? = null,
)

/**
 * User-initiated public internet diagnostics.
 *
 * Nothing here runs in the background. Every endpoint is fixed and shown in the UI.
 * Responses are capped before parsing so a remote server cannot allocate arbitrary memory.
 */
@Singleton
class InternetDiagnostics @Inject constructor() {

    suspend fun speedTest(
        onProgress: suspend (SpeedTestProgress) -> Unit = {},
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        val latencySamples = mutableListOf<Double>()
        repeat(LATENCY_SAMPLES) {
            coroutineContext.ensureActive()
            val started = SystemClock.elapsedRealtimeNanos()
            val connection = open(CLOUDFLARE_BASE + "/__down?bytes=" + LATENCY_BYTES)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) >= 0) Unit
                }
                latencySamples += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            } finally {
                connection.disconnect()
            }
        }

        val download = transferDownload(DOWNLOAD_BYTES, onProgress)
        val upload = transferUpload(UPLOAD_BYTES, onProgress)
        val jitter = if (latencySamples.size >= 2) {
            latencySamples.zipWithNext { a, b -> abs(b - a) }.average()
        } else null

        SpeedTestResult(
            latencyMillis = latencySamples.average().takeIf { !it.isNaN() } ?: 0.0,
            jitterMillis = jitter,
            downloadMbps = download.second,
            uploadMbps = upload.second,
            downloadedBytes = download.first,
            uploadedBytes = upload.first,
            testedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    suspend fun currentIdentity(): PublicNetworkIdentity = withContext(Dispatchers.IO) {
        val json = JSONObject(getText(CLOUDFLARE_BASE + "/meta", MAX_JSON_BYTES))
        PublicNetworkIdentity(
            ip = json.stringOrNull("clientIp"),
            asn = json.stringOrNull("asn"),
            organization = json.stringOrNull("asOrganization"),
            city = json.stringOrNull("city"),
            region = json.stringOrNull("region"),
            country = json.stringOrNull("country"),
            colo = json.stringOrNull("colo"),
            source = CLOUDFLARE_BASE + "/meta",
        )
    }

    suspend fun geolocate(target: String): IpGeoResult = withContext(Dispatchers.IO) {
        val resolved = runCatching { InetAddress.getByName(target) }.getOrNull()
            ?: return@withContext IpGeoResult(
                target, null, false, null, null, null, null, null, null, null, null,
                GEO_SOURCE, "The target could not be resolved.",
            )

        if (resolved.isAnyLocalAddress || resolved.isLoopbackAddress ||
            resolved.isLinkLocalAddress || resolved.isSiteLocalAddress || resolved.isMulticastAddress
        ) {
            return@withContext IpGeoResult(
                target, resolved.hostAddress, false, null, null, null, null, null, null, null, null,
                GEO_SOURCE, "Geolocation is only meaningful for public IP addresses.",
            )
        }

        val ip = resolved.hostAddress ?: target
        val json = JSONObject(getText(GEO_SOURCE + "/" + encodePath(ip), MAX_JSON_BYTES))
        val connection = json.optJSONObject("connection")
        val timezone = json.optJSONObject("timezone")
        IpGeoResult(
            query = target,
            ip = json.stringOrNull("ip") ?: ip,
            success = json.optBoolean("success", true),
            country = json.stringOrNull("country"),
            region = json.stringOrNull("region"),
            city = json.stringOrNull("city"),
            latitude = json.doubleOrNull("latitude"),
            longitude = json.doubleOrNull("longitude"),
            timezone = timezone?.stringOrNull("id"),
            asn = connection?.stringOrNull("asn"),
            organization = connection?.stringOrNull("org"),
            source = GEO_SOURCE,
            error = json.stringOrNull("message"),
        )
    }

    suspend fun rdap(query: String): RdapResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext RdapResult(
                trimmed, "unknown", null, null, null, null, null,
                emptyList(), emptyList(), emptyList(), emptyList(),
                RDAP_BASE, null, "Enter an IP address, domain, or AS number.",
            )
        }

        val kind: String
        val url: String
        if (trimmed.matches(Regex("(?i)^AS\\d+$"))) {
            kind = "autnum"
            url = RDAP_BASE + "/autnum/" + trimmed.drop(2)
        } else {
            val literal = runCatching { InetAddress.getByName(trimmed) }.getOrNull()
            if (literal != null && (trimmed.contains(':') || literal.hostAddress == trimmed)) {
                kind = "ip"
                url = RDAP_BASE + "/ip/" + encodePath(trimmed)
            } else {
                kind = "domain"
                url = RDAP_BASE + "/domain/" + encodePath(trimmed.removeSuffix("."))
            }
        }

        var finalUrl = url
        val json = JSONObject(getText(url, MAX_RDAP_BYTES) { finalUrl = it })
        RdapResult(
            query = trimmed,
            kind = kind,
            handle = json.stringOrNull("handle"),
            name = json.stringOrNull("name") ?: json.stringOrNull("ldhName"),
            startAddress = json.stringOrNull("startAddress"),
            endAddress = json.stringOrNull("endAddress"),
            country = json.stringOrNull("country"),
            status = json.optJSONArray("status").strings(MAX_LIST_ITEMS),
            entities = parseEntities(json.optJSONArray("entities")),
            events = parseEvents(json.optJSONArray("events")),
            links = parseLinks(json.optJSONArray("links")),
            source = finalUrl,
            rawSummary = listOfNotNull(
                json.stringOrNull("type")?.let { "type=" + it },
                json.stringOrNull("port43")?.let { "port43=" + it },
                json.stringOrNull("parentHandle")?.let { "parent=" + it },
            ).joinToString(" · ").ifBlank { null },
        )
    }

    private suspend fun transferDownload(
        bytes: Long,
        onProgress: suspend (SpeedTestProgress) -> Unit,
    ): Pair<Long, Double> {
        val connection = open(CLOUDFLARE_BASE + "/__down?bytes=" + bytes)
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = SPEED_READ_TIMEOUT_MILLIS
        val start = SystemClock.elapsedRealtimeNanos()
        var transferred = 0L
        var lastReport = 0L
        try {
            BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    transferred += count
                    if (transferred - lastReport >= PROGRESS_GRANULARITY) {
                        lastReport = transferred
                        val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
                        onProgress(SpeedTestProgress("Download", transferred, bytes, mbps(transferred, seconds)))
                    }
                    if (transferred >= bytes) break
                }
            }
        } finally {
            connection.disconnect()
        }
        val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
        return transferred to mbps(transferred, seconds)
    }

    private suspend fun transferUpload(
        bytes: Long,
        onProgress: suspend (SpeedTestProgress) -> Unit,
    ): Pair<Long, Double> {
        val connection = open(CLOUDFLARE_BASE + "/__up")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = SPEED_READ_TIMEOUT_MILLIS
        val start = SystemClock.elapsedRealtimeNanos()
        var transferred = 0L
        var lastReport = 0L
        val chunk = ByteArray(64 * 1024)
        try {
            BufferedOutputStream(connection.outputStream, chunk.size).use { output ->
                while (transferred < bytes) {
                    coroutineContext.ensureActive()
                    val count = minOf(chunk.size.toLong(), bytes - transferred).toInt()
                    output.write(chunk, 0, count)
                    transferred += count
                    if (transferred - lastReport >= PROGRESS_GRANULARITY) {
                        lastReport = transferred
                        val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
                        onProgress(SpeedTestProgress("Upload", transferred, bytes, mbps(transferred, seconds)))
                    }
                }
                output.flush()
            }
            connection.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (input.read(buffer) >= 0) Unit
            }
        } finally {
            connection.disconnect()
        }
        val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
        return transferred to mbps(transferred, seconds)
    }

    private fun getText(
        url: String,
        maxBytes: Int,
        onFinalUrl: ((String) -> Unit)? = null,
    ): String {
        val connection = open(url)
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP " + code + " from " + connection.url.host + "." }
            onFinalUrl?.invoke(connection.url.toString())
            val data = ByteArray(maxBytes + 1)
            var total = 0
            BufferedInputStream(connection.inputStream).use { input ->
                while (total < data.size) {
                    val count = input.read(data, total, data.size - total)
                    if (count < 0) break
                    total += count
                }
            }
            require(total <= maxBytes) { "Response exceeded the safety limit." }
            return String(data, 0, total, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "NetScope/Android")
        }

    private fun parseEntities(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until minOf(array.length(), MAX_LIST_ITEMS)) {
            val item = array.optJSONObject(i) ?: continue
            val handle = item.stringOrNull("handle")
            val roles = item.optJSONArray("roles").strings(6)
            val value = listOfNotNull(
                handle,
                roles.takeIf { it.isNotEmpty() }?.joinToString("/"),
            ).joinToString(" · ")
            if (value.isNotBlank()) result += sanitize(value, 256)
        }
        return result
    }

    private fun parseEvents(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until minOf(array.length(), MAX_LIST_ITEMS)) {
                val item = array.optJSONObject(i) ?: continue
                val action = item.stringOrNull("eventAction") ?: continue
                val date = item.stringOrNull("eventDate")
                add(sanitize(action + (date?.let { " · " + it } ?: ""), 256))
            }
        }
    }

    private fun parseLinks(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until minOf(array.length(), MAX_LIST_ITEMS)) {
                val href = array.optJSONObject(i)?.stringOrNull("href") ?: continue
                if (href.startsWith("https://") || href.startsWith("http://")) add(sanitize(href, 512))
            }
        }
    }

    private fun JSONArray?.strings(limit: Int): List<String> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), limit)).mapNotNull {
            optString(it, null)?.let { value -> sanitize(value, 256) }
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key, "").trim().takeIf { it.isNotEmpty() }?.let { sanitize(it, 512) }

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

    private fun sanitize(value: String, limit: Int): String =
        value.filter { it == ' ' || it == '\t' || !it.isISOControl() }.take(limit)

    private fun encodePath(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun mbps(bytes: Long, seconds: Double): Double =
        if (seconds <= 0.0) 0.0 else (bytes * 8.0 / seconds) / 1_000_000.0

    companion object {
        const val CLOUDFLARE_BASE = "https://speed.cloudflare.com"
        const val GEO_SOURCE = "https://ipwho.is"
        const val RDAP_BASE = "https://rdap.org"

        private const val CONNECT_TIMEOUT_MILLIS = 7_000
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val SPEED_READ_TIMEOUT_MILLIS = 20_000
        private const val LATENCY_SAMPLES = 4
        private const val LATENCY_BYTES = 1_000
        private const val DOWNLOAD_BYTES = 10_000_000L
        private const val UPLOAD_BYTES = 5_000_000L
        private const val PROGRESS_GRANULARITY = 512 * 1024L
        private const val MAX_JSON_BYTES = 256 * 1024
        private const val MAX_RDAP_BYTES = 512 * 1024
        private const val MAX_LIST_ITEMS = 24
    }
}
