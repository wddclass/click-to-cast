package com.wddclass.clicktocast.dlna

import java.net.HttpURLConnection
import java.net.URL

object StreamSniffer {
    fun sniff(url: String, userAgent: String, headers: Map<String, String>): MediaKind {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return MediaKindDetector.detect(url, null, null)
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            useCaches = false
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Range", "bytes=0-2047")
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            val code = connection.responseCode
            val type = connection.contentType
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val buffer = ByteArray(2048)
            val count = stream?.read(buffer) ?: -1
            val head = if (count > 0) buffer.copyOf(count) else null
            MediaKindDetector.detect(url, type, head)
        } catch (_: Exception) {
            MediaKindDetector.detect(url, null, null)
        } finally {
            connection.disconnect()
        }
    }
}
