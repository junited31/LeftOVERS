package com.junited31.leftovers

import android.content.IntentFilter
import androidx.core.content.ContextCompat

class DebugApplication : LeftoversApplication() {
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
