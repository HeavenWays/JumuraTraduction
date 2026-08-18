package com.jumura.translate

import android.app.Application
import com.jumura.translate.core.Engine

class JumuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
