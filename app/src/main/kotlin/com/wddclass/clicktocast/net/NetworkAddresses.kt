package com.wddclass.clicktocast.net

import java.net.Inet4Address
import java.net.NetworkInterface

data class LanNic(val name: String, val ipv4: String, val nif: NetworkInterface)

object NetworkAddresses {
    fun bestIpv4(): String? = ipv4Nics().firstOrNull()?.ipv4

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
