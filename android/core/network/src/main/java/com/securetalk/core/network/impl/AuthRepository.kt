package com.securetalk.core.network.impl

import com.securetalk.core.network.api.DeviceRegisterRequest
import com.securetalk.core.network.api.LoginRequest
import com.securetalk.core.network.api.PrekeyDto
import com.securetalk.core.network.api.RegisterRequest
import com.securetalk.core.network.api.SecureTalkApi
import com.securetalk.core.network.auth.TokenManager
import com.securetalk.core.security.session.DeviceIdentityManager
import com.securetalk.core.security.session.PreKeyBundleDirectory
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.Member
import com.securetalk.core.common.model.PreKeyBundleData
import com.securetalk.core.common.model.UserId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionInfo(
    val userId: String,
    val deviceId: String,
    val displayName: String,
    val authenticated: Boolean,
)

/**
 * Authentication + device registration (ARCHITECTURE §5):
 * register/login -> tokens; then the device identity prekey bundle is
 * published (§3.1) so peers can establish pairwise sessions.
 */
class AuthRepository(
    private val api: SecureTalkApi,
    private val tokens: TokenManager,
    private val identityManager: DeviceIdentityManager,
) : PreKeyBundleDirectory {

    private val _session = MutableStateFlow(SessionInfo("", "", "", authenticated = false))
    val session: StateFlow<SessionInfo> = _session.asStateFlow()

    /** device id -> owning user id, learned from channel rosters (§5 key discovery). */
    private val deviceUsers = ConcurrentHashMap<String, String>()

    /** Called by ChannelManagerImpl whenever a member roster is fetched/refreshed. */
    fun rememberMembers(members: List<Member>) {
        members.forEach { deviceUsers[it.deviceId.value] = it.userId.value }
    }

    fun rememberSelf(userId: String, deviceId: String) {
        deviceUsers[deviceId] = userId
    }

    suspend fun register(displayName: String, password: String?, inviteCode: String? = null): Result<SessionInfo> = try {
        val response = api.register(RegisterRequest(displayName, password, inviteCode?.trim()?.takeIf { it.isNotEmpty() }))
        applyAuth(displayName, response)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun login(displayName: String, password: String?): Result<SessionInfo> = try {
        val response = api.login(LoginRequest(displayName, password))
        applyAuth(displayName, response)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun logout(): Result<Unit> = try {
        api.logout()
        tokens.clear()
        _session.value = SessionInfo("", "", "", authenticated = false)
        Result.success(Unit)
    } catch (e: Exception) {
        tokens.clear()
        Result.failure(e)
    }

    private suspend fun applyAuth(displayName: String, response: com.securetalk.core.network.api.AuthResponse): Result<SessionInfo> {
        tokens.update(response.access_token, response.refresh_token, response.expires_in)
        val deviceId = response.device_id ?: registerDevice()
        // TokenPair does not echo user_id; read `sub` from the server-issued JWT.
        val userId = response.user_id.ifBlank { subjectFromJwt(response.access_token) }
        _session.value = SessionInfo(userId, deviceId, displayName, authenticated = true)
        rememberSelf(userId, deviceId)
        return Result.success(_session.value)
    }

    private fun subjectFromJwt(token: String): String = try {
        val payload = token.split('.')[1]
        val decoded = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        Regex("\"sub\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1) ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Publishes this device's identity + prekeys (§3.1) — required before key distribution. */
    suspend fun registerDevice(): String {
        val bundle = identityManager.ensureIdentity()
        val otps = bundle.oneTimePreKeys.map { PrekeyDto(it.id, hex(it.public)) }
        val response = api.registerDevice(
            DeviceRegisterRequest(
                name = "Android device",
                identity_pubkey = hex(bundle.identity.x25519Public),
                signing_pubkey = hex(bundle.identity.ed25519Public),
                signed_prekey = hex(bundle.signedPreKey.public),
                prekey_signature = hex(bundle.signedPreKey.signature),
                one_time_prekeys = otps,
            ),
        )
        return response.device_id
    }

    override suspend fun bundleFor(deviceId: DeviceId): Result<PreKeyBundleData?> {
        return try {
            // Peer bundles live at GET /users/{user_id}/keys (ARCHITECTURE §5).
            // The device -> user mapping comes from the channel roster.
            val userId = deviceUsers[deviceId.value]
            val peer = if (userId != null) {
                api.userKeys(userId).firstOrNull { it.device_id == deviceId.value }
            } else {
                null
            }
            if (peer != null) {
                Result.success(
                    PreKeyBundleData(
                        deviceId = DeviceId(peer.device_id),
                        identitySigningPub = unhex(peer.identity_pubkey), // X25519 identity point
                        identityEd25519Pub = unhex(peer.signing_pubkey),  // Ed25519 verify key
                        signedPrekeyPub = unhex(peer.signed_prekey),
                        prekeySignature = unhex(peer.prekey_signature),
                        oneTimePrekeyPub = peer.one_time_prekey?.let { unhex(it.pubkey) },
                        oneTimePrekeyId = peer.one_time_prekey?.key_id,
                    ),
                )
            } else {
                // Fallback for our own devices: GET /devices (public metadata
                // only; one-time prekeys are not returned there).
                val local = api.myDevices().firstOrNull { it.device_id == deviceId.value }
                Result.success(local?.let(::toBundle))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun toBundle(local: com.securetalk.core.network.api.DeviceResponse): PreKeyBundleData =
        PreKeyBundleData(
            deviceId = DeviceId(local.device_id),
            identitySigningPub = unhex(local.identity_pubkey),
            identityEd25519Pub = unhex(local.signing_pubkey),
            signedPrekeyPub = unhex(local.signed_prekey),
            prekeySignature = unhex(local.prekey_signature),
        )

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
        return out
    }
}
