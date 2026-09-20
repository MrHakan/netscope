package com.netscope.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.netscope.core.model.CsvExport
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.Evidence
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.ScanMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    JSON("JSON", "json", "application/json"),
    CSV("CSV", "csv", "text/csv"),
}

/**
 * Writes scan results to a file the user can share.
 *
 * Every exported property carries the source and confidence that produced it, so an
 * export is as auditable as the screen it came from. Nothing leaves the device unless
 * the user picks a destination in the system share sheet.
 */
@Singleton
class ScanExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun export(
        format: ExportFormat,
        devices: List<DiscoveredDevice>,
        network: NetworkSnapshot?,
        metadata: ScanMetadata?,
    ): Intent? = withContext(Dispatchers.IO) {
        if (devices.isEmpty()) return@withContext null
        val content = when (format) {
            ExportFormat.JSON -> buildJson(devices, network, metadata)
            ExportFormat.CSV -> buildCsv(devices)
        }

        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        // One file per export, named by timestamp so successive exports do not collide.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "netscope-scan-$stamp.${format.extension}")
        file.writeText(content)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NetScope scan ${metadata?.target.orEmpty()}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildJson(
        devices: List<DiscoveredDevice>,
        network: NetworkSnapshot?,
        metadata: ScanMetadata?,
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)

        root.put(
            "network",
            JSONObject().apply {
                put("interface", network?.interfaceName?.value ?: JSONObject.NULL)
                put("transport", network?.transportLabel ?: JSONObject.NULL)
                put("ipv4", network?.primaryIpv4?.toCanonicalString() ?: JSONObject.NULL)
                put("cidr", network?.primaryIpv4Cidr?.toString() ?: JSONObject.NULL)
                put("gateway", network?.ipv4Gateway?.toCanonicalString() ?: JSONObject.NULL)
                put("validatedInternet", network?.hasValidatedInternet?.value ?: JSONObject.NULL)
            },
        )

        root.put(
            "interfaces",
            JSONArray().apply {
                network?.ipv4Addresses?.forEach { (address, prefix) ->
                    put(
                        JSONObject().apply {
                            put("address", address.toCanonicalString())
                            put("prefixLength", prefix)
                            put("family", "IPv4")
                            put("source", "LINK_PROPERTIES")
                        },
                    )
                }
                network?.ipv6Addresses?.forEach { (address, prefix) ->
                    put(
                        JSONObject().apply {
                            put("address", address.toCanonicalString())
                            put("prefixLength", prefix)
                            put("family", "IPv6")
                            put("scope", address.scope.name)
                            put("source", "LINK_PROPERTIES")
                        },
                    )
                }
            },
        )

        root.put(
            "routes",
            JSONArray().apply {
                network?.routes?.forEach { route ->
                    put(
                        JSONObject().apply {
                            put("destination", route.destinationText)
                            put("gateway", route.gateway?.toCanonicalString() ?: JSONObject.NULL)
                            put("interface", route.interfaceName ?: JSONObject.NULL)
                            put("isDefaultRoute", route.isDefaultRoute)
                            put("type", route.kind.name)
                            put("source", route.origin.name)
                        },
                    )
                }
            },
        )

        root.put(
            "subnets",
            JSONArray().apply {
                network?.primaryIpv4Cidr?.let {
                    put(
                        JSONObject().apply {
                            put("cidr", it.toString())
                            put("relation", "CONNECTED")
                            put("usableHosts", it.usableHostCount)
                        },
                    )
                }
            },
        )

        val services = JSONArray()
        root.put(
            "devices",
            JSONArray().apply {
                devices.forEach { device ->
                    put(
                        JSONObject().apply {
                            put("key", device.key)
                            put("ipv4", device.ipv4?.toCanonicalString() ?: JSONObject.NULL)
                            put("state", device.state.name)
                            put("latencyMillis", device.latencyMillis ?: JSONObject.NULL)
                            put("probeType", device.probeType?.name ?: JSONObject.NULL)
                            put("userLabel", device.userLabel ?: JSONObject.NULL)
                            put("hostname", evidenceJson(device.hostname) { it })
                            put("advertisedName", evidenceJson(device.friendlyName) { it })
                            put("mac", evidenceJson(device.mac) { it.toString() })
                            put("vendor", evidenceJson(device.vendor) { it })
                            put("deviceType", evidenceJson(device.deviceType) { it.name })
                            put(
                                "discoverySources",
                                JSONArray().apply { device.discoverySources.forEach { put(it.name) } },
                            )
                            put("firstSeenEpochMillis", device.firstSeenEpochMillis)
                            put("lastSeenEpochMillis", device.lastSeenEpochMillis)
                        },
                    )
                    device.services.forEach { service ->
                        services.put(
                            JSONObject().apply {
                                put("device", device.key)
                                put("name", service.name)
                                put("port", service.port ?: JSONObject.NULL)
                                put("protocol", service.protocol)
                                put("serviceType", service.serviceType ?: JSONObject.NULL)
                                put("source", service.source.name)
                                put("confidence", service.confidence.name)
                            },
                        )
                    }
                }
            },
        )
        root.put("services", services)

        root.put(
            "scanMetadata",
            JSONObject().apply {
                put("target", metadata?.target ?: JSONObject.NULL)
                put("profile", metadata?.profile?.name ?: JSONObject.NULL)
                put("performanceProfile", metadata?.performanceProfile?.name ?: JSONObject.NULL)
                put("startedAtEpochMillis", metadata?.startedAtEpochMillis ?: JSONObject.NULL)
                put("durationMillis", metadata?.durationMillis ?: JSONObject.NULL)
                put("addressesProbed", metadata?.addressesProbed ?: JSONObject.NULL)
                put("devicesFound", metadata?.devicesFound ?: JSONObject.NULL)
                put("peakConcurrency", metadata?.peakConcurrency ?: JSONObject.NULL)
                put("icmpAvailable", metadata?.icmpAvailable ?: JSONObject.NULL)
                put("completed", metadata?.completed ?: JSONObject.NULL)
                put("isDemoData", metadata?.isDemoData ?: false)
            },
        )

        return root.toString(2)
    }

    /**
     * Serialises one property with its provenance.
     *
     * An absent value exports the reason it is absent rather than null on its own, so a
     * consumer can tell "we looked and found nothing" from "Android would not say".
     */
    private fun <T> evidenceJson(evidence: Evidence<T>, render: (T) -> String): JSONObject =
        JSONObject().apply {
            put("value", evidence.value?.let(render) ?: JSONObject.NULL)
            put("source", evidence.source.name)
            put("confidence", evidence.confidence.name)
            put("inferred", evidence.isInferred)
            put("unavailability", evidence.unavailability?.name ?: JSONObject.NULL)
            put("observedAtEpochMillis", evidence.observedAtEpochMillis)
        }

    private fun buildCsv(devices: List<DiscoveredDevice>): String =
        CsvExport.render(devices)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val EXPORT_DIRECTORY = "exports"
    }
}
