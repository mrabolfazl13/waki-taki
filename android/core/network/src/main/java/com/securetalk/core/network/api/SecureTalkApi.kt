package com.securetalk.core.network.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * REST client for the API contract in ARCHITECTURE §5 (base /api/v1).
 */
interface SecureTalkApi {

    // ---- auth ------------------------------------------------------------------------

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout(): retrofit2.Response<Unit>

    // ---- devices ---------------------------------------------------------------------

    @POST("devices")
    suspend fun registerDevice(@Body body: DeviceRegisterRequest): DeviceResponse

    @GET("devices")
    suspend fun myDevices(): List<DeviceResponse>

    @DELETE("devices/{id}")
    suspend fun deleteDevice(@Path("id") id: String): retrofit2.Response<Unit>

    @GET("users/{id}/keys")
    suspend fun userKeys(@Path("id") userId: String): List<KeyBundleResponse>

    // ---- channels --------------------------------------------------------------------

    @POST("channels")
    suspend fun createChannel(@Body body: CreateChannelRequest): ChannelResponse

    @GET("channels")
    suspend fun listChannels(): List<ChannelResponse>

    @DELETE("channels/{id}")
    suspend fun deleteChannel(@Path("id") id: String): retrofit2.Response<Unit>

    @POST("channels/{id}/join")
    suspend fun joinChannel(@Path("id") id: String, @Body body: JoinRequest): ChannelResponse

    @POST("channels/{id}/leave")
    suspend fun leaveChannel(@Path("id") id: String): retrofit2.Response<Unit>

    @POST("channels/{id}/invites")
    suspend fun createInvite(@Path("id") id: String): InviteResponse

    @POST("invites/validate")
    suspend fun validateInvite(@Body body: ValidateInviteRequest): ValidateInviteResponse

    @POST("channels/{id}/members/{userId}/remove")
    suspend fun removeMember(@Path("id") channelId: String, @Path("userId") userId: String): retrofit2.Response<Unit>

    @GET("channels/{id}/members")
    suspend fun channelMembers(@Path("id") channelId: String): List<MemberResponse>

    // ---- key distribution (§3.3) -------------------------------------------------------

    @POST("channels/{id}/keys/distribute")
    suspend fun distributeKeys(@Path("id") channelId: String, @Body body: DistributeKeysRequest): retrofit2.Response<Unit>

    @GET("channels/{id}/keys/envelopes")
    suspend fun fetchKeyEnvelopes(
        @Path("id") channelId: String,
        @Query("device_id") deviceId: String,
    ): KeyEnvelopesResponse

    @POST("channels/{id}/keys/ack")
    suspend fun ackKeyEnvelopes(@Path("id") channelId: String, @Body body: AckEnvelopesRequest): retrofit2.Response<Unit>

    @POST("channels/{id}/epoch/rotate")
    suspend fun rotateEpoch(@Path("id") channelId: String): EpochResponse

    // ---- health ------------------------------------------------------------------------

    @GET("health")
    suspend fun health(): retrofit2.Response<Unit>
}

// ---- DTOs ------------------------------------------------------------------------------

@Serializable
data class RegisterRequest(val display_name: String, val password: String? = null, val invite_code: String? = null)

@Serializable
data class LoginRequest(val display_name: String, val password: String? = null)

@Serializable
data class RefreshRequest(val refresh_token: String)

@Serializable
data class AuthResponse(
    // Backend TokenPair carries only the two tokens; the remaining fields are
    // optional so deserialization never fails against the real contract.
    val user_id: String = "",
    val device_id: String? = null,
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long = 900L,
    val token_type: String? = null,
)

@Serializable
data class DeviceRegisterRequest(
    val name: String,
    val identity_pubkey: String,
    val signing_pubkey: String,
    val signed_prekey: String,
    val prekey_signature: String,
    val one_time_prekeys: List<PrekeyDto>,
)

@Serializable
data class PrekeyDto(val key_id: Long, val pubkey: String)

@Serializable
data class DeviceResponse(
    val device_id: String,
    val user_id: String,
    val name: String,
    val identity_pubkey: String,
    val signing_pubkey: String,
    val signed_prekey: String,
    val prekey_signature: String,
    val registered_at: Long,
)

@Serializable
data class KeyBundleResponse(
    val device_id: String,
    val identity_pubkey: String,
    val signing_pubkey: String,
    val signed_prekey: String,
    val prekey_signature: String,
    val one_time_prekey: PrekeyDto? = null,
)

@Serializable
data class CreateChannelRequest(val name: String)

@Serializable
data class ChannelResponse(
    val id: String,
    val name: String,
    val owner_id: String,
    val member_count: Int = 1,
    val epoch: Long = 0,
    val created_at: Long = 0,
)

@Serializable
data class JoinRequest(val invite_code: String)

@Serializable
data class InviteResponse(val invite_code: String, val qr_payload: String)

@Serializable
data class ValidateInviteRequest(val invite_code: String)

@Serializable
data class ValidateInviteResponse(val valid: Boolean, val channel_id: String? = null, val channel_name: String? = null)

@Serializable
data class MemberResponse(
    val user_id: String,
    val device_id: String,
    val display_name: String,
    val role: String,
)

@Serializable
data class DistributeKeysRequest(
    val sender_device_id: String,
    val epoch: Long,
    val envelopes: List<EnvelopeDto>,
)

@Serializable
data class EnvelopeDto(
    val recipient_device_id: String,
    val epoch: Long,
    val envelope_ciphertext: String,
)

@Serializable
data class KeyEnvelopeResponse(
    val envelope_id: Int,
    val sender_device_id: String,
    val epoch: Long,
    val envelope_ciphertext: String,
)

@Serializable
data class KeyEnvelopesResponse(val envelopes: List<KeyEnvelopeResponse>)

@Serializable
data class AckEnvelopesRequest(val envelope_ids: List<Int>)

@Serializable
data class EpochResponse(val epoch: Long)
