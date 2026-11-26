package com.securetalk.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal, dependency-free crash capture:
 *  - an uncaught exception handler writes the full stack trace to a file
 *  - the next app start uploads it (best effort, 10 s timeout) to the
 *    backend `/api/v1/crash-reports` endpoint and deletes the file on success
 * No third-party crash SDK; the report lands in the admin dashboard's
 * security events so field crashes can be fixed without adb.
 */
object CrashReporter {

    private const val ENDPOINT = "https://wakitaki.sadsirco.ir/api/v1/crash-reports"
    private const val FILE = "last_crash.txt"
    private const val VERSION = "0.1.0"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(context, thread, throwable)
            } catch (_: Throwable) {
                // never let the reporter itself break crash handling
            }
            previous?.uncaughtException(thread, throwable)
        }
        // Upload any crash captured during the previous run.
        val pending = File(context.filesDir, FILE)
        if (pending.isFile) {
            val text = try { pending.readText() } catch (_: Throwable) { null }
            if (!text.isNullOrBlank()) {
                Thread {
                    if (post(text)) {
                        try { pending.delete() } catch (_: Throwable) {}
                    }
                }.start()
            }
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val text = buildString {
            appendLine("time=${System.currentTimeMillis()}")
            appendLine("thread=${thread.name}")
            appendLine("version=$VERSION")
            appendLine(sw.toString())
        }
        File(context.filesDir, FILE).writeText(text)
    }

    /** JSON with the raw stack embedded; escaping via manual replacement is safe here. */
    fun json(stack: String): String {
        val esc = stack
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")
        return """{"platform":"android","version":"$VERSION","stack":"$esc"}"""
    }

    private fun post(stack: String): Boolean {
        return try {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json(stack).toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Throwable) {
            false
        }
    }
}
