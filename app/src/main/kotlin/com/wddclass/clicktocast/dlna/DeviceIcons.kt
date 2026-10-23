package com.wddclass.clicktocast.dlna

/** PNG icons served at /dmr/icon48.png and /dmr/icon120.png. Phones drop renderers that have no iconList. */
object DeviceIcons {
    val small: ByteArray by lazy { load("dmr/icon48.png") }
    val large: ByteArray by lazy { load("dmr/icon120.png") }

    private fun load(path: String): ByteArray {
        val stream = DeviceIcons::class.java.classLoader?.getResourceAsStream(path)
            ?: DeviceIcons::class.java.getResourceAsStream("/$path")
            ?: return ByteArray(0)
        return stream.use { it.readBytes() }
    }
}
