package com.mattiadoronzo.sonixlink

import android.app.Application

/** Installs the crash recorder before any activity or service starts. */
class SonixLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
