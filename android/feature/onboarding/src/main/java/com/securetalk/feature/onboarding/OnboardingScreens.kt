package com.securetalk.feature.onboarding

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.design.SecureTalkTheme
import com.securetalk.core.design.StColors
import com.securetalk.core.database.data.SettingsRepository
import com.securetalk.core.permissions.RequestPermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val onboarded = settings.settings

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboarded(true) }
    }

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    fun nextStep() {
        _step.value = (_step.value + 1).coerceAtMost(2)
    }
}

/**
 * Screen 1/3: Welcome — mode overview, security pitch.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SecureTalk", style = MaterialTheme.typography.headlineMedium, color = StColors.Amber)
        Spacer(Modifier.height(8.dp))
        Text(
            "Encrypted push-to-talk for teams",
            style = MaterialTheme.typography.bodyLarge,
            color = StColors.GreyText,
        )
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("END-TO-END ENCRYPTED VOICE", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sender-key group encryption with pairwise key distribution. " +
                        "Relays and hosts relay opaque packets only — they cannot decrypt your audio.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("TWO MODES", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Internet: connect to a SecureTalk server. Offline: one device acts as a " +
                        "local host on Wi-Fi/hotspot and relays encrypted packets. Switching modes " +
                        "always re-establishes security sessions.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
            Text("GET STARTED")
        }
    }
}

/**
 * Screen 2/3: Permissions — microphone, notifications, foreground service.
 */
@Composable
fun PermissionsScreen(
    onGranted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "SecureTalk needs:\n\n• Microphone — push-to-talk capture (VOICE_COMMUNICATION audio path with echo cancellation and noise suppression)\n\n" +
                "• Notifications — the PTT session and local host run as foreground services so calls survive backgrounding",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))
        RequestPermissions(
            permissions = com.securetalk.core.permissions.StPermissions.requiredList,
            onAllGranted = onGranted,
            onDenied = onGranted, // allow proceeding; PTT will re-request
        )
        Button(onClick = onGranted, modifier = Modifier.fillMaxWidth()) {
            Text("ALLOW AND CONTINUE")
        }
    }
}

/**
 * Screen 3/3: Mode selection — internet vs local.
 */
@Composable
fun ModeSelectScreen(
    onPickInternet: () -> Unit,
    onPickLocal: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Choose mode", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Card(onClick = onPickInternet, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("INTERNET MODE", style = MaterialTheme.typography.labelLarge, color = StColors.Amber)
                Spacer(Modifier.height(6.dp))
                Text("Connect to a SecureTalk server over HTTPS/WSS. Server coordinates floors and relays encrypted media.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(onClick = onPickLocal, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("OFFLINE MODE", style = MaterialTheme.typography.labelLarge, color = StColors.Teal)
                Spacer(Modifier.height(6.dp))
                Text("Same Wi-Fi or hotspot. One device hosts; peers discover it via NSD or QR. No server, no internet.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        viewModel // touch to keep VM alive for onboarding completion
        OutlinedButton(
            onClick = { viewModel.completeOnboarding() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("DECIDE LATER") }
    }
}
