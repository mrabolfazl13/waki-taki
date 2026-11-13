package com.securetalk.core.network.auth

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Holds JWT access/refresh tokens with rotation (15 min / 30 d, ARCHITECTURE §5). */
class TokenManager {
    @Volatile var accessToken: String? = null
    @Volatile var refreshToken: String? = null
    @Volatile var expiresAtMs: Long = 0

    val hasSession: Boolean get() = !accessToken.isNullOrBlank()

    fun update(access: String, refresh: String, expiresInSec: Long) {
        accessToken = access
        refreshToken = refresh
        expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000
    }

    fun clear() {
        accessToken = null
        refreshToken = null
        expiresAtMs = 0
    }
}

/** Adds the bearer token to every API request. */
class AuthInterceptor(private val tokens: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.accessToken
        val request: Request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}

/**
 * On 401, refreshes the access token once and retries. Refresh token rotation
 * is honored by storing whatever the refresh call returns.
 */
class TokenAuthenticator(
    private val tokens: TokenManager,
    private val refreshBlock: suspend (String) -> Pair<String, String>?, // (access, refresh)
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.code != 401) return null
        val refresh = tokens.refreshToken ?: return null
        val newTokens = kotlinx.coroutines.runBlocking {
            runCatching { refreshBlock(refresh) }.getOrNull()
        } ?: run {
            tokens.clear()
            return null
        }
        tokens.update(newTokens.first, newTokens.second, 15 * 60)
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${newTokens.first}")
            .build()
    }
}
