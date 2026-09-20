package com.netscope.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

enum class FtpEntryType { FILE, DIRECTORY, OTHER }

data class FtpEntry(
    val name: String,
    val type: FtpEntryType,
    val sizeBytes: Long?,
    val raw: String,
)

data class FtpBrowseResult(
    val workingDirectory: String,
    val entries: List<FtpEntry>,
    val serverGreeting: String,
)

/**
 * Small read-only FTP browser used by Service Explorer.
 *
 * Credentials are supplied for one operation and are never persisted. Only USER, PASS,
 * TYPE, PWD, CWD, EPSV/PASV, MLSD/LIST and QUIT are issued. No upload, delete, rename,
 * chmod or other mutating command exists in this client.
 */
@Singleton
class FtpBrowserService @Inject constructor() {

    suspend fun list(
        host: String,
        port: Int,
        username: String,
        password: String,
        path: String = "/",
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): FtpBrowseResult = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Invalid FTP port." }
        require(!host.contains('\r') && !host.contains('\n')) { "Invalid host." }
        require(!username.contains('\r') && !username.contains('\n')) { "Invalid username." }
        require(!password.contains('\r') && !password.contains('\n')) { "Invalid password." }
        require(!path.contains('\r') && !path.contains('\n')) { "Invalid path." }

