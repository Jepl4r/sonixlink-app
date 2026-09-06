package com.mattiadoronzo.sonixlink

import android.app.Application

/** Exists only to install the crash recorder before anything else runs. */
class SonixLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
