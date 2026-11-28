package com.securetalk.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Installs the crash handler as early as possible: ContentProvider.onCreate
 * runs BEFORE Application.onCreate and before Hilt injects anything, so even a
 * DI/Hilt failure inside Application.onCreate is captured.
 * Also kicks off upload of any crash captured during the previous run.
 */
class CrashReporterProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        CrashReporter.install(context!!)
        return true
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
