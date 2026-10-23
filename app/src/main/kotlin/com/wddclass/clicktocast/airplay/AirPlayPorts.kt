package com.wddclass.clicktocast.airplay

import com.wddclass.clicktocast.dlna.DmrLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Ports named in the RTSP SETUP reply. Timing is logged. The data port accepts
 * the mirroring socket and logs the first bytes. The H.264 payload stays
 * encrypted; FairPlay key unwrap is not included.
 */
internal class AirPlayPorts {
    @Volatile var timingPort: Int = 0
        private set

    @Volatile var dataPort: Int = 0
        private set

    private val lock = Any()
    private var started = false

    fun ensure() {
        synchronized(lock) {
            if (started) return
            started = true
            try {
                val udp = DatagramSocket(null)
                udp.reuseAddress = true
                udp.bind(InetSocketAddress(0))
                timingPort = udp.localPort
                Thread({ timingLoop(udp) }, "airplay-timing").apply { isDaemon = true }.start()
            } catch (error: Throwable) {
                DmrLog.i("airplay timing port failed: ${error.message}")
            }
            try {
                val tcp = ServerSocket(0)
                dataPort = tcp.localPort
                Thread({ acceptLoop(tcp) }, "airplay-mirror").apply { isDaemon = true }.start()
            } catch (error: Throwable) {
                DmrLog.i("airplay data port failed: ${error.message}")
            }
            DmrLog.i("airplay ports timing=$timingPort data=$dataPort")
        }
    }

    private fun timingLoop(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                DmrLog.i(
                    "airplay timing ${packet.length} bytes from ${packet.address?.hostAddress}:${packet.port}",
                )
            } catch (error: Throwable) {
                if (!socket.isClosed) DmrLog.i("airplay timing read failed: ${error.message}")
                return
            }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (error: Throwable) {
                if (!server.isClosed) DmrLog.i("airplay data accept failed: ${error.message}")
                return
            }
            Thread({ readMirror(client) }, "airplay-mirror-io").apply { isDaemon = true }.start()
        }
    }

    private fun readMirror(client: Socket) {
        val remote = client.inetAddress?.hostAddress ?: "?"
        DmrLog.i("airplay mirror connected from $remote")
        try {
            client.soTimeout = 15_000
            val buffer = ByteArray(64)
            val count = client.getInputStream().read(buffer)
            if (count > 0) {
                DmrLog.i(
                    "airplay mirror first bytes=$count hex=${AirPlayKeys.hexPrefix(buffer.copyOf(count), 24)}; " +
                        "payload is still FairPlay-encrypted, no MediaCodec feed",
                )
            } else {
                DmrLog.i("airplay mirror closed before data from $remote")
            }
            while (client.getInputStream().read(buffer) > 0) {
                // Keep the socket open so the sender does not see a reset. Bytes stay encrypted.
            }
        } catch (error: Throwable) {
            DmrLog.i("airplay mirror ended from $remote: ${error.message}")
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }
}
