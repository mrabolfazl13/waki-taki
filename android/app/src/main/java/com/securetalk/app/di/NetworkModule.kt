package com.securetalk.app.di

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.CommunicationTransport
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.domain.GroupKeyManager
import com.securetalk.core.common.domain.ParticipantPresence
import com.securetalk.core.common.domain.PushToTalkController
import com.securetalk.core.common.domain.SecureSessionManager
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.common.protocol.WsEvent
import com.securetalk.core.network.api.SecureTalkApi
import com.securetalk.core.network.auth.AuthInterceptor
import com.securetalk.core.network.auth.TokenManager
import com.securetalk.core.network.impl.AuthRepository
import com.securetalk.core.network.impl.ChannelManagerImpl
import com.securetalk.core.network.impl.EnvelopeTransportImpl
import com.securetalk.core.network.impl.LocalChannelManager
import com.securetalk.core.network.impl.PresenceManager
import com.securetalk.core.network.monitor.ConnectionMonitorImpl
import com.securetalk.core.network.nsd.NsdDiscovery
import com.securetalk.core.network.transport.LocalTransport
import com.securetalk.core.network.transport.OnlineTransport
import com.securetalk.core.security.engine.BcCryptoEngine
import com.securetalk.core.security.engine.CryptoEngine
import com.securetalk.core.security.group.ChannelMemberDirectory
import com.securetalk.core.security.group.EnvelopeTransport
import com.securetalk.core.security.group.SenderKeyManager
import com.securetalk.core.security.session.DeviceIdentityManager
import com.securetalk.core.security.session.SecureSessionManagerImpl
import com.securetalk.core.security.store.SecretStore
import com.securetalk.core.security.store.TinkAndroidSecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class OnlineTransportQualifier
@Qualifier annotation class LocalTransportQualifier

