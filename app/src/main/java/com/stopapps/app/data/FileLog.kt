package com.stopapps.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Diag-build persistent text log.
 *
 * Every `[dbg]` RunLog line is mirrored to
 * `<getExternalFilesDir("logs")>/runlog-<yyyyMMdd-HHmmss>.txt`, i.e.
 * `/sdcard/Android/data/com.stopapps.app/files/logs/runlog-*.txt` on the
 * device. A new file is started on every app launch.
 *
 * - No permissions required (app-private external files dir).
 * - Writes happen on a dedicated background thread; logging never blocks
 *   the UI or the automation.
 * - Every failure is swallowed: logging must never crash the app.
 */
object FileLog {

    private const val DIR_NAME = "logs"

    private val lock = Any()
    private val tsFmt =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    private val nameFmt =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    @Volatile
    private var file: File? = null

    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "FileLog").apply { isDaemon = true }
        }

    /** Must be called once from the Application class. */
    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(DIR_NAME) ?: return
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "runlog-${nameFmt.format(Date())}.txt")
            val pi =
                try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (_: Exception) {
                    null
                }
            val header =
                buildString {
                    appendLine("=== Stop Apps diag log ===")
                    appendLine("package: ${context.packageName}")
                    appendLine(
                        "version: ${pi?.versionName} " +
                            "(code ${pi?.versionCode})"
                    )
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine(
                        "android: ${Build.VERSION.RELEASE} " +
                            "(SDK ${Build.VERSION.SDK_INT})"
                    )
                    appendLine("started: ${tsFmt.format(Date())}")
                    appendLine("=========================")
                }
            synchronized(lock) { f.appendText(header) }
            file = f
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }

    /** Appends one line with a timestamp prefix. Thread-safe, never throws. */
    fun append(line: String) {
        val f = file ?: return
        val stamped =
            try {
                "[${tsFmt.format(Date())}] $line"
            } catch (_: Exception) {
                line
            }
        try {
            executor.execute {
                try {
                    synchronized(lock) { f.appendText(stamped + "\n") }
                } catch (_: Exception) {
                    // Swallowed: logging must never crash the app.
                }
            }
        } catch (_: Exception) {
            // Executor rejected/shut down: best-effort synchronous write.
            try {
                synchronized(lock) { f.appendText(stamped + "\n") }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Best-effort drain of queued writes. Used by the uncaught-exception
     * handler so the crash trace reaches the file before the process dies.
     */
    fun flushSync(timeoutMs: Long = 3000) {
        try {
            val latch = CountDownLatch(1)
            executor.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Swallowed.
        }
    }

    /** The live log file (may not exist yet). */
    fun currentFile(): File? = file

    /**
     * Copies the current runlog into the public Downloads folder so the user
     * can share it easily. Returns the display name on success, null otherwise.
     */
    fun copyToDownloads(context: Context): String? {
        return try {
            val src = file ?: return null
            if (!src.exists()) return null
            val displayName = "stopapps-runlog-${nameFmt.format(Date())}.txt"
            if (Build.VERSION.SDK_INT >= 29) {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                val uri: Uri =
                    context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                append("runlog copied to Downloads/$displayName")
                displayName
            } else {
                @Suppress("DEPRECATION")
                val dest =
                    File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        displayName
                    )
                synchronized(lock) { src.copyTo(dest, overwrite = true) }
                append("runlog copied to ${dest.absolutePath}")
                displayName
            }
        } catch (e: Exception) {
            append("copyToDownloads failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
