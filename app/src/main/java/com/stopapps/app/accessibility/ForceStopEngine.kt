package com.stopapps.app.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Drives one stop run: for every target package it opens the system
 * "App info" screen and clicks "Force stop" -> confirmation, using the
 * [StopAccessService] accessibility events as its eyes.
 *
 * The automation mirrors the publicly observable behavior of the reference
 * app (AppSleep 2.4, reverse-engineered for interoperability):
 *
 * - Only window-state-changed (32) + view-scrolled (4096) events are
 *   monitored; window-content-changed (2048) is enabled while the "Force
 *   stop" button is on screen and disabled after the confirmation is
 *   clicked.
 * - The node tree is scanned from `event.source` (the node that changed),
 *   exactly like the reference; `rootInActiveWindow` is only a fallback.
 * - "Force stop" is matched by the *localized* Settings strings for the
 *   resource names `force_stop` / `menu_item_force_stop`, resolved from the
 *   Settings package that raised the event; a clickable node is found either
 *   directly or via its clickable ancestor.
 * - The confirmation ("OK") button is matched by the full view ids
 *   `com.android.settings:id/button1` / `android:id/button1`, falling back
 *   to the localized system OK / Yes strings.
 * - Per-package watchdog: 8 seconds. One reopen-and-retry per package.
 * - Inter-package delay: 1000 ms normally, 0 ms in turbo mode.
 * - Pre-confirmation-click delay: 100 ms normally, 50 ms in turbo mode.
 * - `com.android.chrome` is stopped last.
 * - Window ids already handled are skipped (deduplication).
 * - A disabled "Force stop" button on MIUI means the package can never be
 *   stopped this way: it is recorded as MIUI-invalid (reference
 *   `INVALID_PACK`), skipped, and hidden from the running list until usage
 *   events rehabilitate it.
 * - Both clicks are fire-and-forget like the reference: the click result is
 *   not checked and there is no post-click verification. Once "OK" is
 *   clicked the attempt counts as stopped.
 *
 * Kept deliberately:
 * - Never clicks a node after recycling it, and always clicks the
 *   *actionable* (clickable) ancestor rather than a text child.
 * - The run refuses to start when the accessibility service is enabled but
 *   not connected, instead of hanging on a dead run.
 */
class ForceStopEngine(private val appContext: Context) {

    interface Listener {
        fun onLog(line: String)
        fun onProgress(done: Int, total: Int, currentPackage: String?)
        fun onFinished(result: RunResult)
    }

    data class RunResult(
        val stopped: List<String>,
        val failed: List<String>,
        val skipped: List<String>,
        /** Packages the reference records as MIUI-invalid (hidden from the running list). */
        val invalid: List<String> = emptyList()
    )

    private enum class Stage { IDLE, WAIT_FORCE_STOP, WAIT_CONFIRM }

    @Volatile
    var running = false
        private set

    @Volatile
    var currentPackage: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: kotlinx.coroutines.Job? = null
    private var listener: Listener? = null
    private var turbo = false

    private var stage = Stage.IDLE
    private var targetPkg = ""
    private var attemptSignal: CompletableDeferred<AttemptSignal>? = null

    private val handledWindowIds = mutableSetOf<Int>()
    private val forceStopTextCache = mutableMapOf<String, Set<String>>()
    // MIUI detection mirrors the reference's `u.w()`: the
    // `ro.miui.ui.version.name` system property, not the manufacturer.
    private val miui: Boolean = detectMiui()

