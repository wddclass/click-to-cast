package com.wddclass.clicktocast.dlna

/** UPnP AV identifiers and the sink protocol list advertised to controllers. */
object Upnp {
    /** Out-of-box name phones see in the cast list. Renaming is optional and never prefixed. */
    const val DEFAULT_FRIENDLY_NAME = "投屏点我"

    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    const val SINK_PROTOCOLS =
        "http-get:*:video/mp4:*," +
            "http-get:*:video/mpeg:*," +
            "http-get:*:video/x-flv:*," +
            "http-get:*:video/webm:*," +
            "http-get:*:video/matroska:*," +
            "http-get:*:video/mp2t:*," +
            "http-get:*:application/vnd.apple.mpegurl:*," +
            "http-get:*:application/x-mpegURL:*," +
            "http-get:*:application/dash+xml:*," +
            "http-get:*:audio/mpeg:*," +
            "http-get:*:*:*"

    const val PREFERRED_PORT = 8192
    const val PRODUCT = "ClickToCast"
}

object DmrPaths {
    const val DESCRIPTION = "/dmr/description.xml"
    const val SCPD_AVT = "/dmr/scpd/avt.xml"
    const val SCPD_RCS = "/dmr/scpd/rcs.xml"
    const val SCPD_CMS = "/dmr/scpd/cms.xml"
    const val CONTROL_AVT = "/dmr/control/avt"
    const val CONTROL_RCS = "/dmr/control/rcs"
    const val CONTROL_CMS = "/dmr/control/cms"
    const val EVENT_AVT = "/dmr/event/avt"
    const val EVENT_RCS = "/dmr/event/rcs"
    const val EVENT_CMS = "/dmr/event/cms"

    fun serviceForEvent(path: String): String? = when (path) {
        EVENT_AVT -> Upnp.AVT
        EVENT_RCS -> Upnp.RCS
        EVENT_CMS -> Upnp.CMS
        else -> null
    }
}

object DmrLog {
    var out: (String) -> Unit = { message -> println("ClickToCast $message") }

    fun i(message: String) {
        out(message)
    }
}

fun redactUrl(url: String): String {
    val base = url.substringBefore('?')
    return if ('?' in url) "$base?…" else base
}
