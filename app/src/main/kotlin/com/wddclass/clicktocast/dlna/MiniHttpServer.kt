package com.wddclass.clicktocast.dlna

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun toBytes(): ByteArray {
        val merged = linkedMapOf<String, String>()
        headers.forEach { (key, value) -> merged[key] = value }
        if (merged.keys.none { it.equals("Content-Length", true) }) {
            merged["Content-Length"] = body.size.toString()
        }
        if (merged.keys.none { it.equals("Connection", true) }) {
            merged["Connection"] = "close"
        }
        if (merged.keys.none { it.equals("Server", true) }) {
            merged["Server"] = "ClickToCast UPnP/1.0"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
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
        Thread(runnable, "dmr-http").apply { isDaemon = true }
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
        Thread({ acceptLoop(server) }, "dmr-accept").apply { isDaemon = true }.start()
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
                } catch (_: Exception) {
                } finally {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 8_000
        val remote = client.inetAddress?.hostAddress ?: ""
        val input = BufferedInputStream(client.getInputStream())
        val request = readRequest(input, remote) ?: return
        val response = try {
            handler(request)
        } catch (error: Exception) {
            DmrLog.i("http handler failed: ${error.message}")
            HttpResponse(500, "Internal Server Error", emptyMap(), "error".toByteArray())
        }
        client.getOutputStream().use { output ->
            output.write(response.toBytes())
            output.flush()
        }
    }

    private fun readRequest(input: InputStream, remoteIp: String): HttpRequest? {
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
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0)
        val chunked = headers["transfer-encoding"]?.contains("chunked", true) == true
        val body = when {
            chunked -> readChunked(input)
            length != null -> readBytes(input, length.coerceAtMost(MAX_BODY))
            else -> ByteArray(0)
        }
        return HttpRequest(parts[0].uppercase(), normalizePath(parts[1]), headers, body, remoteIp)
    }

    private fun normalizePath(target: String): String {
        val raw = target.trim()
        val path = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            "/" + raw.substringAfter("://").substringAfter('/', "")
        } else {
            raw
        }
        val clean = path.substringBefore('?')
        return if (clean.isBlank()) "/" else clean
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_BODY) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
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
            if (value == -1) return if (output.size() == 0) null else output.toString(Charsets.ISO_8859_1)
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
        }
        return output.toString(Charsets.ISO_8859_1)
    }

    companion object {
        private const val MAX_BODY = 1024 * 1024
    }
}
