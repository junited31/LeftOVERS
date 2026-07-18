package com.junited31.leftovers

import android.app.Application
import com.junited31.leftovers.photo.PhotoLifecycle

open class LeftoversApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PhotoLifecycle(this).sweepStale()
    }
}
