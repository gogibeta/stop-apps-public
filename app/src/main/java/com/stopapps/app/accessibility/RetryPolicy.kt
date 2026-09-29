package com.stopapps.app.accessibility

/**
 * Pure retry policy for one package attempt, mirroring the reference
 * (AppSleep 2.4):
 * - Missing/timeout/click-failure signals get exactly one reopen-and-retry,
 *   then a terminal outcome.
 * - A disabled "Force stop" on MIUI means "already stopped" -> skip.
 *
 * Kept in its own file (no Android dependencies) so it can be unit-tested
 * on the JVM.
 */

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

/** Pure retry policy. */
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
