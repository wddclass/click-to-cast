package com.wddclass.clicktocast.dlna

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MSearch(val st: String, val mxSeconds: Int)

object SsdpMessages {
    fun targets(uuid: String): List<Pair<String, String>> {
        val udn = "uuid:$uuid"
        return listOf(
            "upnp:rootdevice" to "$udn::upnp:rootdevice",
            udn to udn,
            Upnp.DEVICE to "$udn::${Upnp.DEVICE}",
            Upnp.AVT to "$udn::${Upnp.AVT}",
            Upnp.RCS to "$udn::${Upnp.RCS}",
            Upnp.CMS to "$udn::${Upnp.CMS}",
        )
    }

    fun matching(st: String, uuid: String): List<Pair<String, String>> {
        val all = targets(uuid)
        val needle = st.trim()
        if (needle.equals("ssdp:all", ignoreCase = true)) return all
        return all.filter { (nt, usn) ->
            nt.equals(needle, ignoreCase = true) || usn.equals(needle, ignoreCase = true)
        }
    }

    fun parseMSearch(packet: String): MSearch? {
        val text = packet.replace("\r\n", "\n")
        val first = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!first.startsWith("M-SEARCH", ignoreCase = true)) return null
        val headers = mutableMapOf<String, String>()
        for (line in text.lineSequence().drop(1)) {
            if (line.isBlank()) break
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        val st = headers["st"] ?: return null
        val mx = headers["mx"]?.toIntOrNull()?.coerceIn(0, 5) ?: 1
        return MSearch(st, mx)
    }

    fun searchResponses(profile: DeviceProfile, st: String, date: String, bootId: Int, configId: Int): List<String> {
        if (profile.hostAddress.isBlank() || profile.httpPort <= 0) return emptyList()
        return matching(st, profile.uuid).map { (nt, usn) ->
            response(nt, usn, profile, date, bootId, configId)
        }
    }

    fun alive(profile: DeviceProfile, date: String, bootId: Int, configId: Int): List<String> =
        targets(profile.uuid).map { (nt, usn) -> notify("ssdp:alive", nt, usn, profile, date, bootId, configId) }

    fun bye(profile: DeviceProfile, date: String, bootId: Int, configId: Int): List<String> =
        targets(profile.uuid).map { (nt, usn) -> notify("ssdp:byebye", nt, usn, profile, date, bootId, configId) }

    fun httpDate(epochMs: Long): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(epochMs))
    }

    private fun response(st: String, usn: String, profile: DeviceProfile, date: String, bootId: Int, configId: Int): String =
        lines(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "DATE: $date",
            "EXT:",
            "LOCATION: ${profile.location}",
            "SERVER: ${profile.serverHeader}",
            "ST: $st",
            "USN: $usn",
            "BOOTID.UPNP.ORG: $bootId",
            "CONFIGID.UPNP.ORG: $configId",
            "CONTENT-LENGTH: 0",
        )

    private fun notify(nts: String, nt: String, usn: String, profile: DeviceProfile, date: String, bootId: Int, configId: Int): String =
        lines(
            "NOTIFY * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: ${profile.location}",
            "NT: $nt",
            "NTS: $nts",
            "SERVER: ${profile.serverHeader}",
            "USN: $usn",
            "BOOTID.UPNP.ORG: $bootId",
            "CONFIGID.UPNP.ORG: $configId",
            "DATE: $date",
        )

    private fun lines(vararg parts: String): String = parts.joinToString("\r\n", postfix = "\r\n\r\n")
}
