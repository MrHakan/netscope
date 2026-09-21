package com.netscope.core.network

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class SshHostKeyInfo(
    val host: String,
    val port: Int,
    val algorithm: String,
    val keyBase64: String,
    val sha256Fingerprint: String,
)

data class SshSystemSnapshot(
    val host: String,
    val port: Int,
    val username: String,
    val hostKeyFingerprint: String,
    val operatingSystem: String?,
    val uptime: String?,
    val loadAverage: String?,
    val memory: String?,
    val disks: List<String>,
    val rawOutput: String,
)

/**
 * Fixed-command, read-only SSH monitoring.
 *
 * The initial key scan deliberately advertises only the SSH "none" authentication
 * method. No password is sent while obtaining the host key. A real session is created
 * only after the caller supplies the exact key returned by that scan, and the custom
 * HostKeyRepository rejects any mismatch.
 */
@Singleton
class SshReadOnlyMonitor @Inject constructor() {

    suspend fun scanHostKey(
        host: String,
        port: Int = 22,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): SshHostKeyInfo = withContext(Dispatchers.IO) {
        validateHost(host, port)
        val jsch = JSch()
        val session = jsch.getSession(KEYSCAN_USERNAME, host, port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "none")
        session.timeout = timeoutMillis
        try {
            runCatching { session.connect(timeoutMillis) }
            val key = session.hostKey
                ?: error("SSH handshake did not expose a server host key.")
            toInfo(host, port, key)
        } finally {
            if (session.isConnected) session.disconnect()
        }
    }

    suspend fun snapshot(
        host: String,
        port: Int,
        username: String,
        password: String,
        trustedHostKey: SshHostKeyInfo,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): SshSystemSnapshot = withContext(Dispatchers.IO) {
        validateHost(host, port)
        require(username.isNotBlank()) { "SSH username is required." }
        require(password.isNotEmpty()) { "SSH password is required." }
        require(
            trustedHostKey.host == host &&
                trustedHostKey.port == port
        ) { "The trusted host key belongs to a different endpoint." }

        val jsch = JSch()
        jsch.hostKeyRepository = SingleHostKeyRepository(trustedHostKey)
        val session = jsch.getSession(username.take(128), host, port)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.timeout = timeoutMillis
        try {
            session.connect(timeoutMillis)
            val actual = session.hostKey ?: error("SSH server host key unavailable after connect.")
            val actualInfo = toInfo(host, port, actual)
            require(actualInfo.keyBase64 == trustedHostKey.keyBase64) {
                "SSH host key changed after trust confirmation."
            }

            val output = executeReadOnly(session, timeoutMillis)
            parseSnapshot(host, port, username, trustedHostKey.sha256Fingerprint, output)
        } finally {
            if (session.isConnected) session.disconnect()
        }
    }

