package com.wddclass.clicktocast.airplay

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR for AirPlay mirror video.
 *
 * The counter is the 16-byte stream IV and increments as a big-endian integer,
 * the same way OpenSSL's EVP_aes_128_ctr does inside UxPlay `mirror_buffer_decrypt`.
 * Only encrypted video (header byte 4 == 0) is fed in, in order. A partial block
 * at the end of one packet continues into the next. Header byte 5 == 0x10 marks
 * an IDR and does not restart the counter.
 */
internal class MirrorCtr(key: ByteArray, iv: ByteArray) {
    private val aes = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"))
    }
    private val counter = iv.copyOf(16)
    private var keystream = ByteArray(16)
    private var keystreamOff = 16

    fun decrypt(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        val out = ByteArray(input.size)
        for (index in input.indices) {
            out[index] = (input[index].toInt() xor nextKeystreamByte().toInt()).toByte()
        }
        return out
    }

    private fun nextKeystreamByte(): Byte {
        if (keystreamOff == 16) {
            keystream = aes.update(counter) ?: aes.doFinal(counter)
            keystreamOff = 0
            increment(counter)
        }
        return keystream[keystreamOff++]
    }

    private fun increment(block: ByteArray) {
        for (index in 15 downTo 0) {
            val sum = (block[index].toInt() and 0xFF) + 1
            block[index] = sum.toByte()
            if (sum <= 0xFF) return
        }
    }
}
