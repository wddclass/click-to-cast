package com.wddclass.clicktocast.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddresses {
    fun bestIpv4(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") || name == "lo") {
                continue
            }
            for (address in nif.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    val host = address.hostAddress ?: continue
                    candidates += host to name
                }
            }
        }
        return candidates.minByOrNull { (_, name) ->
            when {
                name.startsWith("eth") || name.startsWith("en") -> 0
                name.startsWith("wlan") || name.startsWith("wifi") -> 1
                else -> 2
            }
        }?.first
    }
}
