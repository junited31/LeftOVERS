package com.junited31.leftovers

import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import com.junited31.leftovers.network.LeftoversApi

class DebugApplication : LeftoversApplication() {
    @Volatile
    var recipeApiOverride: LeftoversApi? = null

    @Volatile
    var photoPickerFixtureOverride: Uri? = null

    @Volatile
    var completionCameraFixtureResult: Boolean? = null

    @Volatile
    var completionCameraFixtureBytes: ByteArray? = null

    override fun createRecipeApi(): LeftoversApi = recipeApiOverride ?: super.createRecipeApi()

    override fun consumePhotoPickerFixture(): Uri? = photoPickerFixtureOverride.also {
        photoPickerFixtureOverride = null
    }

    override fun captureCompletionPhotoFixture(output: Uri): Boolean? =
        completionCameraFixtureResult?.also { captured ->
            if (captured) {
                contentResolver.openOutputStream(output).use {
                    checkNotNull(it).write(requireNotNull(completionCameraFixtureBytes))
                }
            }
            completionCameraFixtureResult = null
            completionCameraFixtureBytes = null
        }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction("com.junited31.leftovers.DEBUG_SEED")
            addAction("com.junited31.leftovers.DEBUG_RESET")
        }
        ContextCompat.registerReceiver(
            this,
            DebugSeedReceiver(),
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
