package com.wddclass.clicktocast.airplay

import com.wddclass.clicktocast.dlna.DmrLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Ports named in the RTSP SETUP reply. Timing is UDP (NTP). Mirror video is TCP
 * with 128-byte headers. Type 96 audio uses its own UDP data and control ports.
 */
internal class AirPlayPorts(private val mirror: MirrorSession? = null) {
    @Volatile var timingPort: Int = 0
        private set

    @Volatile var dataPort: Int = 0
        private set

    @Volatile var audioPort: Int = 0
        private set

    @Volatile var audioControlPort: Int = 0
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
                mirror?.attachTiming(udp)
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
            audioPort = bindUdp("airplay-audio") { audioLoop(it) }
            audioControlPort = bindUdp("airplay-audio-ctl") { controlLoop(it) }
            DmrLog.i(
                "airplay ports timing=$timingPort data=$dataPort audio=$audioPort audioControl=$audioControlPort",
            )
            DmrLog.i("airplay audio ports data=$audioPort control=$audioControlPort")
        }
    }

    private fun bindUdp(name: String, loop: (DatagramSocket) -> Unit): Int {
        return try {
            val udp = DatagramSocket(null)
            udp.reuseAddress = true
            udp.bind(InetSocketAddress(0))
            Thread({ loop(udp) }, name).apply { isDaemon = true }.start()
            udp.localPort
        } catch (error: Throwable) {
            DmrLog.i("airplay $name port failed: ${error.message}")
            0
        }
    }

    private fun timingLoop(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val copy = packet.data.copyOf(packet.length)
                val address = packet.address ?: continue
                val reply = mirror?.onTiming(copy, address, packet.port)
                if (reply != null) {
                    socket.send(DatagramPacket(reply, reply.size, address, packet.port))
                } else if (mirror == null) {
                    DmrLog.i(
                        "airplay timing ${packet.length} bytes from ${address.hostAddress}:${packet.port}",
                    )
                }
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
            val session = mirror
            if (session != null) {
                Thread({ session.readVideo(client) }, "airplay-mirror-io").apply { isDaemon = true }.start()
            } else {
                Thread({ readMirror(client) }, "airplay-mirror-io").apply { isDaemon = true }.start()
            }
        }
    }

    private fun audioLoop(socket: DatagramSocket) {
        val buffer = ByteArray(4096)
        var logged = 0
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val copy = packet.data.copyOf(packet.length)
                val session = mirror
                if (session != null) {
                    session.onAudioDatagram(copy)
                } else if (logged < 3) {
                    logged++
                    DmrLog.i("airplay audio ${packet.length} from ${packet.address?.hostAddress}:${packet.port}")
                }
            } catch (error: Throwable) {
                if (!socket.isClosed) DmrLog.i("airplay audio read failed: ${error.message}")
                return
            }
        }
    }

    private fun controlLoop(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val copy = packet.data.copyOf(packet.length)
                val session = mirror
                if (session != null) {
                    session.onAudioControl(copy)
                } else {
                    DmrLog.i(
                        "airplay audio control ${packet.length} from ${packet.address?.hostAddress}:${packet.port}",
                    )
                }
            } catch (error: Throwable) {
                if (!socket.isClosed) DmrLog.i("airplay audio control failed: ${error.message}")
                return
            }
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
                DmrLog.i("airplay mirror first bytes=$count hex=${AirPlayKeys.hexPrefix(buffer.copyOf(count), 24)}")
            } else {
                DmrLog.i("airplay mirror closed before data from $remote")
            }
            while (client.getInputStream().read(buffer) > 0) {
                // Keep the socket open so the sender does not see a reset.
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
