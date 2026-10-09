/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.fscm.data

import androidx.appcompat.app.AppCompatDelegate
import com.google.samples.apps.nowinandroid.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.QueryMap
import retrofit2.http.Url
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.Response as OkHttpResponse

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FscmJson

interface FscmRetrofitApi {
    @GET
    suspend fun download(@Url url: String, @HeaderMap headers: Map<String, String>): Response<okhttp3.ResponseBody>

    @GET
    suspend fun get(
        @Url url: String,
        @QueryMap params: Map<String, String> = emptyMap(),
        @HeaderMap headers: Map<String, String> = emptyMap(),
    ): Response<JsonElement>

    @POST
    suspend fun post(
        @Url url: String,
        @Body body: JsonElement = JsonObject(emptyMap()),
    ): Response<JsonElement>

    @POST
    suspend fun postWithHeaders(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: JsonElement = JsonObject(emptyMap()),
    ): Response<JsonElement>

    @PUT
    suspend fun put(
        @Url url: String,
        @Body body: JsonElement = JsonObject(emptyMap()),
        @HeaderMap headers: Map<String, String> = emptyMap(),
    ): Response<JsonElement>

    @DELETE
    suspend fun delete(@Url url: String, @HeaderMap headers: Map<String, String> = emptyMap()): Response<JsonElement>

    @Multipart
    @POST
    suspend fun upload(
        @Url url: String,
        @Part file: MultipartBody.Part,
        @HeaderMap headers: Map<String, String> = emptyMap(),
    ): Response<JsonElement>
}

class FscmApiException(
    message: String,
    val statusCode: Int,
    val payload: JsonElement? = null,
    val serverMessage: String? = null,
) : RuntimeException(message)

class SessionExpiredException : RuntimeException("Session expired")

class SessionRefreshUnavailableException : RuntimeException("Session refresh is temporarily unavailable")

@PublishedApi
internal enum class SessionRefreshResult {
    Refreshed,
    AlreadyCurrent,
    Rejected,
    Unavailable,
}