        Socket().use { control ->
            control.connect(InetSocketAddress(host, port), timeoutMillis)
            control.soTimeout = timeoutMillis
            val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.ISO_8859_1))
            val writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), Charsets.ISO_8859_1))

            val greeting = readReply(reader)
            require(greeting.code in 200..399) { "FTP server rejected the connection: ${greeting.message}" }

            val userReply = command(writer, reader, "USER $username")
            when (userReply.code) {
                230, 202 -> Unit
                331, 332 -> {
                    val passReply = command(writer, reader, "PASS $password")
                    require(passReply.code in listOf(202, 230)) {
                        "FTP login failed: ${passReply.message}"
                    }
                }
                else -> error("FTP login failed: ${userReply.message}")
            }

            command(writer, reader, "TYPE I")
            if (path.isNotBlank() && path != "/") {
                val cwd = command(writer, reader, "CWD $path")
                require(cwd.code in 200..299) { "Cannot open $path: ${cwd.message}" }
            }

            val pwdReply = command(writer, reader, "PWD")
            val workingDirectory = parsePwd(pwdReply.message) ?: path.ifBlank { "/" }

            val entries = listDirectory(
                controlHost = host,
                writer = writer,
                reader = reader,
                timeoutMillis = timeoutMillis,
            )

            runCatching { command(writer, reader, "QUIT") }

            FtpBrowseResult(
                workingDirectory = sanitize(workingDirectory, MAX_PATH_CHARS),
                entries = entries.sortedWith(compareBy<FtpEntry>({ it.type != FtpEntryType.DIRECTORY }, { it.name.lowercase() })),
                serverGreeting = sanitize(greeting.message, MAX_MESSAGE_CHARS),
            )
        }
    }

    private fun listDirectory(
        controlHost: String,
        writer: BufferedWriter,
        reader: BufferedReader,
        timeoutMillis: Int,
    ): List<FtpEntry> {
        val mlsd = runCatching {
            transferLines(controlHost, writer, reader, timeoutMillis, "MLSD")
        }.getOrNull()

        if (mlsd != null) {
            return mlsd.mapNotNull(::parseMlsdLine)
        }

        val lines = transferLines(controlHost, writer, reader, timeoutMillis, "LIST")
        return lines.mapNotNull(::parseListLine)
    }

    private fun transferLines(
        controlHost: String,
        writer: BufferedWriter,
        reader: BufferedReader,
        timeoutMillis: Int,
        commandName: String,
    ): List<String> {
        val dataPort = enterPassiveMode(controlHost, writer, reader)
        Socket().use { dataSocket ->
            dataSocket.connect(InetSocketAddress(controlHost, dataPort), timeoutMillis)
            dataSocket.soTimeout = timeoutMillis

            val preliminary = command(writer, reader, commandName)
            require(preliminary.code == 125 || preliminary.code == 150) {
                "$commandName is not available: ${preliminary.message}"
            }

            val dataReader = BufferedReader(InputStreamReader(dataSocket.getInputStream(), Charsets.UTF_8))
            val lines = mutableListOf<String>()
            var totalChars = 0
            while (lines.size < MAX_ENTRIES && totalChars < MAX_LISTING_CHARS) {
                val line = readBoundedLine(dataReader, MAX_LINE_CHARS) ?: break
                totalChars += line.length
                lines += sanitize(line, MAX_LINE_CHARS)
            }

            val completion = readReply(reader)
            require(completion.code in listOf(226, 250)) {
                "FTP data transfer did not complete normally: ${completion.message}"
            }
            return lines
        }
    }

    /**
     * EPSV is preferred. PASV is a fallback, but the server-provided IP is deliberately
     * ignored and only its port is used. This prevents a malicious FTP server from
     * turning the client into a connection oracle for an unrelated host.
     */
    private fun enterPassiveMode(
        controlHost: String,
        writer: BufferedWriter,
        reader: BufferedReader,
    ): Int {
        val epsv = command(writer, reader, "EPSV")
        if (epsv.code == 229) {
            EPSV_PORT.find(epsv.message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { port ->
                if (port in 1..65535) return port
            }
        }

        val pasv = command(writer, reader, "PASV")
        require(pasv.code == 227) { "FTP server does not support passive mode: ${pasv.message}" }
        val match = PASV_PORT.find(pasv.message)
            ?: error("Could not parse the server's passive-mode response.")
        val p1 = match.groupValues[1].toIntOrNull() ?: error("Invalid PASV response.")
        val p2 = match.groupValues[2].toIntOrNull() ?: error("Invalid PASV response.")
        val port = p1 * 256 + p2
        require(port in 1..65535) { "Invalid passive data port." }
        @Suppress("UNUSED_VARIABLE")
        val sameHost = controlHost
        return port
    }

    private data class Reply(val code: Int, val message: String)

    private fun command(
        writer: BufferedWriter,
        reader: BufferedReader,
        command: String,
    ): Reply {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        return readReply(reader)
    }

    private fun readReply(reader: BufferedReader): Reply {
        val first = readBoundedLine(reader, MAX_LINE_CHARS) ?: error("FTP connection closed unexpectedly.")
        val code = first.take(3).toIntOrNull() ?: error("Malformed FTP reply.")
        val lines = mutableListOf(first)

        if (first.length >= 4 && first[3] == '-') {
            val terminator = "$code "
            while (lines.size < MAX_REPLY_LINES) {
                val line = readBoundedLine(reader, MAX_LINE_CHARS) ?: break
                lines += line
                if (line.startsWith(terminator)) break
            }
        }

        return Reply(code, sanitize(lines.joinToString(" | "), MAX_MESSAGE_CHARS))
    }

    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val builder = StringBuilder()
        var sawAny = false
        while (true) {
            val value = reader.read()
            if (value == -1) return if (sawAny) builder.toString() else null
            sawAny = true
            val ch = value.toChar()
            if (ch == '\n') return builder.toString()
            if (ch != '\r' && builder.length < maxChars) builder.append(ch)
        }
    }

    private fun parseMlsdLine(raw: String): FtpEntry? {
        val separator = raw.indexOf(' ')
        if (separator <= 0 || separator >= raw.lastIndex) return null
        val factText = raw.substring(0, separator)
        val name = sanitize(raw.substring(separator + 1).trim(), MAX_NAME_CHARS)
        if (name.isBlank() || name == "." || name == "..") return null

        val facts = factText.split(';')
            .mapNotNull { fact ->
                val equals = fact.indexOf('=')
                if (equals <= 0) null else fact.substring(0, equals).lowercase() to fact.substring(equals + 1)
            }
            .toMap()

        val type = when (facts["type"]?.lowercase()) {
            "dir", "cdir", "pdir" -> FtpEntryType.DIRECTORY
            "file" -> FtpEntryType.FILE
            else -> FtpEntryType.OTHER
        }

        return FtpEntry(
            name = name,
            type = type,
            sizeBytes = facts["size"]?.toLongOrNull(),
            raw = sanitize(raw, MAX_LINE_CHARS),
        )
    }

    private fun parseListLine(raw: String): FtpEntry? {
        val clean = sanitize(raw, MAX_LINE_CHARS).trim()
        if (clean.isBlank()) return null

        val parts = clean.split(Regex("\\s+"), limit = 9)
        if (parts.size >= 9 && parts[0].isNotEmpty()) {
            val type = when (parts[0].first()) {
                'd' -> FtpEntryType.DIRECTORY
                '-' -> FtpEntryType.FILE
                else -> FtpEntryType.OTHER
            }
            return FtpEntry(
                name = sanitize(parts[8], MAX_NAME_CHARS),
                type = type,
                sizeBytes = parts.getOrNull(4)?.toLongOrNull(),
                raw = clean,
            )
        }

        return FtpEntry(clean.take(MAX_NAME_CHARS), FtpEntryType.OTHER, null, clean)
    }

    private fun parsePwd(message: String): String? =
        Regex("\"([^\"]*)\"").find(message)?.groupValues?.getOrNull(1)

    private fun sanitize(value: String, limit: Int): String =
        value.filter { it == ' ' || it == '\t' || !it.isISOControl() }.take(limit)

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 5_000
        private const val MAX_LINE_CHARS = 4_096
        private const val MAX_MESSAGE_CHARS = 8_192
        private const val MAX_REPLY_LINES = 64
        private const val MAX_LISTING_CHARS = 256 * 1024
        private const val MAX_ENTRIES = 2_000
        private const val MAX_NAME_CHARS = 512
        private const val MAX_PATH_CHARS = 2_048

        private val EPSV_PORT = Regex("\\(\\|\\|\\|(\\d+)\\|\\)")
        private val PASV_PORT = Regex("(?:\\d+,){4}(\\d+),(\\d+)")
    }
}
