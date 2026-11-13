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
import com.securetalk.core.common.model.PreKeyBundleData
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

    suspend fun register(displayName: String, password: String?): Result<SessionInfo> = try {
        val response = api.register(RegisterRequest(displayName, password))
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
        _session.value = SessionInfo(response.user_id, deviceId, displayName, authenticated = true)
        return Result.success(_session.value)
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

    override suspend fun bundleFor(deviceId: DeviceId): Result<PreKeyBundleData?> = try {
        val bundles = api.myDevices() // local device bundles; remote lookup by user keys:
        val local = bundles.firstOrNull { it.device_id == deviceId.value }
        if (local != null) {
            Result.success(toBundle(local))
        } else {
            Result.success(null)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun toBundle(local: com.securetalk.core.network.api.DeviceResponse): PreKeyBundleData =
        PreKeyBundleData(
            deviceId = DeviceId(local.device_id),
            identitySigningPub = unhex(local.identity_pubkey),
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
