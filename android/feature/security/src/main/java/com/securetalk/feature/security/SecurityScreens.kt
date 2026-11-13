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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.securetalk.core.design.StColors
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Security", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        val o = overview
        if (o == null) {
            androidx.compose.material3.CircularProgressIndicator(color = StColors.Amber)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("DEVICE ID", style = MaterialTheme.typography.labelLarge)
                    Text(o.deviceId, style = MaterialTheme.typography.bodyMedium)
                    Text("X25519 IDENTITY FINGERPRINT", style = MaterialTheme.typography.labelLarge)
                    Text(o.identityFingerprint, style = MaterialTheme.typography.bodyMedium, color = StColors.Teal)
                    Text("ED25519 SIGNING FINGERPRINT", style = MaterialTheme.typography.labelLarge)
                    Text(o.signingFingerprint, style = MaterialTheme.typography.bodyMedium, color = StColors.Teal)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("KEY STORAGE", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Private keys are sealed under the Android Keystore master key via Tink; " +
                            "group media uses Signal sender keys with AES-256-GCM per packet " +
                            "(AAD = epoch|sender|seq), sliding replay window 128.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            action?.let {
                Text(it, color = StColors.Green)
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = viewModel::resetSessions, modifier = Modifier.fillMaxWidth()) {
                Text("RESET PAIRWISE SESSIONS")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onVerifyKeys, modifier = Modifier.fillMaxWidth()) {
                Text("VERIFY CONTACT KEYS")
            }
        }
    }
}

@Composable
fun KeyVerificationScreen(
    remoteFingerprint: String,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Verify keys", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Compare this device's fingerprint with the other member's, out-of-band " +
                "(in person or via a trusted channel). Matching fingerprints mean no MITM.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("THIS DEVICE", style = MaterialTheme.typography.labelLarge)
                Text(
                    overview?.identityFingerprint ?: "...",
                    style = MaterialTheme.typography.titleMedium,
                    color = StColors.Teal,
                )
                Spacer(Modifier.height(12.dp))
                Text("REMOTE", style = MaterialTheme.typography.labelLarge)
                Text(
                    remoteFingerprint.ifBlank { "select a member to verify" },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
