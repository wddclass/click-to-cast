package com.wddclass.clicktocast.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.wddclass.clicktocast.dlna.DmrLog

/**
 * Publishes _airplay._tcp and _raop._tcp so iOS can see the receiver.
 * Video playback is the HTTP /play URL flow. RTP audio and FairPlay mirroring are not served.
 */
class AirPlayAdvertiser(
    context: Context,
    private val port: () -> Int,
    private val identity: () -> AirPlayIdentity,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    fun start() {
        stop()
        val manager = nsd
        if (manager == null) {
            DmrLog.i("airplay mDNS unavailable")
            return
        }
        val who = identity()
        val listen = port()
        if (listen <= 0) return
        register(
            manager,
            "_airplay._tcp.",
            who.name.ifBlank { "投屏点我" },
            listen,
            AirPlayTexts.airplayTxt(who.deviceId),
        )
        register(
            manager,
            "_raop._tcp.",
            AirPlayTexts.raopInstanceName(who.deviceId, who.name),
            listen,
            AirPlayTexts.raopTxt(who.deviceId),
        )
        DmrLog.i("airplay mDNS announce name=${who.name} deviceid=${who.deviceId} port=$listen")
    }

    fun stop() {
        val manager = nsd ?: return
        val current = listeners.toList()
        listeners.clear()
        for (listener in current) {
            try {
                manager.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
    }

    private fun register(
        manager: NsdManager,
        type: String,
        name: String,
        listen: Int,
        txt: Map<String, String>,
    ) {
        val info = NsdServiceInfo()
        info.serviceName = name
        info.serviceType = type
        info.port = listen
        for ((key, value) in txt) {
            info.setAttribute(key, value)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                DmrLog.i("airplay mDNS registered ${serviceInfo.serviceType} name=${serviceInfo.serviceName} port=${serviceInfo.port}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DmrLog.i("airplay mDNS failed ${serviceInfo.serviceType} code=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DmrLog.i("airplay mDNS unregister failed code=$errorCode")
            }
        }
        listeners += listener
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            DmrLog.i("airplay mDNS register $type: ${error.message}")
        }
    }
}
