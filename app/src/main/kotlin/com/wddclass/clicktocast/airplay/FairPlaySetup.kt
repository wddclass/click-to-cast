package com.wddclass.clicktocast.airplay

/**
 * Fixed FPLY replies for `POST /fp-setup`.
 *
 * Phase 1 is a 16-byte request; byte 14 selects one of four 142-byte replies.
 * Phase 2 is a 164-byte request; the reply is a 12-byte header plus the last
 * 20 bytes of the request. Unwrapping the ekey is `playfair_decrypt` in the
 * bundled GPL playfair library (`app/src/main/cpp/playfair`).
 *
 * The four replies are the well-known FairPlay interop constants. They are
 * Apple-derived. Kotlin here is original. See THIRD_PARTY_NOTICES.md.
 */
internal object FairPlaySetup {
    private val header = hex("46504c590301040000000014")
    private val replies = arrayOf(
        hex("46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba"),
        hex("46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a"),
        hex("46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce"),
        hex("46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359"),
    )

    fun reply(request: ByteArray): ByteArray? {
        if (request.size < 16 || !isFply(request)) return null
        if ((request[4].toInt() and 0xFF) != 3 || (request[5].toInt() and 0xFF) != 1) return null
        return when {
            request.size == 16 -> replies.getOrNull(request[14].toInt() and 0xFF)?.copyOf()
            request.size >= 164 -> header + request.copyOfRange(request.size - 20, request.size)
            else -> null
        }
    }

    private fun isFply(request: ByteArray): Boolean =
        request[0] == 0x46.toByte() &&
            request[1] == 0x50.toByte() &&
            request[2] == 0x4c.toByte() &&
            request[3] == 0x59.toByte()

    private fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            out[index] = text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
