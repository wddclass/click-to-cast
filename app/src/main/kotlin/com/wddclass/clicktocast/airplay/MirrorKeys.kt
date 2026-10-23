package com.wddclass.clicktocast.airplay

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Screen-mirror key schedule used by current UxPlay.
 *
 * `playfair_decrypt` yields 16 bytes. When pair-verify produced a 32-byte X25519
 * secret, those 16 bytes are replaced by the first 16 bytes of
 * SHA-512(aeskey || ecdh_secret). The video AES-CTR key and IV are then the
 * first 16 bytes of SHA-512("AirPlayStreamKey" + decimal id || aeskey) and
 * SHA-512("AirPlayStreamIV" + decimal id || aeskey). UxPlay's log text says
 * SHA-256; `sha_init` in its crypto.c is EVP_sha512.
 */
internal object MirrorKeys {
    fun mixWithEcdh(aesKey: ByteArray, ecdhSecret: ByteArray?): ByteArray {
        val key = aesKey.copyOf(16)
        if (ecdhSecret == null || ecdhSecret.size < 32) return key
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(key)
        digest.update(ecdhSecret, 0, 32)
        return digest.digest().copyOf(16)
    }

    fun streamMaterial(aesKey: ByteArray, streamConnectionId: Long): Pair<ByteArray, ByteArray> {
        val id = unsignedDecimal(streamConnectionId)
        return hashLabel("AirPlayStreamKey$id", aesKey) to hashLabel("AirPlayStreamIV$id", aesKey)
    }

    fun unsignedDecimal(value: Long): String {
        if (value >= 0L) return value.toString()
        return BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64)).toString()
    }

    private fun hashLabel(label: String, aesKey: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(label.toByteArray(Charsets.US_ASCII))
        digest.update(aesKey, 0, 16)
        return digest.digest().copyOf(16)
    }
}
