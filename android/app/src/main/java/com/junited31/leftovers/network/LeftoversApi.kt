package com.junited31.leftovers.network

import com.junited31.leftovers.photo.PhotoLifecycle
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

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
}

class LeftoversApi(
    baseUrl: String,
    private val tokenProvider: TokenProvider,
    client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build(),
) {
    private val baseUrl = baseUrl.toHttpUrl()
    private val client = client.newBuilder().retryOnConnectionFailure(false).build()

    fun executeJson(path: String, json: String): ApiResult {
        val url = baseUrl.resolve(path) ?: return ApiResult.InvalidRequest
        val token = token() ?: return ApiResult.AuthUnavailable
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(client.newCall(request))
    }

    fun newPhotoAdviceCall(contextJson: String, photo: File): PhotoAdviceCall = PhotoAdviceCall(
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
                            .addFormDataPart("photo", "step.jpg", photo.asRequestBody(JPEG_MEDIA_TYPE))
                            .build(),
                    )
                    .build()
                CallCreation.Ready(client.newCall(request))
            }
        },
        photo = photo,
    )

    private fun token(): String? = try {
        tokenProvider.idToken()
    } catch (_: Exception) {
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
    private val photo: File,
) {
    @Volatile
    private var call: Call? = null

    @Volatile
    private var cancelled = false

    fun execute(): ApiResult {
        try {
            if (!photo.isFile) return ApiResult.InvalidPhoto
            if (photo.length() > PhotoLifecycle.MAX_UPLOAD_BYTES) return ApiResult.PayloadTooLarge
            if (cancelled) return ApiResult.Cancelled
            return when (val created = requestFactory()) {
                is CallCreation.Failed -> created.result
                is CallCreation.Ready -> {
                    call = created.call
                    if (cancelled) created.call.cancel()
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
        cancelled = true
        call?.cancel()
    }
}

internal sealed interface CallCreation {
    data class Ready(val call: Call) : CallCreation
    data class Failed(val result: ApiResult) : CallCreation
}
