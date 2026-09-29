package com.stopapps.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that powers the force-stop automation.
 *
 * It does nothing on its own: [ForceStopEngine] enables aggressive event
 * monitoring only for the duration of a stop run ([setAggressiveMonitoring])
 * and every relevant event is forwarded to the engine, which performs the
 * actual Force stop -> OK clicks. Outside a run the service is idle.
 */
class StopAccessService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: StopAccessService? = null
            private set

        /** True if the user has enabled this service in system Settings. */
        fun isEnabled(context: android.content.Context): Boolean {
            return try {
                val expected = "${context.packageName}/${StopAccessService::class.java.name}"
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.split(':').any { it.equals(expected, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Start quiet: only listen while a run is active.
        setAggressiveMonitoring(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            ForceStopEngineHolder.engine?.onAccessibilityEvent(event)
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() = Unit

    /**
     * While a run is active we monitor window-state-changed (32) +
     * view-scrolled (4096), mirroring the reference; the engine additionally
     * toggles window-content-changed (2048) via [updateEventTypes] while the
     * "Force stop" button is on screen. Outside a run, event delivery is off.
     */
    fun setAggressiveMonitoring(enabled: Boolean) {
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            if (enabled) {
                info.eventTypes =
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED // 4128
                info.flags =
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS // 80
                info.notificationTimeout = 100
            } else {
                info.eventTypes = 0
                info.flags = 0
                info.notificationTimeout = 0
            }
            serviceInfo = info
        } catch (_: Exception) {
        }
    }

    /** Applies [transform] to the current event-type mask (engine use only). */
    fun updateEventTypes(transform: (Int) -> Int) {
        try {
            val info = serviceInfo ?: return
            info.eventTypes = transform(info.eventTypes)
            serviceInfo = info
        } catch (_: Exception) {
        }
    }
}

/**
 * Process-wide holder so the engine (created with an application context) and
 * the service can find each other without leaks.
 */
object ForceStopEngineHolder {
    @Volatile
    var engine: ForceStopEngine? = null
}