    private fun executeReadOnly(
        session: com.jcraft.jsch.Session,
        timeoutMillis: Int,
    ): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(READ_ONLY_COMMAND)
        channel.setInputStream(null)
        channel.setErrStream(null)
        val stdout = channel.inputStream
        val stderr = channel.errStream
        return try {
            channel.connect(timeoutMillis)
            val deadline = System.currentTimeMillis() + timeoutMillis
            val out = java.io.ByteArrayOutputStream()
            val err = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                while (stdout.available() > 0 && out.size() < MAX_OUTPUT_BYTES) {
                    val count = stdout.read(
                        buffer,
                        0,
                        minOf(buffer.size, MAX_OUTPUT_BYTES - out.size()),
                    )
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                while (stderr.available() > 0 && err.size() < MAX_ERROR_BYTES) {
                    val count = stderr.read(
                        buffer,
                        0,
                        minOf(buffer.size, MAX_ERROR_BYTES - err.size()),
                    )
                    if (count < 0) break
                    err.write(buffer, 0, count)
                }
                if (channel.isClosed && stdout.available() == 0 && stderr.available() == 0) break
                Thread.sleep(40)
            }
            require(channel.isClosed) { "SSH command timed out." }
            val combined = buildString {
                append(out.toString(Charsets.UTF_8.name()))
                if (err.size() > 0) {
                    append("\n[stderr]\n")
                    append(err.toString(Charsets.UTF_8.name()))
                }
            }
            sanitize(combined, MAX_OUTPUT_CHARS)
        } finally {
            channel.disconnect()
        }
    }

    private fun parseSnapshot(
        host: String,
        port: Int,
        username: String,
        fingerprint: String,
        raw: String,
    ): SshSystemSnapshot {
        val sections = parseSections(raw)
        return SshSystemSnapshot(
            host = host,
            port = port,
            username = username,
            hostKeyFingerprint = fingerprint,
            operatingSystem = sections["OS"]?.firstOrNull(),
            uptime = sections["UPTIME"]?.firstOrNull(),
            loadAverage = sections["LOAD"]?.firstOrNull(),
            memory = sections["MEM"]?.joinToString(" · "),
            disks = sections["DISK"].orEmpty().take(32),
            rawOutput = raw,
        )
    }

    private fun parseSections(raw: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (line in raw.lineSequence().take(256)) {
            val clean = line.trim()
            if (clean.startsWith("__NETSCOPE_") && clean.endsWith("__")) {
                current = clean
                    .removePrefix("__NETSCOPE_")
                    .removeSuffix("__")
                    .take(32)
                result.getOrPut(current) { mutableListOf() }
            } else if (current != null && clean.isNotBlank() && clean != "[stderr]") {
                result.getOrPut(current) { mutableListOf() }.add(clean.take(1024))
            }
        }
        return result
    }

    private fun toInfo(host: String, port: Int, key: HostKey): SshHostKeyInfo {
        val raw = key.key
        return SshHostKeyInfo(
            host = host,
            port = port,
            algorithm = key.type,
            keyBase64 = Base64.getEncoder().encodeToString(raw),
            sha256Fingerprint = sha256Fingerprint(raw),
        )
    }

    private fun sha256Fingerprint(raw: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun validateHost(host: String, port: Int) {
        require(host.isNotBlank() && !host.contains('\n') && !host.contains('\r')) {
            "Invalid SSH host."
        }
        require(port in 1..65535) { "SSH port must be 1-65535." }
    }

    private fun sanitize(text: String, maxChars: Int): String =
        text.asSequence()
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .joinToString("")
            .take(maxChars)

    private class SingleHostKeyRepository(
        private val expected: SshHostKeyInfo,
    ) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            val actual = Base64.getEncoder().encodeToString(key)
            return if (actual == expected.keyBase64) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: com.jcraft.jsch.UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

        override fun getKnownHostsRepositoryID(): String = "NetScope in-memory trusted host key"

        override fun getHostKey(): Array<HostKey> =
            arrayOf(HostKey(expected.host, expected.algorithm, Base64.getDecoder().decode(expected.keyBase64)))

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = getHostKey()
    }

    companion object {
        private const val KEYSCAN_USERNAME = "netscope-keyscan"
        private const val DEFAULT_TIMEOUT_MILLIS = 8_000
        private const val MAX_OUTPUT_BYTES = 64 * 1024
        private const val MAX_ERROR_BYTES = 8 * 1024
        private const val MAX_OUTPUT_CHARS = 70 * 1024

        // Fixed POSIX read-only commands only. There is no user-supplied shell command.
        private const val READ_ONLY_COMMAND =
            "printf '__NETSCOPE_OS__\\n'; uname -srm 2>/dev/null || true; " +
            "printf '__NETSCOPE_UPTIME__\\n'; uptime 2>/dev/null || true; " +
            "printf '__NETSCOPE_LOAD__\\n'; cat /proc/loadavg 2>/dev/null || true; " +
            "printf '__NETSCOPE_MEM__\\n'; " +
            "awk '/^(MemTotal|MemAvailable):/ {print $1 \\$2 \\$3}' /proc/meminfo 2>/dev/null || true; " +
            "printf '__NETSCOPE_DISK__\\n'; df -Pk 2>/dev/null | head -n 32 || true"
    }
}
