package com.wddclass.clicktocast.dlna

import java.net.URI

/** Turns a controller User-Agent and the media URL into a short on-screen source line. */
object SenderLabel {
    private val apps = listOf(
        "bilibili" to "bilibili",
        "bilivideo" to "bilibili",
        "biliapi" to "bilibili",
        "iqiyi" to "爱奇艺",
        "qiyi.com" to "爱奇艺",
        "youku" to "优酷",
        "ykimg" to "优酷",
        "mgtv" to "芒果TV",
        "hunantv" to "芒果TV",
        "tencentvideo" to "腾讯视频",
        "qqvideo" to "腾讯视频",
        "v.qq.com" to "腾讯视频",
        "youtube" to "YouTube",
        "youtu.be" to "YouTube",
        "googlevideo" to "YouTube",
        "douyinvod" to "抖音",
        "douyin" to "抖音",
        "aweme" to "抖音",
        "xiaohongshu" to "小红书",
        "xhslink" to "小红书",
        "migu" to "咪咕",
        "infuse" to "Infuse",
        "vlc" to "VLC",
    )

    fun sourceLine(userAgent: String?, metadata: String, uri: String): String {
        val device = deviceName(userAgent)
        val app = appName(userAgent, metadata, uri)
        return if (app == null) "来自 $device" else "来自 $device · $app"
    }

    fun deviceName(userAgent: String?): String {
        val ua = userAgent.orEmpty().lowercase()
        return when {
            ua.contains("ipad") -> "iPad"
            ua.contains("iphone") || ua.contains("ios") || ua.contains("cfnetwork") || ua.contains("darwin") -> "iPhone"
            ua.contains("android") -> "Android"
            ua.contains("mac os") || ua.contains("macintosh") -> "Mac"
            ua.contains("windows") -> "电脑"
            else -> "局域网设备"
        }
    }

    fun appName(userAgent: String?, metadata: String, uri: String): String? {
        val host = runCatching { URI(uri).host.orEmpty() }.getOrDefault("")
        val haystack = buildString {
            append(userAgent.orEmpty().lowercase())
            append(' ')
            append(host.lowercase())
            append(' ')
            append(metadata.take(4000).lowercase())
        }
        return apps.firstOrNull { (token, _) -> haystack.contains(token) }?.second
    }
}
