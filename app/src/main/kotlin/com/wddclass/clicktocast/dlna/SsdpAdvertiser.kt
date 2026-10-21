package com.wddclass.clicktocast.dlna

import com.wddclass.clicktocast.net.LanNic
import com.wddclass.clicktocast.net.NetworkAddresses
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Announces the renderer on 239.255.255.250:1900 and answers M-SEARCH.
 * NOTIFY goes out three times on every IPv4 interface. Search replies are unicast,
 * delayed by a random 0..MX so they still leave inside the phone's wait window.
 */
class SsdpAdvertiser(
    private val profileProvider: () -> DeviceProfile,
    private val bootId: Int = 1,
    private val configId: () -> Int = { 1 },
) {
    private val pending = mutableListOf<Pending>()
    @Volatile private var running = false
    @Volatile private var burstRequested = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                runLoop()
            } catch (error: Throwable) {
                DmrLog.i("ssdp thread failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }, "dmr-ssdp").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                DmrLog.i("ssdp thread died: ${error.javaClass.simpleName}: ${error.message}")
            }
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
        burstRequested = true
        thread?.interrupt()
    }

    private fun runLoop() {
        val group = InetAddress.getByName("239.255.255.250")
        while (running) {
            val socket = try {
                openSocket()
            } catch (error: Exception) {
                if (!running) break
                DmrLog.i("ssdp bind 1900 failed: ${error.message}")
                sleepQuiet(3_000)
                continue
            }
            try {
                serve(socket, group)
            } catch (error: Throwable) {
                if (running) DmrLog.i("ssdp stopped: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                pending.clear()
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
            if (!running) break
            sleepQuiet(1_000)
        }
    }

    private fun openSocket(): MulticastSocket {
        val socket = MulticastSocket(1900)
        socket.timeToLive = SsdpTiming.MULTICAST_TTL
        return socket
    }

    private fun serve(socket: MulticastSocket, group: InetAddress) {
        val joined = linkedMapOf<String, LanNic>()
        var lastAnnounce = 0L
        var nics = emptyList<LanNic>()
        while (running) {
            nics = NetworkAddresses.ipv4Nics()
            syncGroups(socket, group, nics, joined)
            val now = System.currentTimeMillis()
            val profile = named(profileProvider())
            val dueBurst = burstRequested || lastAnnounce == 0L || now - lastAnnounce >= SsdpTiming.ALIVE_INTERVAL_MS
            if (nics.isNotEmpty() && dueBurst) {
                burstRequested = false
                burstAlive(socket, group, nics, profile)
                lastAnnounce = System.currentTimeMillis()
                DmrLog.i(
                    "ssdp alive x${SsdpTiming.ALIVE_BURST} ttl=${SsdpTiming.MULTICAST_TTL} " +
                        "name=${profile.friendlyName} udn=${profile.udn} " +
                        "on ${nics.joinToString { "${it.name}/${it.ipv4}" }}",
                )
            }
            flushDue(System.currentTimeMillis())
            socket.soTimeout = waitMs(System.currentTimeMillis(), lastAnnounce)
            try {
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                handleSearch(socket, packet, nics, profile)
            } catch (_: SocketTimeoutException) {
            } catch (error: Exception) {
                if (Thread.interrupted() || burstRequested) continue
                if (running) DmrLog.i("ssdp receive: ${error.message}")
            }
            flushDue(System.currentTimeMillis())
        }
        if (nics.isNotEmpty()) {
            sendBye(socket, group, nics, named(profileProvider()))
        }
    }

    private fun handleSearch(
        socket: MulticastSocket,
        packet: DatagramPacket,
        nics: List<LanNic>,
        profile: DeviceProfile,
    ) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val search = SsdpMessages.parseMSearch(text) ?: return
        val peer = packet.address?.hostAddress.orEmpty()
        val host = NetworkAddresses.preferredHost(
            peer,
            nics.map { it.ipv4 },
            profile.hostAddress.ifBlank { nics.firstOrNull()?.ipv4.orEmpty() },
        )
        if (host.isBlank() || packet.address == null || packet.port <= 0) return
        val local = profile.copy(hostAddress = host)
        val responses = SsdpMessages.searchResponses(
            local,
            search.st,
            SsdpMessages.httpDate(System.currentTimeMillis()),
            bootId,
            configId(),
        )
        if (responses.isEmpty()) return
        val window = search.mxSeconds.coerceIn(0, 5) * 1000
        val roll = if (window == 0) 0 else Random.nextInt(window + 1)
        val delay = SsdpTiming.delayMs(search.mxSeconds, roll)
        DmrLog.i(
            "ssdp m-search st=${search.st} from $peer:${packet.port} " +
                "replies=${responses.size} delayMs=$delay location=${local.location}",
        )
        val due = System.currentTimeMillis() + delay
        for (body in responses) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            pending += Pending(due, DatagramPacket(bytes, bytes.size, packet.address, packet.port), socket)
        }
        pending.sortBy { it.due }
        if (delay == 0) flushDue(System.currentTimeMillis())
    }

    private fun burstAlive(socket: MulticastSocket, group: InetAddress, nics: List<LanNic>, profile: DeviceProfile) {
        val date = SsdpMessages.httpDate(System.currentTimeMillis())
        val config = configId()
        repeat(SsdpTiming.ALIVE_BURST) {
            for (nic in nics) {
                if (!selectInterface(socket, nic)) continue
                sendAll(socket, group, SsdpMessages.alive(profile.copy(hostAddress = nic.ipv4), date, bootId, config))
            }
        }
    }

    private fun sendBye(socket: MulticastSocket, group: InetAddress, nics: List<LanNic>, profile: DeviceProfile) {
        val date = SsdpMessages.httpDate(System.currentTimeMillis())
        val config = configId()
        for (nic in nics) {
            if (!selectInterface(socket, nic)) continue
            sendAll(socket, group, SsdpMessages.bye(profile.copy(hostAddress = nic.ipv4), date, bootId, config))
        }
    }

    private fun selectInterface(socket: MulticastSocket, nic: LanNic): Boolean {
        return try {
            socket.networkInterface = nic.nif
            true
        } catch (error: Exception) {
            DmrLog.i("ssdp interface ${nic.name}: ${error.message}")
            false
        }
    }

    private fun syncGroups(
        socket: MulticastSocket,
        group: InetAddress,
        nics: List<LanNic>,
        joined: MutableMap<String, LanNic>,
    ) {
        val want = nics.associateBy { "${it.name}/${it.ipv4}" }
        val stale = joined.keys.filter { it !in want }
        for (key in stale) {
            val nic = joined[key] ?: continue
            try {
                socket.leaveGroup(InetSocketAddress(group, 1900), nic.nif)
            } catch (_: Exception) {
            }
            joined.remove(key)
        }
        for ((key, nic) in want) {
            if (key in joined) continue
            try {
                socket.joinGroup(InetSocketAddress(group, 1900), nic.nif)
                joined[key] = nic
                DmrLog.i("ssdp join ${nic.name} ${nic.ipv4}")
            } catch (error: Exception) {
                DmrLog.i("ssdp join ${nic.name} failed: ${error.message}")
            }
        }
    }

    private fun flushDue(now: Long) {
        while (pending.isNotEmpty() && pending.first().due <= now) {
            val item = pending.removeAt(0)
            try {
                item.socket.send(item.packet)
            } catch (_: Exception) {
            }
        }
    }

    private fun waitMs(now: Long, lastAnnounce: Long): Int {
        val untilAnnounce = if (lastAnnounce == 0L) {
            200L
        } else {
            (lastAnnounce + SsdpTiming.ALIVE_INTERVAL_MS - now).coerceAtLeast(50)
        }
        val untilSend = pending.firstOrNull()?.let { (it.due - now).coerceAtLeast(1) } ?: untilAnnounce
        return minOf(untilAnnounce, untilSend, 1_000L).toInt().coerceIn(20, 1_000)
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

    private fun named(profile: DeviceProfile): DeviceProfile {
        val name = profile.friendlyName.trim().ifBlank { Upnp.DEFAULT_FRIENDLY_NAME }
        return if (name == profile.friendlyName) profile else profile.copy(friendlyName = name)
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private class Pending(val due: Long, val packet: DatagramPacket, val socket: MulticastSocket)
}
