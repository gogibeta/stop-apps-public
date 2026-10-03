package com.stopapps.app

import com.stopapps.app.accessibility.AttemptSignal
import com.stopapps.app.accessibility.AutomationPolicy
import com.stopapps.app.accessibility.NodeMatchers
import com.stopapps.app.accessibility.PackageOutcome
import com.stopapps.app.accessibility.Step
import com.stopapps.app.accessibility.nextStep
import com.stopapps.app.data.AutoWhitelist
import com.stopapps.app.data.RunningClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure logic in the app:
 * button id/text matching and the automatic safety whitelist.
 */
class StopAppsLogicTest {

    // ---------- NodeMatchers: force stop ----------

    @Test
    fun forceStopId_matchesAospIds() {
        assertTrue(NodeMatchers.isForceStopId("com.android.settings:id/force_stop"))
        assertTrue(NodeMatchers.isForceStopId("com.android.settings:id/menu_item_force_stop"))
    }

    @Test
    fun forceStopId_matchesMiuiAndOemVariants() {
        assertTrue(NodeMatchers.isForceStopId("com.miui.securitycenter:id/force_stop"))
        assertTrue(NodeMatchers.isForceStopId("com.example:id/forceStopButton"))
        assertTrue(NodeMatchers.isForceStopId("com.example:id/btn_force_stop"))
    }

    @Test
    fun forceStopId_rejectsUnrelatedIds() {
        assertFalse(NodeMatchers.isForceStopId("com.android.settings:id/button1"))
        assertFalse(NodeMatchers.isForceStopId("android:id/button1"))
        assertFalse(NodeMatchers.isForceStopId("com.example:id/force_stop_title"))
        assertFalse(NodeMatchers.isForceStopId(null))
        assertFalse(NodeMatchers.isForceStopId(""))
    }

    @Test
    fun forceStopText_matchesEnglishLabel() {
        assertTrue(NodeMatchers.isForceStopText("Force stop"))
        assertTrue(NodeMatchers.isForceStopText("force stop"))
        assertFalse(NodeMatchers.isForceStopText("Force Stop Updates"))
        assertFalse(NodeMatchers.isForceStopText(null))
    }

    // ---------- NodeMatchers: confirm ----------

    @Test
    fun confirmId_matchesDialogOkButton() {
        assertTrue(NodeMatchers.isConfirmId("android:id/button1"))
        assertTrue(NodeMatchers.isConfirmId("com.android.settings:id/button1"))
    }

    @Test
    fun confirmId_rejectsOtherButtons() {
        assertFalse(NodeMatchers.isConfirmId("android:id/button2"))
        assertFalse(NodeMatchers.isConfirmId("com.example:id/force_stop"))
        assertFalse(NodeMatchers.isConfirmId(null))
    }

    @Test
    fun confirmText_matchesOkLabels() {
        assertTrue(NodeMatchers.isConfirmText("OK"))
        assertTrue(NodeMatchers.isConfirmText("ok"))
        assertFalse(NodeMatchers.isConfirmText("Cancel"))
        assertFalse(NodeMatchers.isConfirmText(null))
    }

    // ---------- AutoWhitelist ----------

    @Test
    fun whitelist_coversCriticalSystemPackages() {
        val w = AutoWhitelist.SYSTEM_PACKAGES
        assertTrue(w.contains("com.android.systemui"))
        assertTrue(w.contains("com.android.settings"))
        assertTrue(w.contains("com.miui.securitycenter"))
        assertTrue(w.contains("com.android.phone"))
        assertTrue(w.contains("com.google.android.gms"))
        assertTrue(w.contains("com.android.vending"))
    }

    @Test
    fun whitelist_doesNotContainOrdinaryApps() {
        val w = AutoWhitelist.SYSTEM_PACKAGES
        assertFalse(w.contains("com.whatsapp"))
        assertFalse(w.contains("com.instagram.android"))
        assertFalse(w.contains("com.stopapps.app"))
    }

