package com.wddclass.clicktocast.airplay

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Long-term Ed25519 identity advertised as AirPlay `pk`, plus X25519 and AES-CTR
 * helpers for the legacy pair-verify exchange.
 */
class AirPlayKeys private constructor(seed: ByteArray) {
    private val edPrivate = Ed25519PrivateKeyParameters(seed, 0)
    val edPublic: ByteArray = edPrivate.generatePublicKey().encoded

    val publicKeyHex: String get() = hex(edPublic)

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, edPrivate)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun newX25519(): X25519PrivateKeyParameters = X25519PrivateKeyParameters(SecureRandom())

    companion object {
        fun fromSeed(seed: ByteArray): AirPlayKeys {
            require(seed.size == 32) { "seed" }
            return AirPlayKeys(seed.copyOf())
        }

        /** Stable per installation. The UUID is already random and stored on the TV. */
        fun seedFor(uuid: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update("clicktocast-airplay-ed25519-v1:".toByteArray(Charsets.UTF_8))
            digest.update(uuid.toByteArray(Charsets.UTF_8))
            return digest.digest().copyOf(32)
        }

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            if (publicKey.size != 32 || signature.size != 64) return false
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            return verifier.verifySignature(signature)
        }

        fun sharedSecret(ours: X25519PrivateKeyParameters, theirPublic: ByteArray): ByteArray {
            val secret = ByteArray(32)
            ours.generateSecret(X25519PublicKeyParameters(theirPublic, 0), secret, 0)
            return secret
        }

        fun sha512(label: String, material: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(material)
            return digest.digest()
        }

        /** AES-128-CTR. [skip] bytes of keystream are consumed first, matching a continued counter. */
        fun ctr(key: ByteArray, iv: ByteArray, skip: Int, data: ByteArray): ByteArray {
            val cipher = SICBlockCipher(AESEngine())
            cipher.init(true, ParametersWithIV(KeyParameter(key), iv))
            if (skip > 0) {
                val junk = ByteArray(skip)
                cipher.processBytes(junk, 0, skip, junk, 0)
            }
            val out = ByteArray(data.size)
            if (data.isNotEmpty()) cipher.processBytes(data, 0, data.size, out, 0)
            return out
        }

        fun hex(bytes: ByteArray): String {
            val alphabet = "0123456789abcdef"
            val out = StringBuilder(bytes.size * 2)
            for (value in bytes) {
                val v = value.toInt() and 0xFF
                out.append(alphabet[v ushr 4])
                out.append(alphabet[v and 0x0F])
            }
            return out.toString()
        }

        fun hexPrefix(bytes: ByteArray, max: Int = 16): String {
            val n = minOf(max, bytes.size)
            val shown = hex(bytes.copyOf(n))
            return if (bytes.size > n) "$shown…(${bytes.size})" else shown
        }
    }
}
