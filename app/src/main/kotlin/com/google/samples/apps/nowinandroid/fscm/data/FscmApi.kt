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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
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
    suspend fun get(
        @Url url: String,
        @QueryMap params: Map<String, String> = emptyMap(),
    ): Response<JsonElement>

    @POST
    suspend fun post(
        @Url url: String,
        @Body body: JsonElement = JsonObject(emptyMap()),
    ): Response<JsonElement>

    @PUT
    suspend fun put(
        @Url url: String,
        @Body body: JsonElement = JsonObject(emptyMap()),
    ): Response<JsonElement>

    @DELETE
    suspend fun delete(@Url url: String): Response<JsonElement>
}

class FscmApiException(
    message: String,
    val statusCode: Int,
    val payload: JsonElement? = null,
) : RuntimeException(message)

@Singleton
class FscmApiClient @Inject constructor(
    @PublishedApi internal val api: FscmRetrofitApi,
    @PublishedApi internal val sessionStore: SessionStore,
    @param:FscmJson
    @PublishedApi internal val json: Json,
) {
    @PublishedApi internal val refreshMutex = Mutex()

    suspend inline fun <reified T> get(
        path: String,
        params: Map<String, String> = emptyMap(),
        auth: Boolean = true,
    ): T = send(auth) { api.get(path, params) }

    suspend inline fun <reified T> post(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
        auth: Boolean = true,
    ): T = send(auth) { api.post(path, body) }

    suspend inline fun <reified T> put(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
        auth: Boolean = true,
    ): T = send(auth) { api.put(path, body) }

    suspend inline fun <reified T> delete(
        path: String,
        auth: Boolean = true,
    ): T = send(auth) { api.delete(path) }

    suspend inline fun <reified T> send(
        auth: Boolean,
        crossinline call: suspend () -> Response<JsonElement>,
    ): T {
        var response = call()
        if (auth && response.code() == 401 && refreshSession()) {
            response = call()
        }
        return decode(response)
    }

    @PublishedApi
    internal suspend fun refreshSession(): Boolean = refreshMutex.withLock {
        val refreshToken = sessionStore.current().refreshToken
        if (refreshToken.isBlank()) return false
        val response = api.post(
            "auth/refresh",
            buildJsonObject { put("refresh_token", refreshToken) },
        )
        val payload = runCatching { decode<AuthPayload>(response) }.getOrNull() ?: return false
        if (payload.token.isBlank() || payload.refreshToken.isBlank()) return false
        sessionStore.replaceTokens(payload.token, payload.refreshToken)
        true
    }

    @PublishedApi
    internal inline fun <reified T> decode(response: Response<JsonElement>): T {
        val payload = response.body()
        if (!response.isSuccessful) {
            val message = payload?.messageFromPayload() ?: response.message().ifBlank { "请求失败" }
            throw FscmApiException(message, response.code(), payload)
        }
        val data = payload.unwrapStandardPayload(response.code())
        if (T::class == Unit::class) return Unit as T
        return try {
            json.decodeFromJsonElement(serializer<T>(), data)
        } catch (error: SerializationException) {
            throw FscmApiException(error.message ?: "响应解析失败", response.code(), data)
        }
    }
}

@PublishedApi
internal fun JsonElement?.unwrapStandardPayload(statusCode: Int): JsonElement {
    val element = this ?: JsonNull
    val obj = element as? JsonObject ?: return element
    val code = obj["code"]?.jsonPrimitive?.intOrNull
    if (code == null) return element
    if (code == 0) return obj["data"] ?: JsonNull
    throw FscmApiException(obj.messageFromPayload(), statusCode, element)
}

@PublishedApi
internal fun JsonElement.messageFromPayload(): String {
    val obj = this as? JsonObject ?: return "请求失败"
    return obj["msg"]?.jsonPrimitive?.contentOrNull
        ?: obj["message"]?.jsonPrimitive?.contentOrNull
        ?: obj["error"]?.jsonPrimitive?.contentOrNull
        ?: obj["code"]?.jsonPrimitive?.doubleOrNull?.let { "请求失败：$it" }
        ?: "请求失败"
}

class FscmAuthInterceptor @Inject constructor(
    private val sessionStore: SessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        val session = runBlocking { sessionStore.session.first() }
        val requestBuilder = chain.request().newBuilder()
            .header("X-FSCM-Client", "mobile-app")
            .header("Content-Type", "application/json")
            .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
        if (session.token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${session.token}")
        }
        session.currentNamespace?.id?.takeIf { it > 0 }?.let {
            requestBuilder.header("X-Namespace-ID", it.toString())
        }
        return chain.proceed(requestBuilder.build())
    }
}

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
