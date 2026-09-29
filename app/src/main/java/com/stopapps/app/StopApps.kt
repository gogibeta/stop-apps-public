package com.stopapps.app

import android.app.Application
import com.stopapps.app.accessibility.ForceStopEngine
import com.stopapps.app.accessibility.ForceStopEngineHolder

/**
 * Creates the process-wide [ForceStopEngine] early so the accessibility
 * service can forward events to it from the moment it connects.
 */
class StopApps : Application() {
    override fun onCreate() {
        super.onCreate()
        if (ForceStopEngineHolder.engine == null) {
            ForceStopEngineHolder.engine = ForceStopEngine(applicationContext)
        }
    }
}
