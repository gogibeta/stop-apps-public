package com.stopapps.app

import android.app.Application
import com.stopapps.app.accessibility.ForceStopEngine
import com.stopapps.app.accessibility.ForceStopEngineHolder
import com.stopapps.app.data.FileLog
import com.stopapps.app.data.FileLogger
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Creates the process-wide [ForceStopEngine] early so the accessibility
 * service can forward events to it from the moment it connects.
 */
class StopApps : Application() {
    override fun onCreate() {
        super.onCreate()
        // Persistent diagnostic log first — everything below is recorded.
        try {
            FileLogger.init(this)
        } catch (_: Exception) {
        }
        // Diag runlog-*.txt in the app's external files dir: every [dbg]
        // line is mirrored there so the user can pull it off the phone.
        try {
            FileLog.init(this)
        } catch (_: Exception) {
        }
        installCrashHandler()
        FileLogger.log("app", "Application.onCreate")
        if (ForceStopEngineHolder.engine == null) {
            try {
                ForceStopEngineHolder.engine = ForceStopEngine(applicationContext)
                FileLogger.log("app", "ForceStopEngine created")
            } catch (e: Exception) {
                FileLogger.logException("app", "ForceStopEngine creation", e)
            }
        }
    }

    /**
     * Appends the full stack trace of any uncaught exception to the diag
     * runlog, flushes, then chains to the previous default handler so the
     * normal crash flow (dialog/report) still happens.
     */
    private fun installCrashHandler() {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    FileLog.append(
                        "FATAL EXCEPTION in thread ${thread.name}: " +
                            "${throwable.javaClass.name}: ${throwable.message}\n$sw"
                    )
                    FileLog.flushSync()
                } catch (_: Exception) {
                    // Logging must never interfere with crash handling.
                }
                try {
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable)
                    } else {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
                } catch (_: Exception) {
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            // Installing the handler must never crash the app.
        }
    }
}
