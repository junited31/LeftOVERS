package com.junited31.leftovers

import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.junited31.leftovers.network.LeftoversApi

class DebugApplication : LeftoversApplication() {
    @Volatile
    var recipeApiOverride: LeftoversApi? = null

    override fun createRecipeApi(): LeftoversApi = recipeApiOverride ?: super.createRecipeApi()

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
