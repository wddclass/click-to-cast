package com.wddclass.clicktocast.dlna

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit)
}

fun interface EventTransport {
    fun notify(callbackUrl: String, sid: String, seq: Long, body: String)
}

class SocketEventTransport : EventTransport {
    override fun notify(callbackUrl: String, sid: String, seq: Long, body: String) {
        val uri = runCatching { URI(callbackUrl) }.getOrNull() ?: return
        if (!uri.scheme.equals("http", true)) return
        val host = uri.host ?: return
        val port = if (uri.port == -1) 80 else uri.port
        val path = (uri.rawPath?.ifBlank { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val payload = body.toByteArray(Charsets.UTF_8)
        val hostHeader = if (port == 80) host else "$host:$port"
        val head = buildString {
            append("NOTIFY ").append(path).append(" HTTP/1.1\r\n")
            append("HOST: ").append(hostHeader).append("\r\n")
            append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
            append("CONTENT-LENGTH: ").append(payload.size).append("\r\n")
            append("NT: upnp:event\r\n")
            append("NTS: upnp:propchange\r\n")
            append("SID: ").append(sid).append("\r\n")
            append("SEQ: ").append(seq).append("\r\n")
            append("CONNECTION: close\r\n\r\n")
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2000)
            socket.soTimeout = 2000
            val output = socket.getOutputStream()
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(payload)
            output.flush()
            try {
                socket.getInputStream().read()
            } catch (_: Exception) {
            }
        }
    }
}

data class SubscriptionResult(val sid: String, val timeoutSeconds: Int)

/**
 * GENA subscriptions. The initial event is delayed so it leaves after the SUBSCRIBE response.
 */
class GenaPublisher(
    private val transport: EventTransport,
    private val scheduler: Scheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val properties: (service: String) -> Map<String, String>,
) {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    fun subscribe(service: String, callbackHeader: String?, timeoutHeader: String?, sidHeader: String?): SubscriptionResult? {
        val timeout = parseTimeout(timeoutHeader)
        if (!sidHeader.isNullOrBlank()) {
            val existing = subscriptions[sidHeader.trim()] ?: return null
            if (existing.service != service) return null
            existing.expiresAt = now() + timeout * 1000L
            return SubscriptionResult(existing.sid, timeout)
        }
        val callbacks = parseCallbacks(callbackHeader).take(3)
        if (callbacks.isEmpty()) return null
        val sid = "uuid:${UUID.randomUUID()}"
        val subscription = Subscription(sid, service, callbacks, now() + timeout * 1000L, AtomicLong(0))
        subscriptions[sid] = subscription
        scheduler.schedule(120L) { send(subscription) }
        return SubscriptionResult(sid, timeout)
    }

    fun unsubscribe(sidHeader: String?): Boolean {
        val sid = sidHeader?.trim().orEmpty()
        if (sid.isEmpty()) return false
        return subscriptions.remove(sid) != null
    }

    fun publish(service: String) {
        val snapshot = subscriptions.values.filter { it.service == service && it.expiresAt > now() }
        for (subscription in snapshot) send(subscription)
    }

    fun propertySet(props: Map<String, String>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">""")
        for ((name, value) in props) {
            append("<e:property><").append(name).append('>')
            append(XmlText.escape(value))
            append("</").append(name).append("></e:property>")
        }
        append("</e:propertyset>")
    }

    private fun send(subscription: Subscription) {
        if (subscription.expiresAt <= now()) {
            subscriptions.remove(subscription.sid)
            return
        }
        val body = propertySet(properties(subscription.service))
        val seq = subscription.seq.getAndIncrement()
        for (callback in subscription.callbacks) {
            try {
                transport.notify(callback, subscription.sid, seq, body)
            } catch (error: Exception) {
                DmrLog.i("event notify failed: ${error.message}")
            }
        }
    }

    private fun parseCallbacks(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return CALLBACK.findAll(header).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    }

    private fun parseTimeout(header: String?): Int {
        val match = TIMEOUT.find(header.orEmpty()) ?: return 1800
        return match.groupValues[1].toIntOrNull()?.coerceIn(300, 1800) ?: 1800
    }

    private class Subscription(
        val sid: String,
        val service: String,
        val callbacks: List<String>,
        @Volatile var expiresAt: Long,
        val seq: AtomicLong,
    )

    companion object {
        private val CALLBACK = Regex("<([^>]+)>")
        private val TIMEOUT = Regex("Second-(\\d+)", RegexOption.IGNORE_CASE)

        fun defaultScheduler(): Scheduler {
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "dmr-gena").apply { isDaemon = true }
            }
            return Scheduler { delayMs, task ->
                executor.schedule(task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }
}
