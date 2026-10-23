package com.wddclass.clicktocast.dlna

/** UPnP AV identifiers and the sink protocol list advertised to controllers. */
object Upnp {
    /** Out-of-box name phones see in the cast list. Renaming is optional and never prefixed. */
    const val DEFAULT_FRIENDLY_NAME = "投屏点我"

    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** Answered on M-SEARCH only. The description still implements the :1 DMR. */
    const val AVT_V2 = "urn:schemas-upnp-org:service:AVTransport:2"
    const val RCS_V2 = "urn:schemas-upnp-org:service:RenderingControl:2"
    const val CMS_V2 = "urn:schemas-upnp-org:service:ConnectionManager:2"
    const val DEVICE_V2 = "urn:schemas-upnp-org:device:MediaRenderer:2"

    /**
     * Sink protocol list returned by ConnectionManager:GetProtocolInfo.
     * Includes the types Chinese video apps and BubbleUPnP look for, plus a wildcard.
     */
    const val SINK_PROTOCOLS =
        "http-get:*:video/mp4:*," +
            "http-get:*:video/mpeg:*," +
            "http-get:*:video/x-flv:*," +
            "http-get:*:video/flv:*," +
            "http-get:*:video/webm:*," +
            "http-get:*:video/matroska:*," +
            "http-get:*:video/x-matroska:*," +
            "http-get:*:video/mp2t:*," +
            "http-get:*:video/MP2T:*," +
            "http-get:*:video/vnd.dlna.mpeg-tts:*," +
            "http-get:*:application/vnd.apple.mpegurl:*," +
            "http-get:*:application/x-mpegURL:*," +
            "http-get:*:application/x-mpegurl:*," +
            "http-get:*:audio/mpegurl:*," +
            "http-get:*:application/dash+xml:*," +
            "http-get:*:audio/mpeg:*," +
            "http-get:*:audio/mp4:*," +
            "http-get:*:audio/aac:*," +
            "http-get:*:audio/x-aac:*," +
            "http-get:*:image/jpeg:*," +
            "http-get:*:image/png:*," +
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
    const val ICON_48 = "/dmr/icon48.png"
    const val ICON_120 = "/dmr/icon120.png"

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
