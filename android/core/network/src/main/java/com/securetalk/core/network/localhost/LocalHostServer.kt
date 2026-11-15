package com.securetalk.core.network.localhost

import com.securetalk.core.common.model.MediaPacketCodec
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded local host (ARCHITECTURE §2.2, Ktor CIO) implementing the SAME WS
 * protocol as the backend:
 *
 *  - GET /health
 *  - WS /api/v1/ws — envelope routing, floor control (authoritative copy,
 *    [LocalFloorController]), binary media relay fan-out.
 *
 * SECURITY INVARIANT: the host never holds group media keys and CANNOT decrypt
 * media. It parses only the plaintext header (epoch | sender | seq) to check
 * floor ownership and fan the identical binary payload to other members.
 * `keys.envelope` frames are routed by recipient_device_id as opaque bytes.
 */
class LocalHostServer(private val scope: CoroutineScope) {

    private val floor = LocalFloorController()
    private val json = Json { ignoreUnknownKeys = true }

    /** deviceId -> outgoing WS session. */
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    private var engine: ApplicationEngine? = null
    private val startedAt = AtomicLong(0)

    @Volatile
    var boundPort: Int = -1
        private set

    fun start(port: Int = 53317): Result<Int> {
        if (engine != null) return Result.success(boundPort)
        return try {
            val server = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }
            server.start(wait = false)
            engine = server
            // CIO binds to the requested port; if taken, caller retries with 0
            boundPort = port
            startedAt.set(System.currentTimeMillis())
            Result.success(port)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun stop() {
        runCatching { engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000) }
        engine = null
        boundPort = -1
        sessions.clear()
    }

    val isRunning: Boolean get() = engine != null

    private fun Application.module() {
        install(WebSockets)
        routing {
            get("/health") {
                call.respondText(
                    "{\"status\":\"ok\",\"role\":\"local_host\",\"uptime_ms\":${System.currentTimeMillis() - startedAt.get()}}",
                    ContentType.Application.Json,
                )
            }
            webSocket("/api/v1/ws") {
                val deviceId = call.request.queryParameters["device_id"] ?: ""
                if (deviceId.isBlank()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing device_id"))
                    return@webSocket
                }
                sessions[deviceId] = this
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> handleText(deviceId, frame.readText())
                            is Frame.Binary -> handleBinary(deviceId, frame.readBytes())
                            else -> Unit
                        }
                    }
                } catch (_: ClosedReceiveChannelException) {
                    // normal disconnect
                } finally {
                    sessions.remove(deviceId, this)
                    handleDeviceLeft(deviceId)
                }
            }
        }
    }

    private suspend fun handleText(senderId: String, text: String) {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return
        }
        val type = obj["type"]?.jsonPrimitive?.content ?: return
        val data = obj["data"]?.jsonObject ?: JsonObject(emptyMap())
        fun str(key: String): String = data[key]?.jsonPrimitive?.content ?: ""

        when (type) {
            "ping" -> sendTo(senderId, """{"type":"pong","data":{}}""")

            "floor.request" -> {
                val channelId = str("channel_id")
                val result = floor.request(channelId, senderId)
                if (result.granted) {
                    val state = floor.state(channelId)
                    broadcastAll(grantedJson(channelId, senderId, state.leaseExpiresMs))
                    broadcastAll(floorEventJson(channelId, "speaking", senderId))
                } else {
                    sendTo(
                        senderId,
                        """{"type":"floor.denied","data":{"channel_id":"$channelId","reason":"${result.reason}","holder_device_id":"${floor.state(channelId).holder}","queue_position":${result.queuePosition ?: -1}}}""",
                    )
                }
            }

            "floor.release" -> {
                val channelId = str("channel_id")
                val next = floor.release(channelId, senderId)
                broadcastAll(
                    """{"type":"floor.released","data":{"channel_id":"$channelId","previous_holder":"$senderId"}}""",
                )
                if (next != null) {
                    val state = floor.state(channelId)
                    broadcastAll(grantedJson(channelId, next, state.leaseExpiresMs))
                    broadcastAll(floorEventJson(channelId, "speaking", next))
                } else {
                    broadcastAll(floorEventJson(channelId, "idle", null))
                }
            }

            "floor.override" -> {
                // admin/priority: host operator device may override (kept permissive locally)
                val channelId = str("channel_id")
                floor.release(channelId, senderId)
                val result = floor.request(channelId, senderId)
                if (result.granted) {
                    val state = floor.state(channelId)
                    broadcastAll(grantedJson(channelId, senderId, state.leaseExpiresMs))
                }
            }

            "keys.envelope" -> {
                // Opaque pairwise-encrypted sender-key envelope; route by recipient only.
                val recipient = str("recipient_device_id")
                sendTo(recipient, text)
            }

            "presence.update" -> {
                broadcastAllExcept(senderId, text)
            }

            else -> Unit // signaling.* could be relayed here for future WebRTC use
        }
    }

    private suspend fun handleBinary(senderId: String, bytes: ByteArray) {
        // Parse ONLY the plaintext header (epoch|sender|seq); the ciphertext is opaque.
        // Authorization: only the current floor holder on some channel may transmit (§6).
        try {
            MediaPacketCodec.decode(bytes)
        } catch (_: Exception) {
            return
        }
        if (!floor.holders().containsValue(senderId)) return

        sessions.entries
            .filter { it.key != senderId }
            .forEach { runCatching { it.value.send(Frame.Binary(true, bytes)) } }
    }

    private suspend fun handleDeviceLeft(deviceId: String) {
        floor.onDeviceLeft(deviceId).forEach { (channelId, _) ->
            broadcastAll(floorEventJson(channelId, "idle", null))
        }
        broadcastAll("""{"type":"presence.update","data":{"channel_id":"","device_id":"$deviceId","state":"offline"}}""")
    }

    private fun grantedJson(channelId: String, holder: String, leaseExpiresMs: Long) =
        """{"type":"floor.granted","data":{"channel_id":"$channelId","holder_device_id":"$holder","lease_expires_ms":$leaseExpiresMs}}"""

    private fun floorEventJson(channelId: String, state: String, holder: String?) =
        """{"type":"floor.event","data":{"channel_id":"$channelId","state":"$state","holder_device_id":${holder?.let { "\"$it\"" } ?: "null"}}}"""

    private suspend fun sendTo(deviceId: String, text: String) {
        sessions[deviceId]?.send(Frame.Text(text))
    }

    private suspend fun broadcastAll(text: String) {
        sessions.values.forEach { runCatching { it.send(Frame.Text(text)) } }
    }

    private suspend fun broadcastAllExcept(deviceId: String, text: String) {
        sessions.entries.filter { it.key != deviceId }.forEach { runCatching { it.value.send(Frame.Text(text)) } }
    }
}
