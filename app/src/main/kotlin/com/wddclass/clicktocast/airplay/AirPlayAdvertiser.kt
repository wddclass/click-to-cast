package com.wddclass.clicktocast.airplay

import com.wddclass.clicktocast.dlna.DmrLog
import com.wddclass.clicktocast.dlna.SsdpReply
import com.wddclass.clicktocast.net.LanNic
import com.wddclass.clicktocast.net.NetworkAddresses
import com.wddclass.clicktocast.net.V6Nic
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Announces _airplay._tcp and _raop._tcp on every IPv4 interface, and answers
 * mDNS queries. NsdManager on Android TV often stays silent on Ethernet and
 * does not refresh after the address changes, so this talks to 5353 directly.
 * Screen mirroring still needs FairPlay and is not served.
 */
class AirPlayAdvertiser(
    private val port: () -> Int,
    private val identity: () -> AirPlayIdentity,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) {
            refresh()
            return
        }
        running = true
        worker = Thread(this::loop, "airplay-mdns").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * The announcer rebuilds its packets on the next pass, including after the
     * LAN address or the device name changes. The receive timeout is under a
     * second, so this does not have to wake the thread.
     */
    fun refresh() = Unit

    fun stop() {
        running = false
        worker?.interrupt()
        try {
            worker?.join(1_500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
    }

    private fun loop() {
        var v4: MulticastSocket? = null
        var v6: MulticastSocket? = null
        var loggedBind = false
        var loggedV6 = false
        var loggedEphemeral = false
        val joinedV4 = HashSet<String>()
        val joinedV6 = HashSet<String>()
        val joinFailed = HashSet<String>()
        var previous = emptyList<Mdns.Advertisement>()
        var fingerprint = ""
        var lastAnnounce = 0L
        try {
            while (running) {
                if (v4 == null) {
                    val opened = openSocket(ipv6 = false, prefer5353 = true)
                    v4 = opened?.socket
                    if (v4 == null) {
                        if (!loggedBind) {
                            DmrLog.i("airplay mdns bind failed; retrying")
                            loggedBind = true
                        }
                        sleepQuiet(3_000)
                        continue
                    }
                    loggedBind = false
                    if (opened.ephemeral && !loggedEphemeral) {
                        loggedEphemeral = true
                        DmrLog.i("airplay mdns bound ephemeral port ${v4.localPort}; queries to 5353 may be missed")
                    } else {
                        DmrLog.i("airplay mdns listening on ${v4.localPort}")
                    }
                }
                if (v6 == null) {
                    val opened = openSocket(ipv6 = true, prefer5353 = true)
                    v6 = opened?.socket
                    if (v6 == null && !loggedV6) {
                        loggedV6 = true
                        DmrLog.i("airplay mdns ipv6 unavailable")
                    }
                }
                val listen = port()
                val who = try {
                    identity()
                } catch (error: Exception) {
                    DmrLog.i("airplay mdns identity: ${error.message}")
                    sleepQuiet(1_000)
                    continue
                }
                val nics = NetworkAddresses.ipv4Nics()
                val v6nics = NetworkAddresses.ipv6Nics()
                syncJoin(v4, InetAddress.getByName(IPV4_GROUP), nics.map { it.name to it.nif }, joinedV4, joinFailed, "v4")
                if (v6 != null) {
                    syncJoin(v6, InetAddress.getByName(IPV6_GROUP), v6nics.map { it.name to it.nif }, joinedV6, joinFailed, "v6")
                }
                val nextPrint = nics.joinToString(",") { it.ipv4 } + "|" + who.name + "|" + who.deviceId + "|" + listen
                val now = System.currentTimeMillis()
                val due = listen > 0 && nics.isNotEmpty() &&
                    (nextPrint != fingerprint || lastAnnounce == 0L || now - lastAnnounce >= ANNOUNCE_MS)
                if (due) {
                    if (previous.isNotEmpty() && nextPrint != fingerprint) {
                        deliver(v4, nics, previous, goodbye = true)
                        if (v6 != null) deliverV6(v6, v6nics, previous, goodbye = true)
                    }
                    val ads = nics.map { nic ->
                        Mdns.advertisement(who.name, who.deviceId, listen, nic.ipv4, who.publicKeyHex)
                    }
                    deliver(v4, nics, ads, goodbye = false)
                    if (v6 != null) deliverV6(v6, v6nics, ads, goodbye = false)
                    previous = ads
                    fingerprint = nextPrint
                    lastAnnounce = System.currentTimeMillis()
                }
                if (listen > 0 && nics.isNotEmpty()) {
                    poll(v4, nics, who, listen, 0)
                    val scope = v6nics.firstOrNull()?.index ?: 0
                    if (v6 != null) poll(v6, nics, who, listen, scope)
                } else {
                    sleepQuiet(1_000)
                }
            }
            val nics = NetworkAddresses.ipv4Nics()
            if (v4 != null && previous.isNotEmpty()) {
                deliver(v4, nics, previous, goodbye = true)
                if (v6 != null) deliverV6(v6, NetworkAddresses.ipv6Nics(), previous, goodbye = true)
            }
        } finally {
            closeQuiet(v4)
            closeQuiet(v6)
        }
    }

    private fun poll(
        socket: MulticastSocket,
        nics: List<LanNic>,
        who: AirPlayIdentity,
        listen: Int,
        fallbackScope: Int,
    ) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.soTimeout = 400
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return
        } catch (_: InterruptedException) {
            return
        } catch (error: Exception) {
            if (running && error !is java.io.InterruptedIOException) {
                DmrLog.i("airplay mdns recv: ${error.message}")
            }
            return
        }
        val data = packet.data.copyOf(packet.length)
        val peer = SsdpReply.ipv4Literal(packet.address) ?: packet.address?.hostAddress.orEmpty()
        val ipv4 = NetworkAddresses.preferredHost(peer, nics.map { it.ipv4 }, nics.first().ipv4)
        val ad = Mdns.advertisement(who.name, who.deviceId, listen, ipv4, who.publicKeyHex)
        val reply = Mdns.response(data, ad) ?: return
        val names = Mdns.questionNames(data).joinToString()
        val from = SsdpReply.peer(packet.address, packet.port)
        DmrLog.i("airplay mdns query from $from q=$names")
        val target = SsdpReply.unicastTarget(packet, fallbackScope)
        try {
            socket.send(DatagramPacket(reply, reply.size, target.address, target.port))
            DmrLog.i("airplay mdns reply to ${SsdpReply.peer(target.address, target.port)}")
        } catch (error: Exception) {
            DmrLog.i("airplay mdns reply failed: ${error.message}")
        }
    }

    private fun deliver(
        socket: MulticastSocket,
        nics: List<LanNic>,
        ads: List<Mdns.Advertisement>,
        goodbye: Boolean,
    ) {
        if (nics.isEmpty()) return
        val group = InetAddress.getByName(IPV4_GROUP)
        for (ad in ads) {
            val nic = nics.firstOrNull { it.ipv4 == ad.ipv4 } ?: nics.first()
            sendOn(socket, nic.nif, group, Mdns.announce(ad, goodbye), nic.ipv4)
            if (!goodbye) {
                DmrLog.i("airplay mdns announce ${nic.ipv4}:${ad.port} name=${ad.name}")
            }
        }
    }

    private fun deliverV6(
        socket: MulticastSocket,
        nics: List<V6Nic>,
        ads: List<Mdns.Advertisement>,
        goodbye: Boolean,
    ) {
        if (nics.isEmpty() || ads.isEmpty()) return
        val group = InetAddress.getByName(IPV6_GROUP)
        val chosen = ads.first()
        val packets = Mdns.announce(chosen, goodbye)
        for (nic in nics) {
            sendOn(socket, nic.nif, group, packets, nic.name)
        }
    }

    private fun sendOn(
        socket: MulticastSocket,
        nif: NetworkInterface,
        group: InetAddress,
        packets: List<ByteArray>,
        label: String,
    ) {
        try {
            socket.networkInterface = nif
        } catch (error: Exception) {
            DmrLog.i("airplay mdns interface $label: ${error.message}")
            return
        }
        for (payload in packets) {
            try {
                socket.send(DatagramPacket(payload, payload.size, group, MDNS_PORT))
            } catch (error: Exception) {
                if (running) DmrLog.i("airplay mdns send $label: ${error.message}")
            }
        }
    }

    private fun syncJoin(
        socket: MulticastSocket,
        group: InetAddress,
        nics: List<Pair<String, NetworkInterface>>,
        joined: MutableSet<String>,
        failed: MutableSet<String>,
        family: String,
    ) {
        for ((name, nif) in nics) {
            val key = "$family:$name"
            if (key in joined) continue
            try {
                socket.joinGroup(InetSocketAddress(group, MDNS_PORT), nif)
                joined += key
                failed -= key
                DmrLog.i("airplay mdns join $name $family")
            } catch (error: Exception) {
                if (failed.add(key)) DmrLog.i("airplay mdns join $name $family failed: ${error.message}")
            }
        }
    }

    private fun openSocket(ipv6: Boolean, prefer5353: Boolean): Opened? {
        val preferred = if (prefer5353) MDNS_PORT else 0
        try {
            return Opened(bind(ipv6, preferred), ephemeral = false)
        } catch (error: Exception) {
            if (preferred == 0) {
                DmrLog.i("airplay mdns socket: ${error.message}")
                return null
            }
        }
        return try {
            Opened(bind(ipv6, 0), ephemeral = true)
        } catch (error: Exception) {
            DmrLog.i("airplay mdns socket: ${error.message}")
            null
        }
    }

    private fun bind(ipv6: Boolean, port: Int): MulticastSocket {
        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        val any = if (ipv6) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
        socket.bind(InetSocketAddress(any, port))
        try {
            socket.timeToLive = 255
        } catch (_: Exception) {
        }
        try {
            socket.loopbackMode = true
        } catch (_: Exception) {
        }
        return socket
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun closeQuiet(socket: MulticastSocket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private data class Opened(val socket: MulticastSocket, val ephemeral: Boolean)

    companion object {
        private const val MDNS_PORT = 5353
        private const val IPV4_GROUP = "224.0.0.251"
        private const val IPV6_GROUP = "ff02::fb"
        private const val ANNOUNCE_MS = 15_000L
    }
}
