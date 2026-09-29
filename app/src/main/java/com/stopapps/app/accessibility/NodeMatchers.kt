package com.stopapps.app.accessibility

import java.util.Locale

/**
 * Pure, UI-tree-independent matching rules for the automation.
 * Kept separate from [ForceStopEngine] so they can be unit-tested on the JVM.
 */
object NodeMatchers {

    private val FORCE_STOP_ID_SUFFIXES = setOf(
        "force_stop",
        "menu_item_force_stop",
        "forcestopbutton",
        "force_stop_button",
        "btn_force_stop"
    )

    private val CONFIRM_TEXTS = setOf("ok", "yes", "confirm", "确定", "aceptar")

    /** True if the view id belongs to a "Force stop" button. */
    fun isForceStopId(viewId: String?): Boolean {
        if (viewId.isNullOrEmpty()) return false
        val suffix = viewId.substringAfterLast('/').lowercase(Locale.ROOT)
        // Normalise camelCase ids ("forceStopButton") to snake_case.
        val normalised = suffix
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase(Locale.ROOT)
        return suffix in FORCE_STOP_ID_SUFFIXES || normalised in FORCE_STOP_ID_SUFFIXES
    }

    /** True if the node's own text looks like a "Force stop" label. */
    fun isForceStopText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.trim().equals("Force stop", ignoreCase = true)
    }

    /** True if the view id belongs to a dialog "OK" (positive) button. */
    fun isConfirmId(viewId: String?): Boolean {
        if (viewId.isNullOrEmpty()) return false
        val suffix = viewId.substringAfterLast('/').lowercase(Locale.ROOT)
        // android:id/button1 is the AlertDialog positive button.
        return suffix == "button1"
    }

    /** True if the node's text looks like a dialog confirmation label. */
    fun isConfirmText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.trim().lowercase(Locale.ROOT) in CONFIRM_TEXTS
    }
}