    @Test
    fun whitelist_hasExpectedSize() {
        // 54 packages recovered from the reference implementation.
        assertTrue(AutoWhitelist.SYSTEM_PACKAGES.size == 54)
    }

    @Test
    fun settingsPackages_coversKnownOemHosts() {
        val s = AutoWhitelist.SETTINGS_PACKAGES
        assertTrue(s.contains("com.android.settings"))
        assertTrue(s.contains("com.miui.securitycenter"))
        assertTrue(s.contains("com.samsung.android.settings"))
    }

    // ---------- AutomationPolicy: timing ----------

    @Test
    fun policy_packageWatchdog_isEightSeconds() {
        assertEquals(8000L, AutomationPolicy.PACKAGE_TIMEOUT_MS)
    }

    @Test
    fun policy_interDelay_normalIs1000_turboIs0() {
        assertEquals(1000L, AutomationPolicy.interDelayMs(false))
        assertEquals(0L, AutomationPolicy.interDelayMs(true))
    }

    @Test
    fun policy_preClickDelay_normalIs100_turboIs0() {
        assertEquals(100L, AutomationPolicy.preClickDelayMs(false))
        assertEquals(0L, AutomationPolicy.preClickDelayMs(true))
    }

    @Test
    fun policy_preForceStopDelay_normalIs100_turboIs0() {
        assertEquals(100L, AutomationPolicy.preForceStopDelayMs(false))
        assertEquals(0L, AutomationPolicy.preForceStopDelayMs(true))
    }

    // ---------- AutomationPolicy: event types ----------

    @Test
    fun policy_runEventTypes_matchReference4128() {
        assertEquals(32, AutomationPolicy.EVENT_WINDOW_STATE_CHANGED)
        assertEquals(2048, AutomationPolicy.EVENT_WINDOW_CONTENT_CHANGED)
        assertEquals(4096, AutomationPolicy.EVENT_VIEW_SCROLLED)
        assertEquals(4128, AutomationPolicy.RUN_EVENT_TYPES)
    }

    @Test
    fun policy_withContentChanged_setsBit2048() {
        assertEquals(4128 or 2048, AutomationPolicy.withContentChanged(4128))
        // Idempotent.
        assertEquals(4128 or 2048, AutomationPolicy.withContentChanged(4128 or 2048))
    }

    @Test
    fun policy_withoutContentChanged_clearsBit2048() {
        assertEquals(4128, AutomationPolicy.withoutContentChanged(4128 or 2048))
        // Idempotent.
        assertEquals(4128, AutomationPolicy.withoutContentChanged(4128))
    }

    @Test
    fun policy_eventTypeToggle_roundTrip() {
        val base = AutomationPolicy.RUN_EVENT_TYPES
        assertEquals(base, AutomationPolicy.withoutContentChanged(AutomationPolicy.withContentChanged(base)))
    }

    // ---------- AutomationPolicy: queue ordering ----------

    @Test
    fun policy_chromeMovedToEnd() {
        val q = AutomationPolicy.orderQueue(
            listOf("com.android.chrome", "com.a", "com.b")
        )
        assertEquals(listOf("com.a", "com.b", "com.android.chrome"), q)
    }

    @Test
    fun policy_queueWithoutChrome_unchanged() {
        val q = listOf("com.a", "com.b")
        assertEquals(q, AutomationPolicy.orderQueue(q))
    }

    @Test
    fun policy_queuePreservesRelativeOrder() {
        val q = AutomationPolicy.orderQueue(
            listOf("com.z", "com.android.chrome", "com.a")
        )
        assertEquals(listOf("com.z", "com.a", "com.android.chrome"), q)
    }

    @Test
    fun policy_emptyQueue_ok() {
        assertTrue(AutomationPolicy.orderQueue(emptyList()).isEmpty())
    }

    // ---------- RunningClassifier: AppSleep 2.4 parity (v2.c.c) ----------

    private fun installedApp(
        pkg: String,
        enabled: Boolean = true
    ) = RunningClassifier.InstalledApp(pkg, enabled)

