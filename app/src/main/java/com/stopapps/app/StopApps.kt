package com.stopapps.app

import android.app.Application
import com.stopapps.app.accessibility.ForceStopEngine
import com.stopapps.app.accessibility.ForceStopEngineHolder
import com.stopapps.app.data.FileLogger

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
}
