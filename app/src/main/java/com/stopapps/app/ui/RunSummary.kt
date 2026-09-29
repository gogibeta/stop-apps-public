package com.stopapps.app.ui

import java.util.Locale

/**
 * Structured summary of a finished stop run.
 *
 * Pure Kotlin with no Android dependencies so it can be unit-tested on the
 * JVM. The engine emits a machine-readable marker into RunLog
 * ("[dbg] run finished: ..."); the UI parses it back into this.
 */
data class RunSummary(
    val stopped: Int,
    val failed: Int,
    val skipped: Int,
    /** Device available-RAM delta (after minus before), in bytes. May be <= 0. */
    val ramFreedBytes: Long,
    /** Wall-clock run time, in milliseconds. */
    val durationMs: Long,
    val turbo: Boolean
)

private val MARKER_RE = Regex(
    """run finished:\s*stopped=(\d+)\s+failed=(\d+)\s+skipped=(\d+)""" +
        """(?:\s+ram_freed_mb=(-?\d+))?(?:\s+duration_s=(\d+))?(?:\s+turbo=(true|false))?"""
)

/**
 * Builds the machine-readable marker the engine appends to RunLog.
 * Newer fields are optional in the grammar so older markers still parse.
 */
fun formatRunMarker(
    stopped: Int,
    failed: Int,
    skipped: Int,
    ramFreedBytes: Long,
    durationMs: Long,
    turbo: Boolean
): String =
    "run finished: stopped=$stopped failed=$failed skipped=$skipped " +
        "ram_freed_mb=${ramFreedBytes / 1_048_576L} " +
        "duration_s=${durationMs / 1000L} " +
        "turbo=$turbo"

/** Parses a marker previously built by [formatRunMarker]; null when malformed. */
fun parseRunSummary(marker: String): RunSummary? {
    val m = MARKER_RE.find(marker) ?: return null
    val g = m.groupValues
    return RunSummary(
        stopped = g[1].toInt(),
        failed = g[2].toInt(),
        skipped = g[3].toInt(),
        ramFreedBytes = g[4].ifEmpty { "0" }.toLong() * 1_048_576L,
        durationMs = g[5].ifEmpty { "0" }.toLong() * 1000L,
        turbo = g[6] == "true"
    )
}

/** Human-friendly byte count, e.g. "42 MB", "1.50 GB". Never negative. */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val gb = b / 1_073_741_824.0
    if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb)
    val mb = b / 1_048_576.0
    if (mb >= 1) return String.format(Locale.US, "%.0f MB", mb)
    val kb = b / 1024.0
    return String.format(Locale.US, "%.0f KB", kb)
}