    private val launchable = setOf("com.a", "com.b", "com.c", "com.d", "com.e", "com.f", "com.g", "com.h")

    @Test
    fun classifier_filterRunning_keepsForceStoppedAppsLikeReference() {
        // The decompiled reference loop (v2.C2671c.a) has no FLAG_STOPPED
        // check: a force-stopped app is still listed, exactly like AppSleep.
        val apps = listOf(
            installedApp("com.a"),
            installedApp("com.b"),
            installedApp("com.c")
        )
        assertEquals(
            listOf("com.a", "com.b", "com.c"),
            RunningClassifier.filterRunning(apps, emptySet(), emptySet()) { it in launchable }
        )
    }

    @Test
    fun classifier_lastStoppedNever_isMinusOne() {
        assertEquals(-1L, RunningClassifier.LAST_STOPPED_NEVER)
    }

    @Test
    fun classifier_filterRunning_keepsEligibleInInstalledOrder() {
        val apps = listOf(
            installedApp("com.b"), installedApp("com.a"), installedApp("com.c")
        )
        assertEquals(
            listOf("com.b", "com.a", "com.c"),
            RunningClassifier.filterRunning(apps, emptySet(), emptySet()) { it in launchable }
        )
    }

    @Test
    fun classifier_filterRunning_respectsExclusions() {
        // Exclusions (self, launcher, keyboard, whitelist, invalid) still
        // hide apps even though there is no stopped/usage filtering.
        val apps = listOf(
            installedApp("com.a"),
            installedApp("com.b"),
            installedApp("com.c")
        )
        assertEquals(
            listOf("com.a", "com.c"),
            RunningClassifier.filterRunning(
                apps, setOf("com.b"), emptySet()
            ) { it in launchable }
        )
    }

    @Test
    fun classifier_filterRunning_dropsDisabledApps() {
        val apps = listOf(
            installedApp("com.a", enabled = false),
            installedApp("com.b")
        )
        assertEquals(
            listOf("com.b"),
            RunningClassifier.filterRunning(apps, emptySet(), emptySet()) { it in launchable }
        )
    }

    @Test
    fun classifier_filterRunning_dropsNonLaunchable() {
        val apps = listOf(installedApp("com.a"), installedApp("com.zzz"))
        assertEquals(
            listOf("com.a"),
            RunningClassifier.filterRunning(apps, emptySet(), emptySet()) { it in launchable }
        )
    }

    @Test
    fun classifier_filterRunning_dropsExclusions() {
        val apps = listOf(
            installedApp("com.a"), // self
            installedApp("com.b"), // launcher
            installedApp("com.c"), // keyboard
            installedApp("com.d"), // user whitelist
            installedApp("com.e")
        )
        val exclusions = setOf("com.a", "com.b", "com.c", "com.d")
        assertEquals(
            listOf("com.e"),
            RunningClassifier.filterRunning(apps, exclusions, emptySet()) { it in launchable }
        )
    }

    @Test
    fun classifier_filterRunning_dropsSafetyList() {
        val apps = listOf(installedApp("com.android.systemui"), installedApp("com.a"))
        assertEquals(
            listOf("com.a"),
            RunningClassifier.filterRunning(apps, emptySet(), setOf("com.android.systemui")) {
                it == "com.a" || it == "com.android.systemui"
            }
        )
    }

    @Test
    fun classifier_filterRunning_dropsEmptyPackageNames() {
        val apps = listOf(installedApp(""), installedApp("com.a"))
        assertEquals(
            listOf("com.a"),
            RunningClassifier.filterRunning(apps, emptySet(), emptySet()) { it in launchable }
        )
    }

    @Test
    fun classifier_filterRunning_noRecencyWindow_zeroUsageStillRunning() {
        // 7-vs-16 regression: running status must NOT depend on usage
        // events. An app with no usage at all still counts when it is
        // enabled, not force-stopped and launchable.
        val apps = (1..16).map { installedApp("com.app$it") }
        val result = RunningClassifier.filterRunning(apps, emptySet(), emptySet()) { true }
        assertEquals(16, result.size)
    }

