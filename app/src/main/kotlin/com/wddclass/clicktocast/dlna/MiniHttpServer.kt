package com.wddclass.clicktocast.dlna

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String,
    val query: String = "",
    val protocol: String = "HTTP/1.1",
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun toBytes(protocol: String = "HTTP/1.1"): ByteArray {
        val wire = if (protocol.isBlank()) "HTTP/1.1" else protocol
        val rtsp = wire.startsWith("RTSP", ignoreCase = true)
        val merged = linkedMapOf<String, String>()
        headers.forEach { (key, value) -> merged[key] = value }
        if (merged.keys.none { it.equals("Content-Length", true) }) {
            merged["Content-Length"] = body.size.toString()
        }
        if (!rtsp && status != 101 && merged.keys.none { it.equals("Connection", true) }) {
            merged["Connection"] = "close"
        }
        if (merged.keys.none { it.equals("Server", true) }) {
            merged["Server"] = if (rtsp) "AirTunes/220.68" else "ClickToCast UPnP/1.0"
        }
        val head = buildString {
            append(wire).append(' ').append(status).append(' ').append(reason).append("\r\n")
            for ((key, value) in merged) {
                append(key).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return head + body
    }
}

/** Small HTTP/1.1 server. One request per connection, enough for UPnP control and events. */
class MiniHttpServer(
    private val handler: (HttpRequest) -> HttpResponse,
) {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "dmr-http").apply {
            isDaemon = true
            // Android kills the process on any thread's uncaught exception.
            // Swallow leftovers so one bad connection cannot take down the TV app.
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                DmrLog.i("http worker died: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    @Volatile
    var boundPort: Int = -1
        private set

    fun start(preferredPort: Int): Int {
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress("0.0.0.0", preferredPort))
        } catch (error: IOException) {
            if (preferredPort == 0) throw error
            server.bind(InetSocketAddress("0.0.0.0", 0))
        }
        socket = server
        boundPort = server.localPort
        running.set(true)
        Thread({
            try {
                acceptLoop(server)
            } catch (error: Throwable) {
                DmrLog.i("http accept stopped: ${error.javaClass.simpleName}: ${error.message}")
            }
        }, "dmr-accept").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                DmrLog.i("http accept died: ${error.javaClass.simpleName}: ${error.message}")
            }
            start()
        }
        return boundPort
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        workers.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            workers.execute {
                try {
                    handleClient(client)
                } catch (error: Throwable) {
                    // NoSuchMethodError is an Error, not an Exception. One connection must not crash the process.
                    DmrLog.i("http connection failed: ${error.javaClass.simpleName}: ${error.message}")
                } finally {
                    try {
                        client.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 8_000
        val remote = client.inetAddress?.hostAddress ?: ""
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        while (true) {
            val request = try {
                readRequest(input, remote) {
                    output.write(CONTINUE)
                    output.flush()
                }
            } catch (_: SocketTimeoutException) {
                break
            } ?: break
            val response = try {
                handler(request)
            } catch (error: Throwable) {
                DmrLog.i("http handler failed: ${error.javaClass.simpleName}: ${error.message}")
                HttpResponse(500, "Internal Server Error", emptyMap(), "error".toByteArray())
            }
            output.write(response.toBytes(request.protocol))
            output.flush()
            if (response.status == 101) {
                holdUntilClose(client, input)
                break
            }
            if (shouldClose(request, response)) break
            client.soTimeout = if (request.protocol.startsWith("RTSP", ignoreCase = true)) 30_000 else 8_000
        }
    }

    /** RTSP stays open for the next CSeq. HTTP closes unless the client asked to keep it. */
    private fun shouldClose(request: HttpRequest, response: HttpResponse): Boolean {
        val responseConnection = headerValue(response.headers, "connection")
        if (responseConnection.contains("close")) return true
        if (responseConnection.contains("keep-alive") || responseConnection.contains("upgrade")) return false
        val requestConnection = request.header("connection")?.lowercase() ?: ""
        if (requestConnection.contains("close")) return true
        if (request.protocol.startsWith("RTSP", ignoreCase = true)) return false
        if (requestConnection.contains("keep-alive")) return false
        return true
    }

    private fun headerValue(headers: Map<String, String>, name: String): String {
        for ((key, value) in headers) {
            if (key.equals(name, ignoreCase = true)) return value.lowercase()
        }
        return ""
    }

    private fun holdUntilClose(client: Socket, input: InputStream) {
        try {
            client.soTimeout = 30_000
            val buffer = ByteArray(1024)
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (read < 0) break
            }
        } catch (_: Exception) {
        }
    }

    private fun readRequest(input: InputStream, remoteIp: String, onContinue: () -> Unit): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            onContinue()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0)
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val body = when {
            chunked -> readChunked(input)
            length != null -> readBytes(input, length.coerceAtMost(MAX_BODY))
            else -> ByteArray(0)
        }
        val (path, query) = splitTarget(parts[1])
        val protocol = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2] else "HTTP/1.1"
        return HttpRequest(parts[0].uppercase(), path, headers, body, remoteIp, query, protocol)
    }

    private fun splitTarget(target: String): Pair<String, String> {
        val raw = target.trim()
        val pathAndQuery = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            "/" + raw.substringAfter("://").substringAfter('/', "")
        } else {
            raw
        }
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "")
        return (if (path.isBlank()) "/" else path) to query
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_BODY) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            val chunk = readBytes(input, size)
            output.write(chunk)
            readLine(input)
        }
        return output.toByteArray()
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == count) buffer else buffer.copyOf(offset)
    }

    private fun readLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (output.size() < 16_384) {
            val value = input.read()
            if (value == -1) {
                return if (output.size() == 0) null else decodeLine(output)
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
        }
        return decodeLine(output)
    }

    /**
     * HTTP/1.1 header lines are ISO-8859-1.
     * [ByteArrayOutputStream.toString] with a [java.nio.charset.Charset] exists only on API 33;
     * Android 11 throws [NoSuchMethodError] and the default uncaught handler kills the app.
     */
    private fun decodeLine(output: ByteArrayOutputStream): String =
        String(output.toByteArray(), Charsets.ISO_8859_1)

    companion object {
        private const val MAX_BODY = 1024 * 1024
        private val CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
