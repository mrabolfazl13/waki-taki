package com.securetalk.core.network.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.domain.ConnectionTransition
import com.securetalk.core.common.domain.SecureSessionManager
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.common.model.LocalHostInfo
import com.securetalk.core.network.nsd.NsdDiscovery
import com.securetalk.core.network.transport.LocalTransport
import com.securetalk.core.network.transport.OnlineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ConnectionMonitor (ARCHITECTURE §8):
 * `ONLINE_CONNECTED -> DEGRADED -> RECONNECTING -> {ONLINE_CONNECTED | LOCAL_AVAILABLE | OFFLINE}`.
 *
 * On internet loss the monitor probes for local hosts via NSD; if found it
 * emits [ConnectionTransition.RequiresUserDecision] — the UI must ask the user
 * before switching. Switching re-establishes security sessions (never silently
 * continues, never reuses expired security state — §3.4).
 */
class ConnectionMonitorImpl(
    context: Context,
    private val onlineTransport: OnlineTransport,
    private val localTransport: LocalTransport,
    private val sessionManager: SecureSessionManager,
    private val nsd: NsdDiscovery,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : ConnectionMonitor {

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(ConnectionState.OFFLINE)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _transitions = MutableSharedFlow<ConnectionTransition>(extraBufferCapacity = 32)
    override val transitions: SharedFlow<ConnectionTransition> = _transitions.asSharedFlow()

    private val _localHosts = MutableStateFlow<List<LocalHostInfo>>(emptyList())
    override val localHosts: StateFlow<List<LocalHostInfo>> = _localHosts.asStateFlow()

    private val _pendingDecision =
        MutableStateFlow<ConnectionTransition.RequiresUserDecision?>(null)
    override val pendingDecision: StateFlow<ConnectionTransition.RequiresUserDecision?> =
        _pendingDecision.asStateFlow()

    override fun dismissPendingDecision() {
        _pendingDecision.value = null
    }

    private val running = AtomicBoolean(false)
    private var mode: Mode = Mode.ONLINE
    private val callbackRegistered = AtomicBoolean(false)

    private enum class Mode { ONLINE, LOCAL }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            whenInternetRestored()
        }

        override fun onLost(network: Network) {
            whenInternetLost()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!hasInternet) whenInternetLost() else whenInternetRestored()
        }
    }

    override suspend fun start() {
        if (!running.compareAndSet(false, true)) return
        registerCallback()
        connectTransport()
    }

    override suspend fun stop() {
        running.set(false)
        if (callbackRegistered.compareAndSet(true, false)) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        }
        (if (mode == Mode.ONLINE) onlineTransport else localTransport).disconnect()
    }

    private fun registerCallback() {
        if (callbackRegistered.compareAndSet(false, true)) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { connectivity.registerNetworkCallback(request, networkCallback) }
        }
    }

    private suspend fun connectTransport() {
        val result = when (mode) {
            Mode.ONLINE -> onlineTransport.connect()
            Mode.LOCAL -> localTransport.connect()
        }
        if (result.isSuccess) {
            setState(ConnectionState.ONLINE_CONNECTED)
        } else {
            setState(ConnectionState.RECONNECTING)
        }
    }

    private fun whenInternetRestored() {
        if (!running.get()) return
        if (mode == Mode.ONLINE && _state.value in setOf(ConnectionState.RECONNECTING, ConnectionState.OFFLINE, ConnectionState.DEGRADED)) {
            scope.launch {
                val ok = onlineTransport.connect().isSuccess
                setState(if (ok) ConnectionState.ONLINE_CONNECTED else ConnectionState.RECONNECTING)
            }
        } else if (mode == Mode.LOCAL && _state.value == ConnectionState.DEGRADED) {
            // local mode unaffected by internet loss; return to stable local state
            setState(ConnectionState.LOCAL_AVAILABLE)
        }
    }

    private fun whenInternetLost() {
        if (!running.get()) return
        when (mode) {
            Mode.ONLINE -> scope.launch {
                setState(ConnectionState.RECONNECTING)
                probeLocalHosts()
                if (_localHosts.value.isNotEmpty()) {
                    // Do NOT enter LOCAL_AVAILABLE here: discovery is not consent.
                    // Stay RECONNECTING until the user confirms the switch (§8).
                    val decision = ConnectionTransition.RequiresUserDecision(
                        reason = "Internet connection lost. Local SecureTalk hosts found nearby — switch to offline mode? (security sessions will be re-established)",
                        localHosts = _localHosts.value,
                    )
                    _pendingDecision.value = decision
                    _transitions.tryEmit(decision)
                } else {
                    setState(ConnectionState.OFFLINE)
                }
            }
            Mode.LOCAL -> setState(ConnectionState.DEGRADED)
        }
    }

    override suspend fun probeLocalHosts() {
        _localHosts.value = nsd.discover(5_000)
    }

    override suspend fun confirmSwitchToLocal(host: LocalHostInfo): Result<Unit> {
        return try {
            onlineTransport.disconnect()
            localTransport.updateEndpoint(host.address, host.port)
            sessionManager.resetAll() // §3.4/§8: transport switch re-runs session establishment
            mode = Mode.LOCAL
            val result = localTransport.connect()
            setState(if (result.isSuccess) ConnectionState.LOCAL_AVAILABLE else ConnectionState.OFFLINE)
            _transitions.tryEmit(ConnectionTransition.Changed(ConnectionState.ONLINE_CONNECTED, ConnectionState.LOCAL_AVAILABLE))
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun confirmSwitchToOnline(): Result<Unit> {
        return try {
            localTransport.disconnect()
            sessionManager.resetAll()
            mode = Mode.ONLINE
            val result = onlineTransport.connect()
            setState(if (result.isSuccess) ConnectionState.ONLINE_CONNECTED else ConnectionState.RECONNECTING)
            _transitions.tryEmit(ConnectionTransition.Changed(ConnectionState.LOCAL_AVAILABLE, ConnectionState.ONLINE_CONNECTED))
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun setState(next: ConnectionState) {
        val previous = _state.value
        if (previous != next) {
            _state.value = next
            _transitions.tryEmit(ConnectionTransition.Changed(previous, next))
        }
    }
}
