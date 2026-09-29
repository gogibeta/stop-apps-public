package com.stopapps.app.accessibility

/**
 * Pure, platform-independent automation policy for the force-stop run.
 *
 * The timing and sequencing values below mirror the publicly observable
 * behavior of the reference app (AppSleep 2.4, reverse-engineered for
 * interoperability):
 *
 * - Per-package watchdog: 8 seconds.
 * - Inter-package delay: 1000 ms normally, 0 ms in turbo mode.
 * - Delay before clicking the confirmation ("OK") button: 100 ms normally,
 *   50 ms in turbo mode.
 * - Delay before clicking "Force stop": 100 ms.
 * - `com.android.chrome` is moved to the end of the stop queue.
 *
 * Event-type handling mirrors the reference as well: while idle the service
 * listens only to window-state-changed (32) + view-scrolled (4096); when the
 * "Force stop" button is found, window-content-changed (2048) is enabled so
 * the confirmation dialog is observed, and it is disabled again after the
 * confirmation button is clicked.
 */
object AutomationPolicy {

    /** Per-package watchdog in milliseconds. */
    const val PACKAGE_TIMEOUT_MS = 8000L

    /** Inter-package delay, normal mode. */
    const val NORMAL_INTER_DELAY_MS = 1000L

    /** Inter-package delay, turbo mode. */
    const val TURBO_INTER_DELAY_MS = 0L

    /** Delay before clicking the confirmation button, normal mode. */
    const val NORMAL_PRE_CLICK_DELAY_MS = 100L

    /** Delay before clicking the confirmation button, turbo mode. */
    const val TURBO_PRE_CLICK_DELAY_MS = 50L

    /** Fixed delay before clicking "Force stop". */
    const val PRE_FORCE_STOP_DELAY_MS = 100L

    const val EVENT_WINDOW_STATE_CHANGED = 32
    const val EVENT_WINDOW_CONTENT_CHANGED = 2048
    const val EVENT_VIEW_SCROLLED = 4096

    /** Event types the service listens to while a run is active (32 | 4096). */
    val RUN_EVENT_TYPES = EVENT_WINDOW_STATE_CHANGED or EVENT_VIEW_SCROLLED // 4128

    /** Package that is always stopped last (mirrors the reference). */
    const val DEFERRED_PACKAGE = "com.android.chrome"

    /** Inter-package delay for the current mode. */
    fun interDelayMs(turbo: Boolean): Long =
        if (turbo) TURBO_INTER_DELAY_MS else NORMAL_INTER_DELAY_MS

    /** Pre-confirmation-click delay for the current mode. */
    fun preClickDelayMs(turbo: Boolean): Long =
        if (turbo) TURBO_PRE_CLICK_DELAY_MS else NORMAL_PRE_CLICK_DELAY_MS

    /** Enable window-content-changed events on the given event-type mask. */
    fun withContentChanged(eventTypes: Int): Int =
        eventTypes or EVENT_WINDOW_CONTENT_CHANGED

    /** Disable window-content-changed events on the given event-type mask. */
    fun withoutContentChanged(eventTypes: Int): Int =
        eventTypes and EVENT_WINDOW_CONTENT_CHANGED.inv()

    /**
     * Orders the stop queue: [DEFERRED_PACKAGE] (Chrome) is moved to the end,
     * everything else keeps its relative order.
     */
    fun orderQueue(packages: List<String>): List<String> {
        if (DEFERRED_PACKAGE !in packages) return packages
        return packages.filter { it != DEFERRED_PACKAGE } + DEFERRED_PACKAGE
    }
}
