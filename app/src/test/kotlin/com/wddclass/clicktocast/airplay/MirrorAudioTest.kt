package com.wddclass.clicktocast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MirrorAudioTest {
    private val key = ByteArray(16) { (it + 3).toByte() }
    private val iv = ByteArray(16) { (0x40 + it).toByte() }

    @Test
    fun cbcResetsTheIvAndKeepsTheTail() {
        val first = ByteArray(32) { (it + 1).toByte() }
        val second = ByteArray(32) { (0x50 + it).toByte() }
        val tail = byteArrayOf(9, 8, 7)
        val packet = rtp(seq = 7, rtp = 480, payload = encrypt(first) + tail)
        val plain = MirrorAudio.decrypt(packet, key, iv)
        assertArrayEquals(first + tail, plain)
        val again = MirrorAudio.decrypt(rtp(seq = 8, rtp = 960, payload = encrypt(second)), key, iv)
        assertArrayEquals(second, again)
    }

    @Test
    fun emptyMarkerIsNotDecrypted() {
        val packet = ByteArray(16)
        packet[0] = 0x80.toByte()
        MirrorAudio.NO_DATA.copyInto(packet, 12)
        val plain = MirrorAudio.decrypt(packet, key, iv)
        assertTrue(MirrorAudio.isNoData(plain!!))
        assertEquals(7, MirrorAudio.sequence(rtp(seq = 7, rtp = 480, payload = ByteArray(16))))
        assertEquals(480L, MirrorAudio.timestamp(rtp(seq = 1, rtp = 480, payload = ByteArray(16))))
    }

    @Test
    fun controlSyncAndResend() {
        val packet = ByteArray(20)
        packet[0] = 0x80.toByte()
        packet[1] = 0xD4.toByte()
        packet[4] = 0
        packet[5] = 0
        packet[6] = 0x01
        packet[7] = 0xE0.toByte()
        packet[8] = 0x11
        val sync = MirrorAudio.sync(packet)
        assertEquals(480L, sync?.first)
        assertEquals(0x11L shl 56, sync?.second)
        val resent = ByteArray(20)
        resent[1] = 0xD6.toByte()
        resent[4] = 0x80.toByte()
        val wrapped = MirrorAudio.resent(resent)
        assertEquals(16, wrapped?.size)
        assertEquals(0x80.toByte(), wrapped?.get(0))
        assertNull(MirrorAudio.sync(ByteArray(8)))
    }

    @Test
    fun volumeMapsDecibelsAndParsesText() {
        assertEquals(1f, MirrorAudio.linearGain(0f), 0.0001f)
        assertEquals(0f, MirrorAudio.linearGain(-144f), 0.0001f)
        assertEquals(0.5f, MirrorAudio.linearGain(-6.0206f), 0.01f)
        assertEquals(-30f, MirrorAudio.volumeDb("volume: -30.000000\r\n"))
        assertEquals(0f, MirrorAudio.volumeDb("volume: 4\r\n"))
        assertNull(MirrorAudio.volumeDb("progress: 1/2/3\r\n"))
        assertEquals("volume: 0.000000\r\n", MirrorAudio.formatVolume(0f))
        assertEquals("volume: -11.500000\r\n", MirrorAudio.formatVolume(-11.5f))
        assertEquals(480L * 1_000_000_000L / 44_100L, MirrorAudio.leadNs(960, 480))
    }

    @Test
    fun jitterDropsDuplicatesAndWaitsForOrder() {
        val jitter = AudioJitter()
        assertNull(jitter.enqueue(2, 960, byteArrayOf(2)))
        assertEquals("dup", jitter.enqueue(2, 960, byteArrayOf(2)))
        assertNull(jitter.enqueue(1, 480, byteArrayOf(1)))
        assertNull(jitter.dequeue(force = false))
        assertEquals(1, jitter.dequeue(force = true)?.seq)
        assertEquals(2, jitter.dequeue(force = true)?.seq)
        assertEquals("late", jitter.enqueue(1, 480, byteArrayOf(1)))
        jitter.clear()
        assertEquals(0, jitter.pending())
    }

    private fun rtp(seq: Int, rtp: Long, payload: ByteArray): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = 0x60.toByte()
        packet[2] = (seq shr 8).toByte()
        packet[3] = seq.toByte()
        packet[4] = (rtp shr 24).toByte()
        packet[5] = (rtp shr 16).toByte()
        packet[6] = (rtp shr 8).toByte()
        packet[7] = rtp.toByte()
        payload.copyInto(packet, 12)
        return packet
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }
}
