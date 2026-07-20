package com.junited31.leftovers.network

import com.junited31.leftovers.photo.PhotoLifecycle
import com.junited31.leftovers.photo.PhotoLifecycle.ManagedPhoto
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ApiResult {
    data class Success(val body: String) : ApiResult
    data object Unauthorized : ApiResult
    data object PayloadTooLarge : ApiResult
    data object InvalidRequest : ApiResult
    data class QuotaLimited(val retryAfterSeconds: Long?) : ApiResult
    data object UpstreamUnavailable : ApiResult
    data class UnexpectedHttp(val status: Int) : ApiResult
    data object AuthUnavailable : ApiResult
    data object InvalidPhoto : ApiResult
    data object NetworkFailure : ApiResult
    data object Cancelled : ApiResult
    data object AlreadyExecuted : ApiResult
}

class LeftoversApi(
    baseUrl: String,
    private val tokenProvider: TokenProvider,
    client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val baseUrl = baseUrl.toHttpUrl()
    private val client = client.newBuilder().retryOnConnectionFailure(false).build()

    fun executeJson(path: String, json: String): ApiResult {
        if (json.toByteArray().size > MAX_REQUEST_BYTES) return ApiResult.PayloadTooLarge
        val url = baseUrl.resolve(path) ?: return ApiResult.InvalidRequest
        val token = token() ?: return ApiResult.AuthUnavailable
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(client.newCall(request))
    }

    fun newPhotoAdviceCall(contextJson: String, photo: ManagedPhoto): PhotoAdviceCall = PhotoAdviceCall(
        requestFactory = {
            val token = token()
            if (token == null) {
                CallCreation.Failed(ApiResult.AuthUnavailable)
            } else {
                val request = Request.Builder()
                    .url(checkNotNull(baseUrl.resolve("/v1/cooking/advice")))
                    .header("Authorization", "Bearer $token")
                    .post(
                        MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("context", contextJson)
                            .addFormDataPart("photo", "step.jpg", photo.file.asRequestBody(JPEG_MEDIA_TYPE))
                            .build(),
                    )
                    .build()
                CallCreation.Ready(client.newCall(request))
            }
        },
        photo = photo,
        preflightFailure = if (contextJson.toByteArray().size > MAX_REQUEST_BYTES) {
            ApiResult.PayloadTooLarge
        } else {
            null
        },
    )

    private fun token(): String? = try {
        tokenProvider.idToken()
    } catch (_: TokenUnavailableException) {
        null
    }

    private fun execute(call: Call): ApiResult = try {
        call.execute().use(::mapResponse)
    } catch (_: IOException) {
        if (call.isCanceled()) ApiResult.Cancelled else ApiResult.NetworkFailure
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        const val MAX_REQUEST_BYTES = 256 * 1024

        internal fun mapResponse(response: Response): ApiResult = when (response.code) {
            200 -> ApiResult.Success(response.body?.string().orEmpty())
            401 -> ApiResult.Unauthorized
            413 -> ApiResult.PayloadTooLarge
            422 -> ApiResult.InvalidRequest
            429 -> ApiResult.QuotaLimited(response.header("Retry-After")?.toLongOrNull())
            502 -> ApiResult.UpstreamUnavailable
            else -> ApiResult.UnexpectedHttp(response.code)
        }
    }
}

class PhotoAdviceCall internal constructor(
    private val requestFactory: () -> CallCreation,
    private val photo: ManagedPhoto,
    private val preflightFailure: ApiResult?,
) {
    @Volatile
    private var call: Call? = null

    private val cancelled = AtomicBoolean()
    private val started = AtomicBoolean()

    fun execute(): ApiResult {
        if (!started.compareAndSet(false, true)) return ApiResult.AlreadyExecuted
        try {
            preflightFailure?.let { return it }
            if (!photo.file.isFile) return ApiResult.InvalidPhoto
            if (photo.file.length() > PhotoLifecycle.MAX_UPLOAD_BYTES) return ApiResult.PayloadTooLarge
            if (cancelled.get()) return ApiResult.Cancelled
            return when (val created = requestFactory()) {
                is CallCreation.Failed -> created.result
                is CallCreation.Ready -> {
                    call = created.call
                    if (cancelled.get()) created.call.cancel()
                    try {
                        created.call.execute().use(LeftoversApi::mapResponse)
                    } catch (_: IOException) {
                        if (created.call.isCanceled()) ApiResult.Cancelled else ApiResult.NetworkFailure
                    }
                }
            }
        } finally {
            photo.delete()
        }
    }

    fun cancel() {
        cancelled.set(true)
        call?.cancel()
    }
}

internal sealed interface CallCreation {
    data class Ready(val call: Call) : CallCreation
    data class Failed(val result: ApiResult) : CallCreation
}