/** Tracks the active communication mode — updated by [SessionController] from ConnectionMonitor. */
@Singleton
class ModeHolder {
    @Volatile var useLocal: Boolean = false
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): com.securetalk.core.database.SecureTalkDatabase =
        com.securetalk.core.database.SecureTalkDatabase.get(context)

    @Provides
    @Singleton
    fun settingsRepository(@ApplicationContext context: Context): com.securetalk.core.database.data.SettingsRepository =
        com.securetalk.core.database.data.SettingsRepository(context)

    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Provides
    @Singleton
    fun modeHolder(): ModeHolder = ModeHolder()

    // ---- crypto / identity ----------------------------------------------------------------

    @Provides
    @Singleton
    fun cryptoEngine(): CryptoEngine = BcCryptoEngine()

    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = TinkAndroidSecretStore(context)

    @Provides
    @Singleton
    fun identityManager(engine: CryptoEngine, store: SecretStore): DeviceIdentityManager =
        DeviceIdentityManager(engine, store)

    @Provides
    @Singleton
    fun ownDeviceId(identity: DeviceIdentityManager): OwnDeviceId =
        OwnDeviceId(identity)

    class OwnDeviceId(private val identity: DeviceIdentityManager) {
        val value: String
            get() = identity.ensureIdentity().identity.deviceId.value
    }

    // ---- network ---------------------------------------------------------------------------

    @Provides
    @Singleton
    fun tokenManager(): TokenManager = TokenManager()

    @Provides
    @Singleton
    fun baseUrl(settingsRepository: com.securetalk.core.database.data.SettingsRepository): BaseUrl {
        val configured = kotlinx.coroutines.runBlocking {
            settingsRepository.settings.first().apiBaseUrl
        }
        return BaseUrl(normalizeApiOrigin(configured))
    }

    /** Accept both legacy ".../api/v1" values and plain origins; always end in '/'. */
    private fun normalizeApiOrigin(url: String): String {
        val trimmed = url.trim()
        val origin = trimmed.substringBefore("/api/v1").trimEnd('/')
        return if (origin.startsWith("http://") || origin.startsWith("https://")) "$origin/" else "https://wakitaki.sadsirco.ir/"
    }

    class BaseUrl(url: String) {
        @Volatile var value: String = url
    }

    @Provides
    @Singleton
    fun restApi(baseUrl: BaseUrl, tokens: TokenManager, scope: CoroutineScope): SecureTalkApi {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // BaseUrl now holds the server origin; the versioned REST prefix is placed here.
        val restBaseUrl = baseUrl.value.trimEnd('/') + "/api/v1/"
        // Retrofit uses its own client with auth headers; token refresh reuses a bare client
        // to avoid a circular construction between Retrofit and OkHttpClient.
        val baseClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokens))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(restBaseUrl)
            .client(baseClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(SecureTalkApi::class.java)
        // keep baseUrl switchable: recreate is avoided by only supporting runtime change
        // through app restart (documented); transports read BaseUrl at connect time.
        return api
    }

    @Provides
    @Singleton
    fun wsClient(tokens: TokenManager): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(tokens))
        .build()

    @Provides
    @Singleton
    @OnlineTransportQualifier
    fun onlineTransport(
        wsClient: OkHttpClient,
        scope: CoroutineScope,
        tokens: TokenManager,
        api: SecureTalkApi,
        baseUrl: BaseUrl,
        own: OwnDeviceId,
    ): OnlineTransport = OnlineTransport(wsClient, scope, api, tokens, baseUrl.value, own.value)

    @Provides
    @Singleton
    @LocalTransportQualifier
    fun localTransport(
        wsClient: OkHttpClient,
        scope: CoroutineScope,
        tokens: TokenManager,
        own: OwnDeviceId,
    ): LocalTransport = LocalTransport(wsClient, scope, tokens, deviceId = own.value)

    /**
     * Mode-aware transport: merges both underlying transports' event streams and
     * routes calls to whichever mode is active. Mode is switched explicitly via
     * ConnectionMonitor.confirmSwitchToLocal/Online (never silently — §8).
     */
    @Provides
    @Singleton
    fun communicationTransport(
        scope: CoroutineScope,
        @OnlineTransportQualifier online: OnlineTransport,
        @LocalTransportQualifier local: LocalTransport,
        mode: ModeHolder,
    ): CommunicationTransport {
        val merged = MutableSharedFlow<WsEvent>(replay = 0, extraBufferCapacity = 256)
        online.events.onEach { merged.emit(it) }.launchIn(scope)
        local.events.onEach { merged.emit(it) }.launchIn(scope)
        return object : CommunicationTransport {
            override val events: MutableSharedFlow<WsEvent> = merged
            override val state: StateFlow<com.securetalk.core.common.model.TransportState> =
                MutableStateFlow(com.securetalk.core.common.model.TransportState.DISCONNECTED)

            private val active: CommunicationTransport get() = if (mode.useLocal) local else online

            override suspend fun connect(): Result<Unit> = active.connect()
            override suspend fun disconnect() {
                online.disconnect()
                local.disconnect()
            }
            override suspend fun send(command: com.securetalk.core.common.protocol.WsCommand) = active.send(command)
            override suspend fun sendMedia(packet: com.securetalk.core.common.model.MediaPacket) =
                active.sendMedia(packet)
            override suspend fun sendKeyEnvelope(envelope: com.securetalk.core.common.model.KeyEnvelope) =
                active.sendKeyEnvelope(envelope)
            override suspend fun setActiveChannel(channelId: com.securetalk.core.common.model.ChannelId?) {
                online.setActiveChannel(channelId)
                local.setActiveChannel(channelId)
            }
        }
    }

    @Provides
    @Singleton
    fun authRepository(api: SecureTalkApi, tokens: TokenManager, identity: DeviceIdentityManager): AuthRepository =
        AuthRepository(api, tokens, identity)

    @Provides
    @Singleton
    fun secureSessionManager(
        engine: CryptoEngine,
        identity: DeviceIdentityManager,
        auth: AuthRepository,
    ): SecureSessionManager = SecureSessionManagerImpl(engine, identity, auth)

    @Provides
    @Singleton
    fun nsdDiscovery(@ApplicationContext context: Context): NsdDiscovery = NsdDiscovery(context)

    @Provides
    @Singleton
    fun localHostServer(scope: CoroutineScope): com.securetalk.core.network.localhost.LocalHostServer =
        com.securetalk.core.network.localhost.LocalHostServer(scope)

    @Provides
    @Singleton
    fun connectionMonitor(
        @ApplicationContext context: Context,
        @OnlineTransportQualifier online: OnlineTransport,
        @LocalTransportQualifier local: LocalTransport,
        sessions: SecureSessionManager,
        nsd: NsdDiscovery,
        scope: CoroutineScope,
    ): ConnectionMonitor = ConnectionMonitorImpl(context, online, local, sessions, nsd, scope)

    // ---- audio -----------------------------------------------------------------------------

    @Provides
    @Singleton
    fun audioEngine(@ApplicationContext context: Context): com.securetalk.core.common.domain.AudioEngine =
        com.securetalk.core.audio.AndroidAudioEngine(context).also { it.start() }

    // ---- channel layer ----------------------------------------------------------------------

    @Provides
    @Singleton
    fun onlineChannelManager(
        api: SecureTalkApi,
        db: com.securetalk.core.database.SecureTalkDatabase,
        identity: DeviceIdentityManager,
        auth: AuthRepository,
    ): ChannelManagerImpl = ChannelManagerImpl(
        api,
        db.channelDao(),
        db.memberDao(),
        // The backend compares owner_id to the authenticated user id; using ""
        // would mark every channel as not owned. AuthRepository holds the id.
        ownUserId = { auth.session.value.userId },
        isLocalMode = { false },
        auth = auth,
    )

    @Provides
    @Singleton
    fun localChannelManager(
        db: com.securetalk.core.database.SecureTalkDatabase,
        identity: DeviceIdentityManager,
    ): LocalChannelManager = LocalChannelManager(
        db.channelDao(),
        db.memberDao(),
        ownUserId = { identity.ensureIdentity().identity.deviceId.value },
        ownDisplayName = { "local-user" },
        ownDeviceId = { identity.ensureIdentity().identity.deviceId.value },
    )

    @Provides
    @Singleton
    fun channelManager(
        online: ChannelManagerImpl,
        local: LocalChannelManager,
        mode: ModeHolder,
    ): ChannelManager = object : ChannelManager {
        private val active: ChannelManager get() = if (mode.useLocal) local else online
        override val channels = kotlinx.coroutines.flow.merge(online.channels, local.channels)
        override suspend fun refresh() = active.refresh()
        override suspend fun createChannel(name: String) = active.createChannel(name)
        override suspend fun joinChannel(inviteCode: String) = active.joinChannel(inviteCode)
        override suspend fun leaveChannel(id: com.securetalk.core.common.model.ChannelId) = active.leaveChannel(id)
        override suspend fun deleteChannel(id: com.securetalk.core.common.model.ChannelId) = active.deleteChannel(id)
        override suspend fun createInvite(id: com.securetalk.core.common.model.ChannelId) = active.createInvite(id)
        override suspend fun validateInvite(code: String) = active.validateInvite(code)
        override suspend fun members(id: com.securetalk.core.common.model.ChannelId) = active.members(id)
        override suspend fun removeMember(id: com.securetalk.core.common.model.ChannelId, userId: com.securetalk.core.common.model.DeviceId) =
            active.removeMember(id, userId)
    }

    @Provides
    @Singleton
    fun memberDirectory(channelManager: ChannelManager): ChannelMemberDirectory =
        object : ChannelMemberDirectory {
            override suspend fun memberDevices(channelId: com.securetalk.core.common.model.ChannelId): List<com.securetalk.core.common.model.DeviceId> =
                channelManager.members(channelId)
                    .getOrDefault(emptyList())
                    .map { it.deviceId }
        }

    @Provides
    @Singleton
    fun envelopeTransport(
        api: SecureTalkApi,
        transport: CommunicationTransport,
        mode: ModeHolder,
        own: OwnDeviceId,
    ): EnvelopeTransport = EnvelopeTransportImpl(
        api,
        transport,
        isOnlineMode = { !mode.useLocal },
        ownDeviceId = { own.value },
    )

    @Provides
    @Singleton
    fun groupKeyManager(
        engine: CryptoEngine,
        sessions: SecureSessionManager,
        identity: DeviceIdentityManager,
        envelopeTransport: EnvelopeTransport,
        directory: ChannelMemberDirectory,
    ): GroupKeyManager = SenderKeyManager(engine, sessions, identity, envelopeTransport, directory)

    @Provides
    @Singleton
    fun presence(
        transport: CommunicationTransport,
        channelManager: ChannelManager,
        scope: CoroutineScope,
    ): ParticipantPresence = PresenceManager(
        memberSource = { channelId -> channelManager.members(channelId).getOrDefault(emptyList()) },
        transport = transport,
        scope = scope,
    )

    @Provides
    @Singleton
    fun pushToTalkController(
        transport: CommunicationTransport,
        audio: com.securetalk.core.common.domain.AudioEngine,
        keys: GroupKeyManager,
        own: OwnDeviceId,
    ): PushToTalkController = com.securetalk.core.common.domain.DefaultPushToTalkController(
        transport,
        audio,
        keys,
        com.securetalk.core.common.model.DeviceId(own.value),
    )
}