    @Test
    fun classifier_eventPackages_firstSeenOrderDeduped() {
        val events = listOf("com.b", "com.a", "com.b", "com.c")
        assertEquals(
            linkedSetOf("com.b", "com.a", "com.c"),
            RunningClassifier.eventPackages(events) { it in launchable }
        )
    }

    @Test
    fun classifier_eventPackages_dropsNonLaunchableAndEmpty() {
        val events = listOf("", "com.zzz", "com.a", "com.zzz")
        assertEquals(
            linkedSetOf("com.a"),
            RunningClassifier.eventPackages(events) { it in launchable }
        )
    }

    @Test
    fun classifier_eventPackages_checksEachPackageOnce() {
        var checks = 0
        val events = listOf("com.a", "com.a", "com.a")
        RunningClassifier.eventPackages(events) { checks++; true }
        assertEquals(1, checks)
    }

    @Test
    fun classifier_rehabilitate_splitsByUsageEvents() {
        val (stillInvalid, rehabilitated) = RunningClassifier.rehabilitate(
            invalid = listOf("com.x", "com.y", "com.z"),
            eventPkgs = setOf("com.y")
        )
        assertEquals(listOf("com.x", "com.z"), stillInvalid)
        assertEquals(listOf("com.y"), rehabilitated)
    }

    @Test
    fun classifier_rehabilitate_emptyInvalid_nothingToDo() {
        val (stillInvalid, rehabilitated) = RunningClassifier.rehabilitate(
            invalid = emptyList(),
            eventPkgs = setOf("com.y")
        )
        assertTrue(stillInvalid.isEmpty())
        assertTrue(rehabilitated.isEmpty())
    }

    @Test
    fun classifier_rehabilitate_noEvents_allStayInvalid() {
        // last_stopped_time == -1L -> no event query -> everything stays invalid.
        val (stillInvalid, rehabilitated) = RunningClassifier.rehabilitate(
            invalid = listOf("com.x", "com.y"),
            eventPkgs = emptySet()
        )
        assertEquals(listOf("com.x", "com.y"), stillInvalid)
        assertTrue(rehabilitated.isEmpty())
    }

    // ---------- nextStep: retry policy ----------

    @Test
    fun nextStep_confirmClickOk_alwaysStopped() {
        assertEquals(
            Step.Terminal(PackageOutcome.STOPPED),
            nextStep(AttemptSignal.CONFIRM_CLICK_OK, false, false)
        )
        assertEquals(
            Step.Terminal(PackageOutcome.STOPPED),
            nextStep(AttemptSignal.CONFIRM_CLICK_OK, true, true)
        )
    }

    @Test
    fun nextStep_missingOrTimeout_retriesOnceThenFails() {
        for (signal in listOf(
            AttemptSignal.FORCE_STOP_MISSING,
            AttemptSignal.TIMEOUT,
            AttemptSignal.CONFIRM_CLICK_FAILED
        )) {
            for (miui in listOf(false, true)) {
                assertEquals(Step.Retry, nextStep(signal, false, miui))
                assertEquals(
                    Step.Terminal(PackageOutcome.FAILED),
                    nextStep(signal, true, miui)
                )
            }
        }
    }

    @Test
    fun nextStep_forceStopDisabled_miuiSkipsImmediately() {
        assertEquals(
            Step.Terminal(PackageOutcome.SKIPPED),
            nextStep(AttemptSignal.FORCE_STOP_DISABLED, false, true)
        )
        assertEquals(
            Step.Terminal(PackageOutcome.SKIPPED),
            nextStep(AttemptSignal.FORCE_STOP_DISABLED, true, true)
        )
    }

