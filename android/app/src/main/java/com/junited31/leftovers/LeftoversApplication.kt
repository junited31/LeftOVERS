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
        baseUrl = "http://127.0.0.1:8080/",
        tokenProvider = AnonymousFirebaseTokenProvider(),
    )

    open fun consumePhotoPickerFixture(): Uri? = null
}
