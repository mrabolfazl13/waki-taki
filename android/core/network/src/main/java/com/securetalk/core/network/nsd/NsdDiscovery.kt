package com.securetalk.core.network.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.securetalk.core.common.model.LocalHostInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * NSD/DNS-SD discovery of local hosts advertising `_securetalk._tcp` (ARCHITECTURE §2.2).
 * Registration side (host device) lives in the local host service.
 */
class NsdDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Blocks up to [timeoutMs] collecting discovered hosts. */
    suspend fun discover(timeoutMs: Long = 5_000L): List<LocalHostInfo> {
        val hosts = mutableListOf<LocalHostInfo>()
        return suspendCancellableCoroutine { cont ->
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (cont.isActive) cont.resume(hosts)
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host?.hostAddress ?: return
                            synchronized(hosts) {
                                if (hosts.none { it.address == address && it.port == info.port }) {
                                    hosts.add(LocalHostInfo(info.serviceName, address, info.port))
                                }
                            }
                        }
                    })
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            val timerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val handle = timerScope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                runCatching { nsdManager.stopServiceDiscovery(discovery) }
                if (cont.isActive) cont.resume(hosts.toList())
            }
            cont.invokeOnCancellation { handle.cancel() }
        }
    }

    /** Registers this device as an advertised host. Returns a deregister handle. */
    fun register(serviceName: String, port: Int, onRegistered: (Boolean) -> Unit) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }
        nsdManager.registerService(
            info,
            NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = onRegistered(true)
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onRegistered(false)
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            },
        )
    }

    companion object {
        const val SERVICE_TYPE = "_securetalk._tcp."
        const val DEFAULT_PORT = 53317
    }
}