@Singleton
class FscmApiClient @Inject constructor(
    @PublishedApi internal val api: FscmRetrofitApi,
    @PublishedApi internal val sessionStore: SessionStore,
    @param:FscmJson
    @PublishedApi internal val json: Json,
    private val deviceIdentityManager: DeviceIdentityManager,
    @PublishedApi internal val trustedClock: TrustedClock,
) {
    @PublishedApi internal val refreshMutex = Mutex()

    suspend inline fun <reified T> get(
        path: String,
        params: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): T = send(auth) { api.get(path, params, headers) }

    suspend inline fun <reified T> getWithQuery(
        path: String,
        params: List<Pair<String, String>>,
    ): T = get(fscmRepeatedQueryPath(path, params))

    suspend inline fun <reified T> post(
        path: String,
        headers: Map<String, String>,
        body: JsonElement = JsonObject(emptyMap()),
        auth: Boolean = true,
    ): T = send(auth) { api.postWithHeaders(path, headers, body) }

    suspend inline fun <reified T> post(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
        auth: Boolean = true,
    ): T = send(auth) { api.post(path, body) }

    suspend inline fun <reified T> put(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): T = send(auth) { api.put(path, body, headers) }

    suspend inline fun <reified T> delete(
        path: String,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): T = send(auth) { api.delete(path, headers) }

    suspend inline fun <reified T> upload(
        path: String,
        file: MultipartBody.Part,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): T = send(auth) { api.upload(path, file, headers) }

    suspend fun download(path: String, headers: Map<String, String>): ByteArray {
        var response = api.download(path, headers)
        if (response.code() == 401) {
            response.errorBody()?.close()
            when (refreshSession(response.raw().request.header("Authorization")?.removePrefix("Bearer "))) {
                SessionRefreshResult.Refreshed, SessionRefreshResult.AlreadyCurrent -> response = api.download(path, headers)
                SessionRefreshResult.Rejected -> throw SessionExpiredException()
                SessionRefreshResult.Unavailable -> throw SessionRefreshUnavailableException()
            }
        }
        if (response.code() == 401) {
            response.errorBody()?.close()
            sessionStore.clear()
            throw SessionExpiredException()
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw FscmApiException("Download failed", response.code())
        }
        return response.body()?.use { it.bytes() } ?: throw java.io.IOException("Empty download")
    }

    suspend inline fun <reified T> send(
        auth: Boolean,
        noinline call: suspend () -> Response<JsonElement>,
    ): T {
        if (auth) {
            val session = sessionStore.current()
            if (session.shouldProactivelyRefresh(trustedClock.trustedNowMillis() ?: System.currentTimeMillis())) {
                when (refreshSession(session.token)) {
                    SessionRefreshResult.Rejected -> throw SessionExpiredException()
                    else -> Unit
                }
            }
        }
        val response = try {
            retryAuthenticatedRequest(auth, call, ::refreshSession)
        } catch (error: SessionExpiredException) {
            sessionStore.clear()
            throw error
        }
        return decode(response)
    }

    @PublishedApi
    internal suspend fun refreshSession(expectedAccessToken: String?): SessionRefreshResult = refreshMutex.withLock {
        val session = sessionStore.current()
        if (shouldReuseCurrentAccessToken(expectedAccessToken, session.token)) {
            return SessionRefreshResult.AlreadyCurrent
        }
        val refreshToken = session.refreshToken
        if (refreshToken.isBlank()) {
            sessionStore.clear()
            return SessionRefreshResult.Rejected
        }
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        val headers = deviceIdentityManager.proofHeaders(
            method = "POST",
            path = "auth/refresh",
            body = body.toString(),
            timestampMillis = trustedClock.trustedNowMillis() ?: System.currentTimeMillis(),
        )
        val response = runCatching {
            api.postWithHeaders(
                "auth/refresh",
                headers,
                body,
            )
        }.getOrElse { return SessionRefreshResult.Unavailable }
        if (response.isDefinitiveRefreshRejection()) {
            sessionStore.clear()
            return SessionRefreshResult.Rejected
        }
        if (!response.isSuccessful) return SessionRefreshResult.Unavailable
        val payload = runCatching { decode<AuthPayload>(response) }.getOrNull()
            ?: return SessionRefreshResult.Unavailable
        if (payload.token.isBlank()) return SessionRefreshResult.Unavailable
        sessionStore.applyRefresh(payload)
        SessionRefreshResult.Refreshed
    }

    @PublishedApi
    internal inline fun <reified T> decode(response: Response<JsonElement>): T {
        val payload = response.payloadOrError(json)
        if (!response.isSuccessful) {
            val serverMessage = payload?.messageFromPayload()
            val message = serverMessage ?: response.message().ifBlank { "Request failed" }
            throw FscmApiException(message, response.code(), payload, serverMessage)
        }
        val data = payload.unwrapStandardPayload(response.code())
        if (T::class == Unit::class) return Unit as T
        return try {
            json.decodeFromJsonElement(serializer<T>(), data)
        } catch (error: SerializationException) {
            throw FscmApiException(error.message ?: "Response parsing failed", response.code(), data)
        }
    }
}

@PublishedApi
internal suspend fun retryAuthenticatedRequest(
    auth: Boolean,
    request: suspend () -> Response<JsonElement>,
    refreshSession: suspend (String?) -> SessionRefreshResult,
): Response<JsonElement> {
    val initialResponse = request()
    if (!auth || !initialResponse.isAuthenticationFailure()) {
        return initialResponse
    }
    return when (refreshSession(initialResponse.requestAccessToken())) {
        SessionRefreshResult.Refreshed,
        SessionRefreshResult.AlreadyCurrent,
        -> request().also { response ->
            if (response.isAuthenticationFailure()) throw SessionExpiredException()
        }
        SessionRefreshResult.Rejected -> throw SessionExpiredException()
        SessionRefreshResult.Unavailable -> throw SessionRefreshUnavailableException()
    }
}

@PublishedApi
internal fun SessionState.shouldProactivelyRefresh(
    nowMillis: Long,
    refreshMarginMillis: Long = ACCESS_REFRESH_MARGIN_MS,
): Boolean = token.isNotBlank() && refreshToken.isNotBlank() && accessExpiresAt > 0 &&
    accessExpiresAt <= nowMillis + refreshMarginMillis

@PublishedApi
internal fun shouldReuseCurrentAccessToken(expectedAccessToken: String?, currentAccessToken: String): Boolean =
    !expectedAccessToken.isNullOrBlank() && currentAccessToken.isNotBlank() && expectedAccessToken != currentAccessToken

@PublishedApi
internal fun Response<JsonElement>.requestAccessToken(): String? =
    raw().request.header("Authorization")
        ?.removePrefix("Bearer ")
        ?.takeIf(String::isNotBlank)

