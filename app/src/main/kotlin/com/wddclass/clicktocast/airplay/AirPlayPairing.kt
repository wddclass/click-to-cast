package com.wddclass.clicktocast.airplay

/**
 * Legacy AirPlay pair-setup / pair-verify.
 *
 * pair-setup is a raw 32-byte Ed25519 public key each way. pair-verify then does an
 * ephemeral X25519 exchange and Ed25519 signatures encrypted with AES-128-CTR. The
 * AES key and IV are the first 16 bytes of SHA-512("Pair-Verify-AES-Key"/"Pair-Verify-AES-IV" || shared).
 * The first client message is `01 00 00 00` plus the two public keys. The second is
 * `00 00 00 00` plus the client signature encrypted after 64 bytes of that keystream.
 * This is the exchange iOS 26 sent (content-length 32) before video URL playback.
 * It is not HomeKit SRP, and it does not encrypt later /play requests.
 */
class AirPlayPairing(private val keys: AirPlayKeys) {
    private val lock = Any()
    private val pending = HashMap<String, VerifyState>()
    private val setupKeys = HashMap<String, ByteArray>()
    private val verified = HashSet<String>()

    fun isVerified(remoteIp: String): Boolean = synchronized(lock) { remoteIp in verified }

    fun pairSetup(remoteIp: String, body: ByteArray): ByteArray? {
        if (body.size != 32) return null
        synchronized(lock) { setupKeys[remoteIp] = body.copyOf() }
        return keys.edPublic.copyOf()
    }

    fun pairVerify(remoteIp: String, body: ByteArray): ByteArray? {
        if (isFirst(remoteIp, body)) return startVerify(remoteIp, body)
        return finishVerify(remoteIp, body)
    }

    private fun startVerify(remoteIp: String, body: ByteArray): ByteArray {
        val offset = if (body.size == 68) 4 else 0
        val clientX = body.copyOfRange(offset, offset + 32)
        val clientEd = body.copyOfRange(offset + 32, offset + 64)
        val ours = keys.newX25519()
        val serverX = ours.generatePublicKey().encoded
        val shared = AirPlayKeys.sharedSecret(ours, clientX)
        val aesKey = AirPlayKeys.sha512("Pair-Verify-AES-Key", shared).copyOf(16)
        val aesIv = AirPlayKeys.sha512("Pair-Verify-AES-IV", shared).copyOf(16)
        val signature = keys.sign(serverX + clientX)
        val encrypted = AirPlayKeys.ctr(aesKey, aesIv, 0, signature)
        synchronized(lock) {
            pending[remoteIp] = VerifyState(clientEd, clientX, serverX, aesKey, aesIv)
        }
        return serverX + encrypted
    }

    private fun finishVerify(remoteIp: String, body: ByteArray): ByteArray? {
        val state = synchronized(lock) { pending[remoteIp] } ?: return null
        val cipher = when {
            body.size == 68 && body[0] == 0.toByte() -> body.copyOfRange(4, 68)
            body.size == 64 -> body
            else -> return null
        }
        val signature = AirPlayKeys.ctr(state.aesKey, state.aesIv, 64, cipher)
        val message = state.clientX + state.serverX
        if (!AirPlayKeys.verify(state.clientEd, message, signature)) return null
        synchronized(lock) {
            pending.remove(remoteIp)
            verified.add(remoteIp)
        }
        return ByteArray(0)
    }

    fun clientSetupKey(remoteIp: String): ByteArray? = synchronized(lock) { setupKeys[remoteIp]?.copyOf() }

    private fun isFirst(remoteIp: String, body: ByteArray): Boolean {
        if (body.size == 68 && body[0] == 1.toByte() && body[1] == 0.toByte()) return true
        if (body.size != 64) return false
        return synchronized(lock) { pending[remoteIp] == null }
    }

    private class VerifyState(
        val clientEd: ByteArray,
        val clientX: ByteArray,
        val serverX: ByteArray,
        val aesKey: ByteArray,
        val aesIv: ByteArray,
    )
}
