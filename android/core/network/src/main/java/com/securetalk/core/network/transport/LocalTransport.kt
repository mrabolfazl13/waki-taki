package com.securetalk.core.network.transport

import com.securetalk.core.network.auth.TokenManager
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Local (offline) mode transport (ARCHITECTURE §2.2): plain HTTP+WS against an
 * Android local host service (Ktor) running on another device in the same
 * Wi-Fi/hotspot. The host only routes opaque envelopes — it never holds keys.
 */
class LocalTransport(
    client: OkHttpClient,
    scope: CoroutineScope,
    private val tokens: TokenManager,
    private var host: String = "127.0.0.1",
    private var port: Int = 53317,
    deviceId: String = "",
) : BaseWsTransport(client, scope, deviceId) {

    fun updateEndpoint(newHost: String, newPort: Int) {
        host = newHost
        port = newPort
    }

    override fun buildRequest(): Request {
        // LocalHostServer authenticates by device_id, not by an internet JWT.
        val id = deviceId.ifBlank { "unknown-device" }
        val url = "ws://$host:$port/api/v1/ws?device_id=$id"
        return Request.Builder().url(url).build()
    }
}
