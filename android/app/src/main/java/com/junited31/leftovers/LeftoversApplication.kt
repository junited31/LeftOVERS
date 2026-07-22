package com.junited31.leftovers

import android.app.Application
import android.net.Uri
import com.junited31.leftovers.network.AnonymousFirebaseTokenProvider
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.photo.PhotoLifecycle

open class LeftoversApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PhotoLifecycle(this).sweepStale()
    }

    open fun createRecipeApi(): LeftoversApi = LeftoversApi(
        baseUrl = BuildConfig.LEFTOVERS_API_BASE_URL.ifBlank {
            error("LEFTOVERS_API_BASE_URL must be injected by deploy_backend.ps1")
        },
        tokenProvider = AnonymousFirebaseTokenProvider(),
    )

    open fun consumePhotoPickerFixture(): Uri? = null

    open fun captureCompletionPhotoFixture(output: Uri): Boolean? = null
}
