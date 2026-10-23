package com.wddclass.clicktocast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class MirrorTest {
    @Test
    fun sha512MixAndStreamLabelsMatchUxPlay() {
        val aes = ByteArray(16) { it.toByte() }
        val ecdh = ByteArray(32) { (it + 3).toByte() }
        val mixed = MirrorKeys.mixWithEcdh(aes, ecdh)
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(aes)
        digest.update(ecdh)
        assertTrue(mixed.contentEquals(digest.digest().copyOf(16)))
        assertTrue(MirrorKeys.mixWithEcdh(aes, null).contentEquals(aes))

        val (key, iv) = MirrorKeys.streamMaterial(mixed, 42L)
        assertTrue(key.contentEquals(labeled("AirPlayStreamKey42", mixed)))
        assertTrue(iv.contentEquals(labeled("AirPlayStreamIV42", mixed)))
        assertFalse(key.contentEquals(iv))
    }

    @Test
    fun unsignedStreamIdUsesFullWidth() {
        assertEquals("42", MirrorKeys.unsignedDecimal(42L))
        assertEquals("18446744073709551615", MirrorKeys.unsignedDecimal(-1L))
    }

    @Test
    fun headerAndH264Payload() {
        val header = ByteArray(128)
        header[0] = 100
        header[4] = 1
        header[5] = 0x16
        putFloat(header, 40, 1920f)
        putFloat(header, 44, 1080f)
        val parsed = MirrorPackets.header(header)
        assertNotNull(parsed)
        assertEquals(100, parsed!!.payloadSize)
        assertEquals(1, parsed.type)
        assertEquals(0x16, parsed.option)
        assertEquals(1920, parsed.width)
        assertEquals(1080, parsed.height)

        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())
        // 6-byte prefix, BE SPS size, SPS, one PPS-count byte, BE PPS size, PPS.
        val payload = ByteArray(sps.size + pps.size + 11)
        payload[6] = 0
        payload[7] = sps.size.toByte()
        sps.copyInto(payload, 8)
        payload[8 + sps.size] = 1
        payload[sps.size + 9] = 0
        payload[sps.size + 10] = pps.size.toByte()
        pps.copyInto(payload, sps.size + 11)
        val sets = MirrorPackets.extractH264(payload)
        assertNotNull(sets)
        assertTrue(sets!!.first.contentEquals(sps))
        assertTrue(sets.second.contentEquals(pps))

        val nal = byteArrayOf(0, 0, 0, 4, 0x65, 1, 2, 3)
        val annex = MirrorPackets.toAnnexB(nal)
        assertNotNull(annex)
        assertEquals(0, annex!![0].toInt())
        assertEquals(0, annex[1].toInt())
        assertEquals(0, annex[2].toInt())
        assertEquals(1, annex[3].toInt())
        assertEquals(0x65, annex[4].toInt() and 0xFF)
        assertNull(MirrorPackets.toAnnexB(byteArrayOf(0, 0, 0, 8, 1, 2)))
    }

    @Test
    fun timingRequestAndReply() {
        val request = MirrorTiming.request(1_700_000_000_000_000_000L)
        assertEquals(32, request.size)
        assertEquals(0x80, request[0].toInt() and 0xFF)
        assertEquals(0xD2, request[1].toInt() and 0xFF)
        assertEquals(0x07, request[3].toInt() and 0xFF)
        val reply = MirrorTiming.reply(request, 1_700_000_000_100_000_000L, 1_700_000_000_200_000_000L)
        assertEquals(0xD3, reply[1].toInt() and 0xFF)
        assertTrue(request.copyOfRange(24, 32).contentEquals(reply.copyOfRange(8, 16)))
    }

    private fun labeled(label: String, key: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(label.toByteArray(Charsets.US_ASCII))
        digest.update(key)
        return digest.digest().copyOf(16)
    }

    private fun putFloat(bytes: ByteArray, offset: Int, value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        bytes[offset] = bits.toByte()
        bytes[offset + 1] = (bits ushr 8).toByte()
        bytes[offset + 2] = (bits ushr 16).toByte()
        bytes[offset + 3] = (bits ushr 24).toByte()
    }
}
