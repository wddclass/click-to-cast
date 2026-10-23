package com.wddclass.clicktocast.airplay

import com.wddclass.clicktocast.dlna.DmrLog

/**
 * JNI entry to UxPlay/RPiPlay `playfair_decrypt` (`playfair.c`, `omg_hax.c`).
 * The native library is GPL-3.0-only. Unit tests run without it and get null.
 */
internal object PlayfairNative {
    private val ready: Boolean = try {
        System.loadLibrary("playfair")
        true
    } catch (error: Throwable) {
        DmrLog.i("airplay playfair library not loaded: ${error.javaClass.simpleName}")
        false
    }

    fun decrypt(message3: ByteArray, cipherText: ByteArray): ByteArray? {
        if (!ready || message3.size < 164 || cipherText.size < 72) return null
        return try {
            nativeDecrypt(message3, cipherText)
        } catch (error: Throwable) {
            DmrLog.i("airplay playfair_decrypt threw ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private external fun nativeDecrypt(message3: ByteArray, cipherText: ByteArray): ByteArray
}
