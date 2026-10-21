package com.wddclass.clicktocast.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

data class LanNic(val name: String, val ipv4: String, val nif: NetworkInterface)

data class V6Nic(val name: String, val index: Int, val nif: NetworkInterface)

object NetworkAddresses {
    fun bestIpv4(): String? = ipv4Nics().firstOrNull()?.ipv4

    /** MAC of an up LAN interface, or null when the firmware hides it. */
    fun hardwareMac(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name ?: continue
            if (skip(name)) continue
            val hw = try {
                nif.hardwareAddress
            } catch (_: Exception) {
                null
            } ?: continue
            if (hw.size < 6 || hw.all { it == 0.toByte() }) continue
            return hw.take(6).joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        }
        return null
    }

    fun ipv4Nics(): List<LanNic> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        val found = mutableListOf<LanNic>()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name ?: continue
            if (skip(name)) continue
            for (address in nif.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    val host = address.hostAddress ?: continue
                    found += LanNic(name, host, nif)
                }
            }
        }
        return found.sortedBy { rank(it.name) }
    }

    /** Up interfaces that have an IPv6 address, including link-local, so ff02::c can be joined. */
    fun ipv6Nics(): List<V6Nic> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        val found = mutableListOf<V6Nic>()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name ?: continue
            if (skip(name)) continue
            val has6 = nif.inetAddresses.asSequence().any { it is Inet6Address && !it.isMulticastAddress }
            if (!has6) continue
            val index = try {
                nif.index
            } catch (_: Exception) {
                0
            }
            found += V6Nic(name, index, nif)
        }
        return found.sortedBy { rank(it.name) }
    }

    /** Prefer the address that shares a /24 with the phone that sent M-SEARCH. */
    fun preferredHost(peer: String, locals: List<String>, fallback: String): String {
        val cleanPeer = peer.substringBefore('%').trim()
        val same = locals.firstOrNull { same24(it, cleanPeer) }
        if (same != null) return same
        return locals.firstOrNull { it.isNotBlank() } ?: fallback
    }

    fun same24(local: String, peer: String): Boolean {
        val left = local.split('.')
        val right = peer.split('.')
        if (left.size != 4 || right.size != 4) return false
        return left[0] == right[0] && left[1] == right[1] && left[2] == right[2]
    }

    private fun skip(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "lo" ||
            normalized.startsWith("docker") ||
            normalized.startsWith("br-") ||
            normalized.startsWith("veth") ||
            normalized.startsWith("tun") ||
            normalized.startsWith("dummy")
    }

    private fun rank(name: String): Int {
        val normalized = name.lowercase()
        return when {
            normalized.startsWith("eth") || normalized.startsWith("en") -> 0
            normalized.startsWith("wlan") || normalized.startsWith("wifi") -> 1
            else -> 2
        }
    }
}
