package com.wddclass.clicktocast.dlna

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.ArrayDeque

/**
 * Announces the renderer on 239.255.255.250:1900 and answers M-SEARCH.
 * Responses are queued on the receive thread so MX delay does not block the socket.
 */
class SsdpAdvertiser(
    private val profileProvider: () -> DeviceProfile,
    private val bootId: Int = 1,
    private val configId: () -> Int = { 1 },
) {
    private val pending = ArrayDeque<Pending>()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "dmr-ssdp").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1500)
        thread = null
    }

    fun announceSoon() {
        // The loop sends alive whenever the profile address changes and on its own timer.
        // Interrupting the socket timeout makes the next pass happen immediately.
        thread?.interrupt()
    }

    private fun runLoop() {
        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName("239.255.255.250")
            socket = MulticastSocket(1900)
            socket.timeToLive = 4
            socket.soTimeout = 1000
            var joined: NetworkInterface? = null
            var joinedAddress = ""
            var lastAnnounce = 0L
            while (running) {
                val profile = profileProvider()
                val nif = interfaceFor(profile.hostAddress)
                val now = System.currentTimeMillis()
                if (nif != null && (nif != joined || profile.hostAddress != joinedAddress)) {
                    if (joined != null) {
                        try {
                            socket.leaveGroup(InetSocketAddress(group, 1900), joined)
                        } catch (_: Exception) {
                        }
                    }
                    socket.joinGroup(InetSocketAddress(group, 1900), nif)
                    joined = nif
                    joinedAddress = profile.hostAddress
                    sendAll(socket, group, SsdpMessages.alive(profile, SsdpMessages.httpDate(now), bootId, configId()))
                    lastAnnounce = now
                    DmrLog.i("ssdp alive on ${profile.hostAddress}:${profile.httpPort}")
                } else if (nif != null && now - lastAnnounce > 20_000) {
                    sendAll(socket, group, SsdpMessages.alive(profile, SsdpMessages.httpDate(now), bootId, configId()))
                    lastAnnounce = now
                }
                flushDue(socket, now)
                try {
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val search = SsdpMessages.parseMSearch(text) ?: continue
                    val current = profileProvider()
                    val responses = SsdpMessages.searchResponses(
                        current,
                        search.st,
                        SsdpMessages.httpDate(System.currentTimeMillis()),
                        bootId,
                        configId(),
                    )
                    if (responses.isEmpty()) continue
                    val delay = if (search.mxSeconds <= 0) 0 else (0..search.mxSeconds * 1000).random().coerceAtMost(1500)
                    val due = System.currentTimeMillis() + delay
                    for (body in responses) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        pending.add(Pending(due, DatagramPacket(bytes, bytes.size, packet.address, packet.port)))
                    }
                } catch (_: SocketTimeoutException) {
                } catch (error: Exception) {
                    // announceSoon() interrupts the receive as a wake-up.
                    if (Thread.interrupted()) continue
                    if (running) DmrLog.i("ssdp receive: ${error.message}")
                }
            }
            val closing = profileProvider()
            if (closing.hostAddress.isNotBlank()) {
                sendAll(socket, group, SsdpMessages.bye(closing, SsdpMessages.httpDate(System.currentTimeMillis()), bootId, configId()))
            }
        } catch (error: Exception) {
            DmrLog.i("ssdp stopped: ${error.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun flushDue(socket: MulticastSocket, now: Long) {
        while (pending.isNotEmpty() && pending.first().due <= now) {
            val item = pending.removeFirst()
            try {
                socket.send(item.packet)
            } catch (_: Exception) {
            }
        }
    }

    private fun sendAll(socket: MulticastSocket, group: InetAddress, messages: List<String>) {
        for (message in messages) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            try {
                socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
            } catch (error: Exception) {
                DmrLog.i("ssdp send: ${error.message}")
            }
        }
    }

    private fun interfaceFor(address: String): NetworkInterface? {
        if (address.isBlank()) return null
        return try {
            val inet = InetAddress.getByName(address)
            NetworkInterface.getByInetAddress(inet)
        } catch (_: Exception) {
            null
        }
    }

    private class Pending(val due: Long, val packet: DatagramPacket)
}