    @Test
    fun nextStep_forceStopDisabled_nonMiuiRetriesOnceThenSkips() {
        assertEquals(
            Step.Retry,
            nextStep(AttemptSignal.FORCE_STOP_DISABLED, false, false)
        )
        assertEquals(
            Step.Terminal(PackageOutcome.SKIPPED),
            nextStep(AttemptSignal.FORCE_STOP_DISABLED, true, false)
        )
    }

    @Test
    fun nextStep_confirmDisabled_miuiSkips() {
        assertEquals(
            Step.Terminal(PackageOutcome.SKIPPED),
            nextStep(AttemptSignal.CONFIRM_DISABLED, false, true)
        )
    }

    @Test
    fun nextStep_confirmDisabled_nonMiuiRetriesOnceThenFails() {
        assertEquals(
            Step.Retry,
            nextStep(AttemptSignal.CONFIRM_DISABLED, false, false)
        )
        assertEquals(
            Step.Terminal(PackageOutcome.FAILED),
            nextStep(AttemptSignal.CONFIRM_DISABLED, true, false)
        )
    }

    // ---------- NodeMatchers: extra confirm cases ----------

    @Test
    fun confirmId_matchesFullSettingsIds() {
        assertTrue(NodeMatchers.isConfirmId("com.android.settings:id/button1"))
        assertTrue(NodeMatchers.isConfirmId("com.miui.securitycenter:id/button1"))
    }

    @Test
    fun confirmText_matchesYesAndLocalized() {
        assertTrue(NodeMatchers.isConfirmText("Yes"))
        assertTrue(NodeMatchers.isConfirmText("yes"))
        assertTrue(NodeMatchers.isConfirmText("确定"))
        assertFalse(NodeMatchers.isConfirmText("No"))
        assertFalse(NodeMatchers.isConfirmText(""))
    }

    @Test
    fun forceStopId_rejectsButton1Variants() {
        // button1 is the dialog OK button, never "Force stop".
        assertFalse(NodeMatchers.isForceStopId("android:id/button1"))
        assertFalse(NodeMatchers.isForceStopId("com.android.settings:id/button1"))
    }

    // ---------- RunSummary: marker round-trip ----------

    @Test
    fun runMarker_roundTrip() {
        val marker = com.stopapps.app.ui.formatRunMarker(
            stopped = 16, failed = 1, skipped = 2,
            ramFreedBytes = 42L * 1_048_576L, durationMs = 11_000L, turbo = true
        )
        val s = com.stopapps.app.ui.parseRunSummary(marker)
        assertEquals(16, s?.stopped)
        assertEquals(1, s?.failed)
        assertEquals(2, s?.skipped)
        assertEquals(42L * 1_048_576L, s?.ramFreedBytes)
        assertEquals(11_000L, s?.durationMs)
        assertEquals(true, s?.turbo)
    }

    @Test
    fun runMarker_legacyMarkerStillParses() {
        // Markers written by older versions had no ram/duration/turbo fields.
        val s = com.stopapps.app.ui.parseRunSummary("run finished: stopped=16 failed=0 skipped=0")
        assertEquals(16, s?.stopped)
        assertEquals(0, s?.failed)
        assertEquals(0, s?.skipped)
        assertEquals(0L, s?.ramFreedBytes)
        assertEquals(0L, s?.durationMs)
        assertEquals(false, s?.turbo)
    }

    @Test
    fun runMarker_rejectsGarbage() {
        assertEquals(null, com.stopapps.app.ui.parseRunSummary("hello world"))
        assertEquals(null, com.stopapps.app.ui.parseRunSummary(""))
    }

    @Test
    fun formatBytes_units() {
        assertEquals("42 MB", com.stopapps.app.ui.formatBytes(42L * 1_048_576L))
        assertEquals("1.50 GB", com.stopapps.app.ui.formatBytes(1_610_612_736L))
        assertEquals("512 KB", com.stopapps.app.ui.formatBytes(512L * 1024L))
        // Never negative: a noisy RAM delta is clamped.
        assertEquals("0 KB", com.stopapps.app.ui.formatBytes(-5L))
    }
}
