package com.wddclass.clicktocast.dlna

/**
 * Chinese playback errors. HTTP 401, 403 and 410 are treated as an expired cast URL.
 * Error-code numbers match Media3 PlaybackException so this stays free of Android types.
 */
object PlaybackMessages {
    const val EXPIRED = "播放地址已失效，请在手机上重新投屏"

    fun describe(errorCode: Int, httpStatus: Int?): String = when {
        httpStatus == 401 || httpStatus == 403 || httpStatus == 410 -> EXPIRED
        errorCode == 2001 || errorCode == 2002 -> "网络连接失败"
        errorCode == 2004 || errorCode == 2005 -> "视频地址无法打开"
        errorCode == 4001 || errorCode == 4003 || errorCode == 4005 -> "这部电视解不了这个视频"
        errorCode == 3001 || errorCode == 3002 || errorCode == 3003 -> "视频格式无法识别"
        else -> "播放失败"
    }
}
