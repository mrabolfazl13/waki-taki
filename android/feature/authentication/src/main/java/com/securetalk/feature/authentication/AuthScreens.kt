package com.securetalk.feature.authentication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StTextField
import com.securetalk.core.design.StTopBar
import com.securetalk.core.design.StateBlock
import com.securetalk.core.design.stTokens

@Composable
fun LoginScreen(
    onSuccess: () -> Unit,
    onGoRegister: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    AuthForm(title = "Log in", submitLabel = "LOG IN", needsPassword = true, onGoOther = onGoRegister, onSuccess = onSuccess, viewModel = viewModel, submit = { n, p, _ -> viewModel.login(n, p) })
}

@Composable
fun RegisterScreen(
    onSuccess: () -> Unit,
    onGoLogin: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    AuthForm(title = "Create account", submitLabel = "REGISTER", needsPassword = true, onGoOther = onGoLogin, onSuccess = onSuccess, viewModel = viewModel, submit = viewModel::register)
}

@Composable
private fun AuthForm(
    title: String,
    submitLabel: String,
    needsPassword: Boolean,
    onGoOther: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: AuthViewModel,
    submit: (String, String?, String?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    val t = stTokens()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        StTopBar(title = title)
        Spacer(Modifier.height(8.dp))
        StCard(Modifier.padding(horizontal = 16.dp)) {
            StTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Display name",
            )
            if (needsPassword) {
                Spacer(Modifier.height(12.dp))
                StTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password (optional)",
                )
                Spacer(Modifier.height(12.dp))
                StTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it.uppercase() },
                    label = "Invite code (from an admin)",
                    placeholder = "WT-XXXX-XXXX",
                )
            }
            when (val state = uiState) {
                is AuthUiState.Error -> {
                    Spacer(Modifier.height(10.dp))
                    Text(state.message, color = t.txRed, style = MaterialTheme.typography.bodyMedium)
                }
                AuthUiState.Loading -> {
                    Spacer(Modifier.height(10.dp))
                    StateBlock(title = "Contacting server…", loading = true)
                }
                else -> Unit
            }
            Spacer(Modifier.height(16.dp))
            StButton(
                submitLabel,
                {
                    submit(displayName, password.takeIf { it.isNotBlank() }, inviteCode.takeIf { it.isNotBlank() })
                },
                enabled = uiState !is AuthUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            StButton(
                if (title == "Log in") "CREATE ACCOUNT INSTEAD" else "LOG IN INSTEAD",
                onGoOther,
                modifier = Modifier.fillMaxWidth(),
                style = StButtonStyle.Ghost,
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Done) onSuccess()
    }
}

/**
 * Device verification screen: shows this device's identity fingerprint so the
 * user can compare it out-of-band with other members.
 */
@Composable
fun DeviceVerificationScreen(
    onDone: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val t = stTokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        StTopBar(title = "Device verified")
        Spacer(Modifier.height(8.dp))
        StCard(Modifier.padding(horizontal = 16.dp), accent = t.emerald) {
            Text("SIGNED IN AS", style = MaterialTheme.typography.labelLarge, color = t.greyText)
            Text(session.displayName.ifBlank { "unknown" }, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("DEVICE ID", style = MaterialTheme.typography.labelLarge, color = t.greyText)
            Text(session.deviceId.ifBlank { "pending registration" }, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(20.dp))
        StButton("CONTINUE", onDone, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
    }
}
