package com.securetalk.core.network.transport

import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.KeyEnvelope
import com.securetalk.core.network.api.DistributeKeysRequest
import com.securetalk.core.network.api.EnvelopeDto
import com.securetalk.core.network.api.SecureTalkApi
import com.securetalk.core.network.auth.TokenManager
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Internet mode transport (ARCHITECTURE §2.1): HTTPS REST + WSS against the
 * FastAPI backend. Key envelope distribution goes through the REST
 * `keys/distribute` endpoint; media travels as binary WS frames.
 */
class OnlineTransport(
    client: OkHttpClient,
    scope: CoroutineScope,
    private val api: SecureTalkApi,
    private val tokens: TokenManager,
    private var baseUrl: String,
) : BaseWsTransport(client, scope) {

    fun updateBaseUrl(url: String) {
        baseUrl = url
    }

    override fun buildRequest(): Request {
        val httpUrl = baseUrl.trimEnd('/').toHttpUrl()
        val wsUrl = httpUrl.newBuilder()
            .scheme(if (httpUrl.scheme == "https") "wss" else "ws")
            .addPathSegments("api/v1/ws")
            .addQueryParameter("token", tokens.accessToken ?: "")
            .build()
        return Request.Builder().url(wsUrl).build()
    }

    override suspend fun sendKeyEnvelope(envelope: KeyEnvelope): Result<Unit> = try {
        val response = api.distributeKeys(
            envelope.channelId.value,
            DistributeKeysRequest(
                listOf(
                    EnvelopeDto(
                        recipient_device_id = envelope.recipientDeviceId.value,
                        epoch = envelope.epoch,
                        envelope_ciphertext = java.util.Base64.getEncoder().encodeToString(envelope.ciphertext),
                    ),
                ),
            ),
        )
        if (response.isSuccessful) Result.success(Unit) else Result.failure(IllegalStateException("distribute failed: ${response.code()}"))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
