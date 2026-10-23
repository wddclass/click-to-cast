package com.wddclass.clicktocast.dlna

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** Where an M-SEARCH reply must go, and how to keep the source port at 1900. */
object SsdpReply {
    const val PORT = 1900

    /**
     * Reply to the sender's ip:port. Link-local IPv6 needs a scope id or the
     * kernel drops the datagram. iOS often sends M-SEARCH from fe80::/10.
     */
    fun unicastTarget(received: DatagramPacket, fallbackScope: Int): InetSocketAddress {
        val address = received.address ?: return InetSocketAddress(received.port)
        return unicastTarget(address, received.port, fallbackScope)
    }

    fun unicastTarget(address: InetAddress, port: Int, fallbackScope: Int): InetSocketAddress {
        if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0 && fallbackScope > 0) {
            val scoped = Inet6Address.getByAddress(null, address.address, fallbackScope)
            return InetSocketAddress(scoped, port)
        }
        return InetSocketAddress(address, port)
    }

    /** IPv4 literal, or the IPv4 embedded in an IPv4-mapped IPv6 address. */
    fun ipv4Literal(address: InetAddress?): String? {
        if (address == null) return null
        if (address is Inet4Address) return address.hostAddress
        if (address !is Inet6Address) return null
        val bytes = address.address
        if (bytes.size != 16) return null
        val mapped = bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() &&
            bytes[11] == 0xff.toByte()
        if (!mapped) return null
        return InetAddress.getByAddress(bytes.copyOfRange(12, 16)).hostAddress
    }

    fun peer(address: InetAddress?, port: Int): String {
        if (address == null) return ":$port"
        val host = address.hostAddress ?: "?"
        val scope = if (address is Inet6Address && address.scopeId != 0) "%${address.scopeId}" else ""
        return "$host$scope:$port"
    }
}
