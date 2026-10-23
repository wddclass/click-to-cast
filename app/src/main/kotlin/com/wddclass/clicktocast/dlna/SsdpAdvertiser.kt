package com.wddclass.clicktocast.dlna

import com.wddclass.clicktocast.net.LanNic
import com.wddclass.clicktocast.net.NetworkAddresses
import com.wddclass.clicktocast.net.V6Nic
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Inet6Address
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Announces the renderer and answers M-SEARCH with a unicast datagram.
 * Replies leave the socket bound to port 1900, addressed to the sender's
 * ip:port, on the interface that shares a subnet with that sender.
 * IPv4 uses 239.255.255.250. IPv6 uses ff02::c and ff05::c, because iOS
 * cast apps often search only on IPv6 and cannot receive multicast NOTIFY.
 */
class SsdpAdvertiser(
    private val profileProvider: () -> DeviceProfile,
    private val bootId: Int = 1,
    private val configId: () -> Int = { 1 },
) {
    @Volatile private var running = false
    private val burstGeneration = AtomicInteger(0)
    @Volatile private var v4Socket: MulticastSocket? = null
    @Volatile private var v6Socket: MulticastSocket? = null
    private val threads = mutableListOf<Thread>()

    fun start() {
        if (running) return
        running = true
        threads += worker("dmr-ssdp") { runFamily(ipv6 = false) }
        threads += worker("dmr-ssdp6") { runFamily(ipv6 = true) }
    }

    fun stop() {
        running = false
        closeQuiet(v4Socket)
        closeQuiet(v6Socket)
        threads.forEach { thread ->
            thread.interrupt()
            thread.join(1500)
        }
        threads.clear()
    }

    fun announceSoon() {
        burstGeneration.incrementAndGet()
        threads.forEach { it.interrupt() }
    }

    private fun worker(name: String, body: () -> Unit): Thread {
        return Thread({
            try {
                body()
            } catch (error: Throwable) {
                DmrLog.i("ssdp thread failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }, name).apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                DmrLog.i("ssdp thread died: ${error.javaClass.simpleName}: ${error.message}")
            }
            start()
        }
    }

    private fun runFamily(ipv6: Boolean) {
        val label = if (ipv6) "ipv6" else "ipv4"
        var lastBindLog = 0L
        while (running) {
            val socket = try {
                openSocket(ipv6)
            } catch (error: Exception) {
                if (!running) break
                val now = System.currentTimeMillis()
                if (now - lastBindLog >= 30_000L) {
                    lastBindLog = now
                    DmrLog.i("ssdp $label bind ${SsdpReply.PORT} failed: ${error.message}")
                }
                sleepQuiet(3_000)
                continue
            }
            if (ipv6) v6Socket = socket else v4Socket = socket
            val portNote = if (socket.localPort == SsdpReply.PORT) {
                "port ${socket.localPort}"
            } else {
                "port ${socket.localPort} (iOS ignores replies whose source port is not 1900)"
            }
            DmrLog.i("ssdp $label bound $portNote ttl=${SsdpTiming.MULTICAST_TTL}")
            try {
                if (ipv6) serveV6(socket) else serveV4(socket)
            } catch (error: Throwable) {
                if (running) DmrLog.i("ssdp $label stopped: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                if (ipv6) v6Socket = null else v4Socket = null
                closeQuiet(socket)
            }
            if (!running) break
            sleepQuiet(1_000)
        }
    }

    private fun openSocket(ipv6: Boolean): MulticastSocket {
        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        val any = if (ipv6) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
        socket.bind(InetSocketAddress(any, SsdpReply.PORT))
        socket.timeToLive = SsdpTiming.MULTICAST_TTL
        return socket
    }

    private fun serveV4(socket: MulticastSocket) {
        val group = InetAddress.getByName(SsdpTiming.IPV4_GROUP)
        val joined = linkedMapOf<String, LanNic>()
        val pending = mutableListOf<Pending>()
        var lastAnnounce = 0L
        var seenBurst = 0
        var nics = emptyList<LanNic>()
        while (running) {
            nics = NetworkAddresses.ipv4Nics()
            syncV4(socket, group, nics, joined)
            val now = System.currentTimeMillis()
            val profile = named(profileProvider())
            val generation = burstGeneration.get()
            if (nics.isNotEmpty() && (generation != seenBurst || lastAnnounce == 0L || now - lastAnnounce >= SsdpTiming.ALIVE_INTERVAL_MS)) {
                seenBurst = generation
                burstV4(socket, group, nics, profile, alive = true)
                lastAnnounce = System.currentTimeMillis()
                DmrLog.i(
                    "ssdp alive x${SsdpTiming.ALIVE_BURST} ttl=${SsdpTiming.MULTICAST_TTL} " +
                        "name=${profile.friendlyName} udn=${profile.udn} " +
                        "on ${nics.joinToString { "${it.name}/${it.ipv4}" }}",
                )
            }
            flushDue(pending, System.currentTimeMillis())
            socket.soTimeout = waitMs(System.currentTimeMillis(), lastAnnounce, pending)
            receive(socket, pending, nics, emptyList())
            flushDue(pending, System.currentTimeMillis())
        }
        if (nics.isNotEmpty()) burstV4(socket, group, nics, named(profileProvider()), alive = false)
        pending.clear()
    }

    private fun serveV6(socket: MulticastSocket) {
        val link = InetAddress.getByName(SsdpTiming.IPV6_LINK_GROUP)
        val site = InetAddress.getByName(SsdpTiming.IPV6_SITE_GROUP)
        val joined = linkedMapOf<String, V6Nic>()
        val siteJoined = mutableSetOf<String>()
        val pending = mutableListOf<Pending>()
        var lastAnnounce = 0L
        var seenBurst = 0
        var nics = emptyList<V6Nic>()
        while (running) {
            nics = NetworkAddresses.ipv6Nics()
            syncV6(socket, link, nics, joined)
            for (nic in nics) {
                val key = "${nic.name}/${nic.index}"
                if (key in siteJoined) continue
                try {
                    socket.joinGroup(InetSocketAddress(site, SsdpReply.PORT), nic.nif)
                    siteJoined += key
                } catch (_: Exception) {
                }
            }
            val now = System.currentTimeMillis()
            val profile = named(profileProvider())
            val locationHost = profile.hostAddress.ifBlank { NetworkAddresses.bestIpv4().orEmpty() }
            val generation = burstGeneration.get()
            if (nics.isNotEmpty() && locationHost.isNotBlank() &&
                (generation != seenBurst || lastAnnounce == 0L || now - lastAnnounce >= SsdpTiming.ALIVE_INTERVAL_MS)
            ) {
                seenBurst = generation
                burstV6(socket, link, nics, profile.copy(hostAddress = locationHost), alive = true)
                lastAnnounce = System.currentTimeMillis()
                DmrLog.i(
                    "ssdp alive6 x${SsdpTiming.ALIVE_BURST} ttl=${SsdpTiming.MULTICAST_TTL} " +
                        "name=${profile.friendlyName} on ${nics.joinToString { it.name }}",
                )
            }
            flushDue(pending, System.currentTimeMillis())
            socket.soTimeout = waitMs(System.currentTimeMillis(), lastAnnounce, pending)
            receive(socket, pending, NetworkAddresses.ipv4Nics(), nics)
            flushDue(pending, System.currentTimeMillis())
        }
        val host = named(profileProvider()).hostAddress.ifBlank { NetworkAddresses.bestIpv4().orEmpty() }
        if (nics.isNotEmpty() && host.isNotBlank()) {
            burstV6(socket, link, nics, named(profileProvider()).copy(hostAddress = host), alive = false)
        }
        pending.clear()
    }

    private fun receive(
        socket: MulticastSocket,
        pending: MutableList<Pending>,
        v4: List<LanNic>,
        v6: List<V6Nic>,
    ) {
        try {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            handleSearch(socket, packet, pending, v4, v6)
        } catch (_: SocketTimeoutException) {
        } catch (error: Exception) {
            if (Thread.interrupted()) return
            if (running) DmrLog.i("ssdp receive: ${error.message}")
        }
    }

    private fun handleSearch(
        socket: MulticastSocket,
        packet: DatagramPacket,
        pending: MutableList<Pending>,
        v4: List<LanNic>,
        v6: List<V6Nic>,
    ) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
        val head = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!head.startsWith("M-SEARCH", ignoreCase = true)) return
        val peer = SsdpReply.peer(packet.address, packet.port)
        val search = SsdpMessages.parseMSearch(text)
        if (search == null) {
            DmrLog.i("ssdp m-search from $peer st=(missing) head=${head.take(120)}")
            return
        }
        DmrLog.i(
            "ssdp m-search from $peer st=${search.st} mx=${search.mxSeconds} " +
                "host=${search.host.ifBlank { "-" }} ua=${search.userAgent.ifBlank { "-" }} " +
                "man=${search.man.ifBlank { "-" }}",
        )
        val literals = v4.map { it.ipv4 }
        val v4Peer = SsdpReply.ipv4Literal(packet.address)
        val fallback = profileProvider().hostAddress.ifBlank { literals.firstOrNull().orEmpty() }
        val host = NetworkAddresses.preferredHost(v4Peer ?: "", literals, fallback)
        if (host.isBlank() || packet.address == null || packet.port <= 0) {
            DmrLog.i("ssdp m-search ignored from $peer st=${search.st} reason=no-location")
            return
        }
        val local = named(profileProvider()).copy(hostAddress = host)
        val responses = SsdpMessages.searchResponses(
            local,
            search.st,
            SsdpMessages.httpDate(System.currentTimeMillis()),
            bootId,
            configId(),
        )
        if (responses.isEmpty()) {
            DmrLog.i("ssdp m-search ignored from $peer st=${search.st}")
            return
        }
        val nic = v4.firstOrNull { it.ipv4 == host }
            ?: v4.firstOrNull { NetworkAddresses.same24(it.ipv4, host) }
            ?: v4.firstOrNull()
        val fromV6 = packet.address is Inet6Address && v4Peer == null
        val scoped = (packet.address as? Inet6Address)?.scopeId ?: 0
        val v6Nic = v6.firstOrNull { scoped > 0 && it.index == scoped } ?: v6.firstOrNull()
        val scope = if (scoped > 0) scoped else v6Nic?.index ?: 0
        val target = SsdpReply.unicastTarget(packet, scope)
        val window = SsdpTiming.delayMs(search.mxSeconds, if (search.mxSeconds <= 0) 0 else Int.MAX_VALUE)
        val roll = if (window == 0) 0 else Random.nextInt(window + 1)
        val delay = SsdpTiming.delayMs(search.mxSeconds, roll)
        val due = System.currentTimeMillis() + delay
        for (body in responses) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val st = body.lineSequence().firstOrNull { it.startsWith("ST: ") }?.removePrefix("ST: ").orEmpty()
            pending += Pending(
                due,
                DatagramPacket(bytes, bytes.size, target),
                socket,
                if (fromV6) null else nic,
                if (fromV6) v6Nic else null,
                st,
                local.location,
            )
        }
        pending.sortBy { it.due }
        if (delay == 0) flushDue(pending, System.currentTimeMillis())
    }

    private fun burstV4(
        socket: MulticastSocket,
        group: InetAddress,
        nics: List<LanNic>,
        profile: DeviceProfile,
        alive: Boolean,
    ) {
        val date = SsdpMessages.httpDate(System.currentTimeMillis())
        val config = configId()
        val copies = if (alive) SsdpTiming.ALIVE_BURST else 1
        repeat(copies) {
            for (nic in nics) {
                if (!selectInterface(socket, nic.nif, nic.name)) continue
                val profileOnNic = profile.copy(hostAddress = nic.ipv4)
                val messages = if (alive) {
                    SsdpMessages.alive(profileOnNic, date, bootId, config)
                } else {
                    SsdpMessages.bye(profileOnNic, date, bootId, config)
                }
                sendGroup(socket, group, messages)
            }
        }
    }

    private fun burstV6(
        socket: MulticastSocket,
        group: InetAddress,
        nics: List<V6Nic>,
        profile: DeviceProfile,
        alive: Boolean,
    ) {
        val date = SsdpMessages.httpDate(System.currentTimeMillis())
        val config = configId()
        val host = "[FF02::C]:1900"
        val copies = if (alive) SsdpTiming.ALIVE_BURST else 1
        repeat(copies) {
            for (nic in nics) {
                if (!selectInterface(socket, nic.nif, nic.name)) continue
                val messages = if (alive) {
                    SsdpMessages.alive(profile, date, bootId, config, host)
                } else {
                    SsdpMessages.bye(profile, date, bootId, config, host)
                }
                sendGroup(socket, group, messages)
            }
        }
    }

    private fun selectInterface(socket: MulticastSocket, nif: NetworkInterface, name: String): Boolean {
        return try {
            socket.networkInterface = nif
            true
        } catch (error: Exception) {
            DmrLog.i("ssdp interface $name: ${error.message}")
            false
        }
    }

    private fun syncV4(
        socket: MulticastSocket,
        group: InetAddress,
        nics: List<LanNic>,
        joined: MutableMap<String, LanNic>,
    ) {
        val want = nics.associateBy { "${it.name}/${it.ipv4}" }
        for (key in joined.keys.filter { it !in want }) {
            val nic = joined[key] ?: continue
            try {
                socket.leaveGroup(InetSocketAddress(group, SsdpReply.PORT), nic.nif)
            } catch (_: Exception) {
            }
            joined.remove(key)
        }
        for ((key, nic) in want) {
            if (key in joined) continue
            try {
                socket.joinGroup(InetSocketAddress(group, SsdpReply.PORT), nic.nif)
                joined[key] = nic
                DmrLog.i("ssdp join ${nic.name} ${nic.ipv4}")
            } catch (error: Exception) {
                DmrLog.i("ssdp join ${nic.name} failed: ${error.message}")
            }
        }
    }

    private fun syncV6(
        socket: MulticastSocket,
        group: InetAddress,
        nics: List<V6Nic>,
        joined: MutableMap<String, V6Nic>,
    ) {
        val want = nics.associateBy { "${it.name}/${it.index}" }
        for (key in joined.keys.filter { it !in want }) {
            val nic = joined[key] ?: continue
            try {
                socket.leaveGroup(InetSocketAddress(group, SsdpReply.PORT), nic.nif)
            } catch (_: Exception) {
            }
            joined.remove(key)
        }
        for ((key, nic) in want) {
            if (key in joined) continue
            try {
                socket.joinGroup(InetSocketAddress(group, SsdpReply.PORT), nic.nif)
                joined[key] = nic
                DmrLog.i("ssdp join6 ${nic.name}")
            } catch (error: Exception) {
                DmrLog.i("ssdp join6 ${nic.name} failed: ${error.message}")
            }
        }
    }

    private fun flushDue(pending: MutableList<Pending>, now: Long) {
        while (pending.isNotEmpty() && pending.first().due <= now) {
            val item = pending.removeAt(0)
            val nic = item.v4
            if (nic != null) {
                selectInterface(item.socket, nic.nif, nic.name)
            } else if (item.v6 != null) {
                selectInterface(item.socket, item.v6.nif, item.v6.name)
            }
            val dest = SsdpReply.peer(item.packet.address, item.packet.port)
            val iface = when {
                nic != null -> "${nic.name}/${nic.ipv4}"
                item.v6 != null -> item.v6.name
                else -> "-"
            }
            try {
                item.socket.send(item.packet)
                DmrLog.i(
                    "ssdp reply to $dest from :${item.socket.localPort} iface=$iface " +
                        "st=${item.st} bytes=${item.packet.length} location=${item.location}",
                )
            } catch (error: Exception) {
                DmrLog.i(
                    "ssdp reply failed to $dest from :${item.socket.localPort} iface=$iface " +
                        "st=${item.st}: ${error.message}",
                )
            }
        }
    }

    private fun waitMs(now: Long, lastAnnounce: Long, pending: List<Pending>): Int {
        val untilAnnounce = if (lastAnnounce == 0L) {
            200L
        } else {
            (lastAnnounce + SsdpTiming.ALIVE_INTERVAL_MS - now).coerceAtLeast(50)
        }
        val untilSend = pending.firstOrNull()?.let { (it.due - now).coerceAtLeast(1) } ?: untilAnnounce
        return minOf(untilAnnounce, untilSend, 1_000L).toInt().coerceIn(20, 1_000)
    }

    private fun sendGroup(socket: MulticastSocket, group: InetAddress, messages: List<String>) {
        for (message in messages) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            try {
                socket.send(DatagramPacket(bytes, bytes.size, group, SsdpReply.PORT))
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

    private fun closeQuiet(socket: MulticastSocket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private class Pending(
        val due: Long,
        val packet: DatagramPacket,
        val socket: MulticastSocket,
        val v4: LanNic?,
        val v6: V6Nic?,
        val st: String,
        val location: String,
    )
}
