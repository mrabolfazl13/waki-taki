package com.securetalk.feature.authentication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.network.impl.AuthRepository
import com.securetalk.core.network.impl.SessionInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data class Error(val message: String) : AuthUiState
    data object Done : AuthUiState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    val session: StateFlow<SessionInfo> = auth.session

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun register(displayName: String, password: String?, inviteCode: String? = null) {
        if (displayName.isBlank()) {
            _uiState.value = AuthUiState.Error("Display name required")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            auth.register(displayName, password?.takeIf { it.isNotBlank() }, inviteCode)
                .onSuccess { _uiState.value = AuthUiState.Done }
                .onFailure { _uiState.value = AuthUiState.Error(it.message ?: "Registration failed") }
        }
    }

    fun login(displayName: String, password: String?) {
        if (displayName.isBlank()) {
            _uiState.value = AuthUiState.Error("Display name required")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            auth.login(displayName, password?.takeIf { it.isNotBlank() })
                .onSuccess { _uiState.value = AuthUiState.Done }
                .onFailure { _uiState.value = AuthUiState.Error(it.message ?: "Login failed") }
        }
    }
}