@PublishedApi
internal fun Response<JsonElement>.isDefinitiveRefreshRejection(): Boolean =
    code() in DEFINITIVE_REFRESH_STATUS_CODES || body().standardResponseCode() in DEFINITIVE_REFRESH_STATUS_CODES

@PublishedApi
internal fun Response<JsonElement>.isAuthenticationFailure(): Boolean =
    code() == 401 || body().standardResponseCode() == 401

@PublishedApi
internal fun Response<JsonElement>.payloadOrError(json: Json): JsonElement? =
    body() ?: if (!isSuccessful) {
        errorBody()?.string()?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { json.parseToJsonElement(raw) }.getOrNull()
        }
    } else {
        null
    }

@PublishedApi
internal fun JsonElement?.standardResponseCode(): Int? =
    (this as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull

@PublishedApi
internal fun JsonElement?.unwrapStandardPayload(statusCode: Int): JsonElement {
    val element = this ?: JsonNull
    val obj = element as? JsonObject ?: return element
    val code = obj["code"]?.jsonPrimitive?.intOrNull
    if (code == null) return element
    if (code == 0) return obj["data"] ?: JsonNull
    val serverMessage = obj.messageFromPayload()
    throw FscmApiException(serverMessage ?: "Request failed", statusCode, element, serverMessage)
}

@PublishedApi
internal fun JsonElement.messageFromPayload(): String? {
    val obj = this as? JsonObject ?: return null
    return obj["msg"]?.jsonPrimitive?.contentOrNull
        ?: obj["message"]?.jsonPrimitive?.contentOrNull
        ?: obj["error"]?.jsonPrimitive?.contentOrNull
}

class FscmAuthInterceptor @Inject constructor(
    private val sessionStore: SessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val session = runBlocking { sessionStore.session.first() }
        val expectedUser = chain.request().header("X-FSCM-Expected-User-ID")
        val expectedNamespace = chain.request().header("X-FSCM-Expected-Namespace-ID")
        if ((expectedUser != null && expectedUser != session.user?.id?.toString()) ||
            (expectedNamespace != null && expectedNamespace != session.currentNamespace?.id?.toString())
        ) {
            throw java.io.IOException("账号或工作空间已切换，原批次未发送，请返回原账号继续")
        }
        val applicationLocales = AppCompatDelegate.getApplicationLocales()
        val languageTag = effectiveLanguageTag(
            applicationTag = applicationLocales.get(0)?.toLanguageTag(),
            systemTag = java.util.Locale.getDefault().toLanguageTag(),
        )
        val requestBuilder = chain.request().newBuilder()
            .removeHeader("X-FSCM-Expected-User-ID")
            .removeHeader("X-FSCM-Expected-Namespace-ID")
            .header("X-FSCM-Client", "mobile-app")
            .header("Accept-Language", languageTag)
        if (chain.request().header("Content-Type") == null && chain.request().body?.contentType() == null) {
            requestBuilder.header("Content-Type", "application/json")
        }
        if (session.token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${session.token}")
        }
        session.currentNamespace?.id?.takeIf { it > 0 }?.let {
            requestBuilder.header("X-Namespace-ID", it.toString())
        }
        return chain.proceed(requestBuilder.build())
    }
}

internal fun effectiveLanguageTag(applicationTag: String?, systemTag: String): String {
    val tag = applicationTag?.takeIf(String::isNotBlank) ?: systemTag
    return when {
        tag.equals("zh", ignoreCase = true) || tag.startsWith("zh-Hans", ignoreCase = true) -> "zh-Hans"
        tag.startsWith("en", ignoreCase = true) -> "en"
        tag.startsWith("th", ignoreCase = true) -> "th"
        tag.startsWith("my", ignoreCase = true) -> "my"
        else -> "en"
    }
}

private const val ACCESS_REFRESH_MARGIN_MS = 5L * 60L * 1_000L
private val DEFINITIVE_REFRESH_STATUS_CODES = setOf(400, 401, 403)

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object FscmNetworkModule {
    @dagger.Provides
    @FscmJson
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @dagger.Provides
    @Singleton
    fun okHttpClient(authInterceptor: FscmAuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                },
            )
            .build()

    @dagger.Provides
    @Singleton
    fun retrofitApi(
        client: OkHttpClient,
        @FscmJson
        json: Json,
    ): FscmRetrofitApi =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FscmRetrofitApi::class.java)
}
