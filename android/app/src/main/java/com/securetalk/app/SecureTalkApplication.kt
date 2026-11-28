package com.securetalk.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SecureTalkApplication : Application() {
    override fun onCreate() {
        try {
            super.onCreate()
        } catch (t: Throwable) {
            // Hilt/DI failures: record them, then rethrow so the crash is honest.
            try {
                val text = "time=" + System.currentTimeMillis() + "\napp_init_failed\n" + t.stackTraceToString()
                java.io.File(filesDir, "last_crash.txt").writeText(text)
            } catch (_: Throwable) {
            }
            throw t
        }
    }
}
