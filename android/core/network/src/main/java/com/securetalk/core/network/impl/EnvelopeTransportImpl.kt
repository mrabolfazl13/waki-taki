package com.securetalk.core.network.impl

import com.securetalk.core.common.domain.CommunicationTransport
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.KeyEnvelope
import com.securetalk.core.common.protocol.WsEvent
import com.securetalk.core.network.api.AckEnvelopesRequest
import com.securetalk.core.network.api.DistributeKeysRequest
import com.securetalk.core.network.api.EnvelopeDto
import com.securetalk.core.network.api.SecureTalkApi
import com.securetalk.core.security.group.EnvelopeTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * Bridges the security layer's envelope transport to the network layer:
 * online mode uses REST `keys/distribute` + `keys/envelopes` (ack + delete);
 * local mode uses the opaque `keys.envelope` WS relay (§2.2).
 */
class EnvelopeTransportImpl(
    private val api: SecureTalkApi,
    private val transport: CommunicationTransport,
    private val isOnlineMode: () -> Boolean,
    private val ownDeviceId: () -> String,
) : EnvelopeTransport {

    override val incoming: Flow<KeyEnvelope> = transport.events.map { event ->
        when (event) {
            is WsEvent.KeyEnvelopeReceived -> KeyEnvelope(
                event.channelId,
                event.epoch,
                event.senderDeviceId,
                DeviceId(ownDeviceId()),
                event.envelope,
            )
            else -> null
        }
    }.filterIsInstance<KeyEnvelope>()

    override suspend fun distribute(envelopes: List<KeyEnvelope>): Result<Unit> = try {
        if (isOnlineMode()) {
            val byChannel = envelopes.groupBy { it.channelId }
            byChannel.forEach { (channelId, batch) ->
                val response = api.distributeKeys(
                    channelId.value,
                    DistributeKeysRequest(
                        sender_device_id = ownDeviceId(),
                        epoch = batch.first().epoch,
                        envelopes = batch.map {
                            EnvelopeDto(
                                it.recipientDeviceId.value,
                                it.epoch,
                                java.util.Base64.getEncoder().encodeToString(it.ciphertext),
                            )
                        },
                    ),
                )
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("distribute failed: ${response.code()}"))
                }
            }
            Result.success(Unit)
        } else {
            envelopes.forEach { transport.sendKeyEnvelope(it).getOrThrow() }
            Result.success(Unit)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Pulls envelopes addressed to us (online mode), acks them, returns for processing. */
    suspend fun fetchPending(channelId: ChannelId): Result<List<KeyEnvelope>> = try {
        val remote = api.fetchKeyEnvelopes(channelId.value, ownDeviceId()).envelopes
        val envelopes = remote.map {
            KeyEnvelope(
                channelId,
                it.epoch,
                DeviceId(it.sender_device_id),
                DeviceId(ownDeviceId()),
                java.util.Base64.getDecoder().decode(it.envelope_ciphertext),
            )
        }
        if (remote.isNotEmpty()) {
            api.ackKeyEnvelopes(
                channelId.value,
                AckEnvelopesRequest(remote.map { it.envelope_id }),
            )
        }
        Result.success(envelopes)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
