package com.securetalk.feature.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.SecureSessionManager
import com.securetalk.core.common.domain.GroupKeyManager
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StColors
import com.securetalk.core.design.StChip
import com.securetalk.core.design.StTopBar
import com.securetalk.core.design.stTokens
import com.securetalk.core.security.engine.CryptoEngine
import com.securetalk.core.security.session.DeviceIdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecurityOverview(
    val deviceId: String,
    val identityFingerprint: String,
    val signingFingerprint: String,
    val keystoreSealed: Boolean,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val identityManager: DeviceIdentityManager,
    private val sessionManager: SecureSessionManager,
    private val groupKeyManager: GroupKeyManager,
    private val cryptoEngine: CryptoEngine,
) : ViewModel() {

    private val _overview = MutableStateFlow<SecurityOverview?>(null)
    val overview: StateFlow<SecurityOverview?> = _overview.asStateFlow()

    private val _action = MutableStateFlow<String?>(null)
    val action: StateFlow<String?> = _action.asStateFlow()

    init {
        viewModelScope.launch {
            val bundle = identityManager.ensureIdentity()
            val idFp = fingerprint(bundle.identity.x25519Public)
            val signFp = fingerprint(bundle.identity.ed25519Public)
            _overview.value = SecurityOverview(
                deviceId = bundle.identity.deviceId.value,
                identityFingerprint = idFp,
                signingFingerprint = signFp,
                keystoreSealed = true, // SecretStore wraps secrets in the Keystore-backed AEAD
            )
        }
    }

    /** Safety-number style verification string for out-of-band comparison. */
    fun fingerprint(bytes: ByteArray): String =
        cryptoEngine.hkdfSha256(bytes, ByteArray(32), "fingerprint".toByteArray(), 30)
            .joinToString("") { "%02x".format(it) }
            .chunked(5)
            .joinToString(" ")

    fun resetSessions() {
        viewModelScope.launch {
            sessionManager.resetAll()
            _action.value = "All pairwise sessions dropped. They will re-establish (X3DH) on next use."
        }
    }

    fun clearAction() {
        _action.value = null
    }
}

@Composable
fun SecurityOverviewScreen(
    onVerifyKeys: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsState()
    val action by viewModel.action.collectAsState()

    val t = stTokens()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StTopBar(
            title = "Security",
            trailing = { StChip("E2E AES-256-GCM", accent = t.emerald) },
        )
        Spacer(Modifier.height(4.dp))
        val o = overview
        if (o == null) {
            StCard(Modifier.padding(horizontal = 16.dp)) {
                Text("Loading security overview…", style = MaterialTheme.typography.bodyMedium, color = t.greyText)
            }
        } else {
            StCard(Modifier.padding(horizontal = 16.dp), accent = t.emerald) {
                Text("DEVICE ID", style = MaterialTheme.typography.labelLarge, color = t.greyText)
                Text(o.deviceId, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("X25519 IDENTITY FINGERPRINT", style = MaterialTheme.typography.labelLarge, color = t.greyText)
                Text(o.identityFingerprint, style = MaterialTheme.typography.labelMedium, color = t.teal)
                Spacer(Modifier.height(8.dp))
                Text("ED25519 SIGNING FINGERPRINT", style = MaterialTheme.typography.labelLarge, color = t.greyText)
                Text(o.signingFingerprint, style = MaterialTheme.typography.labelMedium, color = t.teal)
            }
            Spacer(Modifier.height(10.dp))
            StCard(Modifier.padding(horizontal = 16.dp), accent = t.teal) {
                Text("KEY STORAGE", style = MaterialTheme.typography.labelLarge, color = t.greyText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Private keys are sealed under the Android Keystore master key via Tink; " +
                        "group media uses Signal sender keys with AES-256-GCM per packet " +
                        "(AAD = epoch|sender|seq), sliding replay window 128.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(14.dp))
            action?.let {
                Text(it, color = t.emerald, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
            }
            StButton("RESET PAIRWISE SESSIONS", viewModel::resetSessions, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), style = StButtonStyle.Ghost)
            Spacer(Modifier.height(8.dp))
            StButton("VERIFY CONTACT KEYS", onVerifyKeys, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), style = StButtonStyle.Ghost)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun KeyVerificationScreen(
    remoteFingerprint: String,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsState()
    val t = stTokens()
    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        StTopBar(title = "Verify keys")
        Spacer(Modifier.height(8.dp))
        StCard(Modifier.padding(horizontal = 16.dp), accent = t.amber) {
            Text(
                "Compare this device's fingerprint with the other member's, out-of-band " +
                    "(in person or via a trusted channel). Matching fingerprints mean no MITM.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        StCard(Modifier.padding(horizontal = 16.dp), accent = t.emerald) {
            Text("THIS DEVICE", style = MaterialTheme.typography.labelLarge, color = t.greyText)
            Text(
                overview?.identityFingerprint ?: "...",
                style = MaterialTheme.typography.titleMedium,
                color = t.teal,
            )
            Spacer(Modifier.height(12.dp))
            Text("REMOTE", style = MaterialTheme.typography.labelLarge, color = t.greyText)
            Text(
                remoteFingerprint.ifBlank { "select a member to verify" },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