    private fun detectMiui(): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java)
            val raw = method.invoke(cls, "ro.miui.ui.version.name")
            val value = raw as? String
            value != null && value.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ run

    /**
     * Starts a run. Returns false (and reports the failure through
     * [listener]) when the accessibility service is not connected, so the
     * caller can tell the user what to do instead of hanging on a dead run.
     */
    fun start(packages: List<String>, turbo: Boolean, listener: Listener): Boolean {
        if (running) return true
        val service = StopAccessService.instance
        if (service == null) {
            listener.onLog("Accessibility service is enabled but not connected yet. Please wait a moment and try again.")
            listener.onFinished(RunResult(emptyList(), packages.toList(), emptyList()))
            return false
        }
        this.listener = listener
        this.turbo = turbo
        running = true
        handledWindowIds.clear()
        forceStopTextCache.clear()
        stage = Stage.IDLE
        service.setAggressiveMonitoring(true)
        val queue = AutomationPolicy.orderQueue(packages.toList())
        job = scope.launch {
            try {
                runQueue(queue)
            } finally {
                finish()
            }
        }
        return true
    }

    fun cancel() {
        running = false
        try {
            job?.cancel()
        } catch (_: Exception) {
        }
    }

    private suspend fun runQueue(queue: List<String>) {
        val stopped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        for ((index, pkg) in queue.withIndex()) {
            if (!running) break
            targetPkg = pkg
            currentPackage = pkg
            listener?.onProgress(index, queue.size, pkg)
            listener?.onLog("# ${index + 1}/${queue.size} ${appLabel(pkg)}")
            val (outcome, markInvalid) = stopOnePackage(pkg)
            if (markInvalid) invalid += pkg
            when (outcome) {
                PackageOutcome.STOPPED -> {
                    stopped += pkg
                    listener?.onLog("Stopped ${appLabel(pkg)}")
                }
                PackageOutcome.SKIPPED -> {
                    skipped += pkg
                    listener?.onLog("Skipped ${appLabel(pkg)}")
                }
                PackageOutcome.FAILED -> {
                    failed += pkg
                    listener?.onLog("Could not stop ${appLabel(pkg)}")
                }
            }
            currentPackage = null
            if (running && index < queue.size - 1) {
                delay(AutomationPolicy.interDelayMs(turbo))
            }
        }
        listener?.onProgress(queue.size, queue.size, null)
        listener?.onFinished(RunResult(stopped, failed, skipped, invalid))
    }

    private fun finish() {
        running = false
        currentPackage = null
        stage = Stage.IDLE
        attemptSignal = null
        try {
            StopAccessService.instance?.setAggressiveMonitoring(false)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ one package

    /**
     * Stops one package. Returns the outcome plus whether the package must be
     * recorded as MIUI-invalid (reference `INVALID_PACK`): a disabled
     * "Force stop" button on MIUI means the package can never be stopped
     * this way, so it is hidden from the running list until usage events
     * rehabilitate it.
     */
    private suspend fun stopOnePackage(pkg: String): Pair<PackageOutcome, Boolean> {
        var retried = false
        var invalid = false
        while (true) {
            if (!running) return PackageOutcome.FAILED to false
            stage = Stage.WAIT_FORCE_STOP
            attemptSignal = CompletableDeferred()
            openAppDetails(pkg)
            val signal = try {
                withTimeout(AutomationPolicy.PACKAGE_TIMEOUT_MS) {
                    attemptSignal!!.await()
                }
            } catch (_: TimeoutCancellationException) {
                AttemptSignal.TIMEOUT
            } catch (_: CancellationException) {
                return PackageOutcome.FAILED to false
            }
            if (signal == AttemptSignal.FORCE_STOP_DISABLED && miui) invalid = true
            when (val step = nextStep(signal, retried, miui)) {
                is Step.Terminal -> {
                    if (step.outcome != PackageOutcome.STOPPED) {
                        listener?.onLog("  [dbg] giving up on ${appLabel(pkg)} (signal=$signal)")
                    }
                    return step.outcome to invalid
                }
                Step.Retry -> {
                    listener?.onLog("  [dbg] retrying ${appLabel(pkg)} (signal=$signal)")
                    retried = true
                    // Loop re-opens the App info screen and tries again.
                }
            }
        }
    }

    private fun openAppDetails(pkg: String) {
        try {
            // Flags mirror the reference exactly: NEW_TASK | CLEAR_TOP |
            // EXCLUDE_FROM_RECENTS | NO_HISTORY (1417707520). CLEAR_TOP is
            // essential: without it, consecutive opens for different packages
            // can reuse a stale App info screen.
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")
            ).setFlags(1417707520)
            appContext.startActivity(intent)
        } catch (_: Exception) {
            attemptSignal?.complete(AttemptSignal.TIMEOUT)
        }
    }

    // ----------------------------------------------------------------- events

    /**
     * Called on the main thread by [StopAccessService] for every
     * accessibility event while monitoring is aggressive.
     *
     * Mirrors the reference's `u2/i` dispatcher exactly:
     * - the tree is ALWAYS `event.source` (never `rootInActiveWindow`);
     * - the source must be a container (`childCount != 0`) with a valid
     *   window id;
     * - windows already handled (a button was found and clicked in them)
     *   are skipped via [handledWindowIds].
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!running) return
        val type = event.eventType
        // The run monitors window-state-changed (32) + view-scrolled (4096);
        // window-content-changed (2048) is toggled on while the "Force stop"
        // button is on screen.
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val eventPkg = event.packageName?.toString() ?: return
        if (!isSettingsHost(eventPkg)) return
        // Like the reference: only the event's own source node, which must
        // be a container from a real window. Leaf sources (a single
        // TextView etc.) cannot contain the button and are dropped.
        val source: AccessibilityNodeInfo = try {
            event.source
        } catch (_: Exception) {
            null
        } ?: return
        try {
            if (source.childCount == 0) return
            val winId = source.windowId
            if (winId <= 0) return
            // Like the reference's f18183h: skip windows where a button was
            // already found and clicked. The id is added only when a button
            // is found (in the handlers below), not on first sight, so a
            // window that is still loading gets retried on later events.
            if (winId in handledWindowIds) return
            when (stage) {
                Stage.WAIT_FORCE_STOP -> handleForceStopWindow(source, eventPkg)
                Stage.WAIT_CONFIRM -> handleConfirmWindow(source)
                Stage.IDLE -> Unit
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------- force-stop stage

    private fun handleForceStopWindow(root: AccessibilityNodeInfo, eventPkg: String) {
        if (stage != Stage.WAIT_FORCE_STOP) return
        val service = StopAccessService.instance ?: return
        val button = findForceStopButton(root, eventPkg)
        if (button == null) {
            listener?.onLog("  [dbg] app-info shown but Force-stop button not found in event tree")
            completeAttempt(AttemptSignal.FORCE_STOP_MISSING)
            return
        }
        // Like the reference (p2/e): the window is marked handled as soon as
        // its button is found, so later events from it are ignored.
        try {
            handledWindowIds.add(button.windowId)
        } catch (_: Exception) {
        }
        try {
            if (!button.isEnabled) {
                // Disabled "Force stop": on MIUI the package is recorded as
                // MIUI-invalid (reference INVALID_PACK); otherwise give it
                // one reopen-and-retry.
                listener?.onLog("  [dbg] Force-stop button found but DISABLED (miui=$miui)")
                completeAttempt(AttemptSignal.FORCE_STOP_DISABLED)
                return
            }
            listener?.onLog("  [dbg] Force-stop button found, clicking")
            // From here on, watch content changes so the confirmation dialog
            // is observed the moment it appears.
            service.updateEventTypes(AutomationPolicy::withContentChanged)
            scope.launch {
                delay(AutomationPolicy.PRE_FORCE_STOP_DELAY_MS)
                val clickResult = try {
                    withContext(Dispatchers.Main) {
                        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } catch (_: Exception) {
                    false
                } finally {
                    try {
                        button.recycle()
                    } catch (_: Exception) {
                    }
                }
                listener?.onLog("  [dbg] Force-stop click dispatched, result=$clickResult")
                // Like the reference (p2/e): the click result is not checked
                // and there is no post-click verification. The confirm
                // handler takes over from here; the dialog's own
                // window-state-changed event drives handleConfirmWindow.
                stage = Stage.WAIT_CONFIRM
            }
        } catch (_: Exception) {
            try {
                button.recycle()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------ confirmation stage

    private fun handleConfirmWindow(root: AccessibilityNodeInfo) {
        if (stage != Stage.WAIT_CONFIRM) return
        listener?.onLog("  [dbg] confirmation dialog event seen")
        val ok = findConfirmButton(root)
        if (ok == null) {
            listener?.onLog("  [dbg] dialog seen but OK button not found in event tree")
            return
        }
        // Like the reference (p2/b): mark the dialog window handled when its
        // button is found.
        try {
            handledWindowIds.add(ok.windowId)
        } catch (_: Exception) {
        }
        clickConfirmButton(ok)
    }

    private fun clickConfirmButton(ok: AccessibilityNodeInfo) {
        try {
            if (!ok.isEnabled) {
                listener?.onLog("  [dbg] OK button found but DISABLED")
                completeAttempt(AttemptSignal.CONFIRM_DISABLED)
                return
            }
            listener?.onLog("  [dbg] OK button found, clicking")
            // Dialog button found: content events are no longer needed.
            try {
                StopAccessService.instance
                    ?.updateEventTypes(AutomationPolicy::withoutContentChanged)
            } catch (_: Exception) {
            }
            stage = Stage.IDLE
            scope.launch {
                delay(AutomationPolicy.preClickDelayMs(turbo))
                val clickResult = try {
                    withContext(Dispatchers.Main) {
                        ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } catch (_: Exception) {
                    false
                } finally {
                    try {
                        ok.recycle()
                    } catch (_: Exception) {
                    }
                }
                listener?.onLog("  [dbg] OK click dispatched, result=$clickResult")
                // Like the reference (p2/b): the click result is not checked
                // and the stopped state is not re-verified. Once OK is
                // clicked the attempt counts as stopped.
                completeAttempt(AttemptSignal.CONFIRM_CLICK_OK)
            }
        } catch (_: Exception) {
            try {
                ok.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun completeAttempt(signal: AttemptSignal) {
        val s = attemptSignal ?: return
        if (!s.isCompleted) s.complete(signal)
    }

    // -------------------------------------------------------------- node find

    private fun findForceStopButton(
        root: AccessibilityNodeInfo,
        eventPkg: String
    ): AccessibilityNodeInfo? {
        // 1. Localized Settings strings for the known resource names.
        for (text in localizedForceStopTexts(eventPkg)) {
            findClickableByText(root, text)?.let { return it }
        }
        // 2. English fallback (also used by the reference on MIUI).
        findClickableByText(root, "Force stop")?.let { return it }
        // 3. View-id fallback for OEM Settings variants.
        return findClickableByViewIdSuffix(root)
    }

    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Exact view ids, like the reference.
        for (id in CONFIRM_VIEW_IDS) {
            findClickableByViewId(root, id)?.let { return it }
        }
        // 2. Localized system OK / Yes strings.
        for (resId in intArrayOf(android.R.string.ok, android.R.string.yes)) {
            val text = try {
                appContext.getString(resId)
            } catch (_: Exception) {
                null
            }
            if (text.isNullOrEmpty()) continue
            findClickableByText(root, text)?.let { return it }
        }
        return null
    }

    /**
     * Finds a clickable node by exact text, or the clickable ancestor of a
     * node whose text *contains* it (case-insensitive), skipping summary
     * nodes — mirroring the reference's recursive matcher. Always returns
     * the actionable (clickable) node, never a text child.
     */
    // --- node finders ---
    //
    // These deliberately do NOT recycle the nodes they walk. Buttons found
    // here are clicked after a short delay, so the search tree must stay
    // valid until the click lands; recycling it first would be a
    // use-after-recycle bug. Search trees are small and short-lived — the
    // garbage collector reclaims them. Only the button that was actually
    // clicked is recycled, after performAction() returns.

    private fun findClickableByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        if (text.isEmpty()) return null
        // Fast path: exact-text search, like the reference.
        val exact = try {
            root.findAccessibilityNodeInfosByText(text)
        } catch (_: Exception) {
            emptyList()
        }
        for (n in exact) {
            try {
                if (n.isClickable) return n
                val ancestor = n.clickableAncestor()
                if (ancestor != null) return ancestor
            } catch (_: Exception) {
            }
        }
        // Slow path: recursive contains-match with clickable-ancestor walk.
        return clickableAncestorByTextContains(root, text)
    }

    private fun clickableAncestorByTextContains(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        try {
            val id = node.viewIdResourceName
            if (id != null && id.endsWith("/summary")) return null
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrEmpty() &&
                nodeText.contains(text, ignoreCase = true)
            ) {
                return node.clickableAncestor()
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                val hit = clickableAncestorByTextContains(child, text)
                if (hit != null) return hit
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun findClickableByViewId(
        root: AccessibilityNodeInfo,
        fullId: String
    ): AccessibilityNodeInfo? {
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(fullId)
        } catch (_: Exception) {
            emptyList()
        }
        for (n in nodes) {
            try {
                if (n.isClickable) return n
                val ancestor = n.clickableAncestor()
                if (ancestor != null) return ancestor
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Fallback: breadth-first scan for any view id shaped like *force_stop*.
     */
    private fun findClickableByViewIdSuffix(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val n = deque.removeFirst()
            try {
                if (NodeMatchers.isForceStopId(n.viewIdResourceName)) {
                    val hit = if (n.isClickable) n else n.clickableAncestor()
                    if (hit != null) return hit
                }
                for (i in 0 until n.childCount) {
                    try {
                        n.getChild(i)?.let { deque.add(it) }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = this
        return try {
            while (p != null && !p.isClickable) p = p.parent
            p
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------ localized strings

    /**
     * The localized "Force stop" strings from the Settings package that
     * raised the event, for the resource names the reference uses. Cached
     * per run.
     */
    private fun localizedForceStopTexts(eventPkg: String): Set<String> {
        return forceStopTextCache.getOrPut(eventPkg) {
            val out = mutableSetOf<String>()
            try {
                val res = appContext.packageManager.getResourcesForApplication(eventPkg)
                for (name in FORCE_STOP_RES_NAMES) {
                    val id = res.getIdentifier(name, "string", eventPkg)
                    if (id != 0) {
                        try {
                            out += res.getString(id)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            out
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun isSettingsHost(pkg: String): Boolean =
        pkg == "com.android.settings" || pkg == "com.miui.securitycenter"

    private fun appLabel(pkg: String): String {
        return try {
            val ai = appContext.packageManager.getApplicationInfo(pkg, 0)
            appContext.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    companion object {
        private val FORCE_STOP_RES_NAMES = arrayOf("force_stop", "menu_item_force_stop")
        private val CONFIRM_VIEW_IDS = arrayOf(
            "com.android.settings:id/button1",
            "android:id/button1"
        )
    }
}

/** Internal signals driving one package attempt. */
internal enum class AttemptSignal {
    FORCE_STOP_MISSING,
    FORCE_STOP_DISABLED,
    CONFIRM_CLICK_OK,
    CONFIRM_CLICK_FAILED,
    CONFIRM_DISABLED,
    TIMEOUT
}

/** What the engine should do after an attempt signal. */
internal sealed interface Step {
    data class Terminal(val outcome: PackageOutcome) : Step
    data object Retry : Step
}

internal enum class PackageOutcome { STOPPED, SKIPPED, FAILED }

/**
 * Pure retry policy, mirroring the reference:
 * - Missing/timeout/click-failure signals get exactly one reopen-and-retry,
 *   then a terminal outcome.
 * - A disabled "Force stop" on MIUI means "already stopped" -> skip.
 */
internal fun nextStep(
    signal: AttemptSignal,
    alreadyRetried: Boolean,
    isMiui: Boolean
): Step = when (signal) {
    AttemptSignal.CONFIRM_CLICK_OK -> Step.Terminal(PackageOutcome.STOPPED)
    AttemptSignal.FORCE_STOP_DISABLED ->
        if (isMiui || alreadyRetried) Step.Terminal(PackageOutcome.SKIPPED)
        else Step.Retry
    AttemptSignal.CONFIRM_DISABLED ->
        if (isMiui) Step.Terminal(PackageOutcome.SKIPPED)
        else if (alreadyRetried) Step.Terminal(PackageOutcome.FAILED)
        else Step.Retry
    AttemptSignal.FORCE_STOP_MISSING,
    AttemptSignal.CONFIRM_CLICK_FAILED,
    AttemptSignal.TIMEOUT ->
        if (alreadyRetried) Step.Terminal(PackageOutcome.FAILED)
        else Step.Retry
}
