package com.stopapps.app.data

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.first

/**
 * One row in the app list.
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val isRunning: Boolean,
    /** Approximate RAM currently held by the app's processes, in kB. */
    val ramKb: Long,
    val lastUsed: Long
)

data class RamInfo(val totalBytes: Long, val availBytes: Long) {
    val usedBytes: Long get() = (totalBytes - availBytes).coerceAtLeast(0)
    val usedPercent: Int get() =
        if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val am: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun ramInfo(): RamInfo {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return RamInfo(mi.totalMem, mi.availMem)
    }

    /** True if the user granted "Usage access" (PACKAGE_USAGE_STATS app-op). */
    fun hasUsageAccess(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun openUsageAccessSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Loads every enabled, launchable app minus:
     * - this app, the default launcher, the active keyboard
     * - [AutoWhitelist.SYSTEM_PACKAGES]
     * - user-whitelisted packages
     * - packages still recorded as MIUI-invalid (stop attempt failed and no
     *   usage since the last stop run rehabilitated them)
     *
     * An app counts as "running" exactly like the reference app (AppSleep
     * 2.4, method `v2.c.c`): `PackageManager.getInstalledApplications(0)`
     * filtered to packages that are enabled, do NOT have the
     * `FLAG_STOPPED` bit set, and have a launch intent. There is no
     * usage-recency window — windowing is what caused the 7-vs-16 count
     * mismatch against the reference.
     *
     * Per-app RAM comes from the process list and is best-effort: the OS
     * restricts process visibility, so many running apps report 0 kB.
     */
    suspend fun loadApps(userWhitelist: Set<String>): List<AppEntry> {
        val selfPkg = context.packageName
        val launcherPkg = defaultLauncherPackage()
        val keyboardPkg = activeKeyboardPackage()

        // ---- exclusions (mirrors the reference) ----
        val exclusions = HashSet<String>(userWhitelist.size + 8)
        exclusions += selfPkg
        exclusions += launcherPkg
        exclusions += keyboardPkg
        exclusions += userWhitelist

        val prefs = PrefsStore(context)

        // ---- launch-intent cache (mirrors C2674f.f20780a) ----
        val launchIntentCache = HashMap<String, Boolean>()
        fun hasLaunchIntent(pkg: String): Boolean =
            launchIntentCache.getOrPut(pkg) {
                try {
                    pm.getLaunchIntentForPackage(pkg) != null
                } catch (_: Exception) {
                    false
                }
            }

        // ---- MIUI-invalid rehabilitation (mirrors v2.c.c) ----
        // Only when the persisted invalid set is nonempty; the usage-event
        // query only when last_stopped_time != -1L.
        val invalid = prefs.miInvalidPacks().toList()
        if (invalid.isNotEmpty()) {
            val lastStopped = prefs.lastStoppedTime.first()
            val eventPkgs: Set<String> =
                if (lastStopped != RunningClassifier.LAST_STOPPED_NEVER && hasUsageAccess()) {
                    try {
                        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                        val now = System.currentTimeMillis()
                        val events = usm.queryEvents(lastStopped, now)
                        val drained = ArrayList<String>(256)
                        val ev = android.app.usage.UsageEvents.Event()
                        while (events.hasNextEvent()) {
                            events.getNextEvent(ev)
                            val pkg = ev.packageName
                            if (!pkg.isNullOrEmpty()) drained += pkg
                        }
                        RunningClassifier.eventPackages(drained, ::hasLaunchIntent)
                    } catch (_: Exception) {
                        emptySet()
                    }
                } else {
                    emptySet()
                }
            val (stillInvalid, rehabilitated) =
                RunningClassifier.rehabilitate(invalid, eventPkgs)
            // Still-invalid packages are hidden from the list, like the
            // reference adding them to its exclusion list.
            exclusions += stillInvalid
            // Persist the rehabilitation (reference `l2.h` transform).
            try {
                prefs.removeMiInvalidPacks(rehabilitated)
            } catch (_: Exception) {
            }
        }

        // ---- installed apps (mirrors C2674f.d) ----
        val installed: List<ApplicationInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (_: Exception) {
            emptyList()
        }

        // ---- running set (mirrors the reference's final loop) ----
        val runningPkgs = RunningClassifier.filterRunning(
            installed.map {
                RunningClassifier.InstalledApp(
                    packageName = it.packageName ?: "",
                    enabled = it.enabled,
                    flags = it.flags
                )
            },
            exclusions,
            AutoWhitelist.SYSTEM_PACKAGES,
            ::hasLaunchIntent
        ).toHashSet()

        // ---- running processes -> per-package PSS (best effort) ----
        val ramByPkg = mutableMapOf<String, Long>()
        try {
            val procs = am.runningAppProcesses ?: emptyList()
            val pidToPkg = mutableMapOf<Int, String>()
            for (proc in procs) {
                // processName is usually the package name (or package:service).
                val pkg = proc.processName.substringBefore(':')
                pidToPkg[proc.pid] = pkg
            }
            if (pidToPkg.isNotEmpty()) {
                val memInfos = am.getProcessMemoryInfo(pidToPkg.keys.toIntArray())
                for (i in pidToPkg.keys.indices) {
                    val pkg = pidToPkg.values.elementAt(i)
                    val pss = memInfos[i].totalPss.toLong() // kB
                    ramByPkg[pkg] = (ramByPkg[pkg] ?: 0L) + pss
                }
            }
        } catch (_: Exception) {
        }

        // ---- usage stats -> last used ----
        val lastUsedByPkg = mutableMapOf<String, Long>()
        if (hasUsageAccess()) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60 * 1000L, now
                ) ?: emptyList()
                for (s in stats) {
                    if (s.lastTimeUsed > 0) {
                        lastUsedByPkg[s.packageName] =
                            maxOf(lastUsedByPkg[s.packageName] ?: 0L, s.lastTimeUsed)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // ---- entries: enabled + launchable, minus exclusions/safety ----
        // isRunning is the strict reference filter (FLAG_STOPPED clear).
        val out = ArrayList<AppEntry>(installed.size)
        for (ai in installed) {
            try {
                val pkg = ai.packageName ?: continue
                if (pkg.isEmpty()) continue
                if (pkg in exclusions) continue
                if (pkg in AutoWhitelist.SYSTEM_PACKAGES) continue
                if (!ai.enabled) continue
                // Launchable only, as before (the list came from a launcher
                // query); the reference additionally requires this.
                if (!hasLaunchIntent(pkg)) continue

                val label = try {
                    pm.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    pkg
                }
                val icon = try {
                    pm.getApplicationIcon(ai)
                } catch (_: Exception) {
                    null
                }
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                out += AppEntry(
                    packageName = pkg,
                    label = label,
                    icon = icon,
                    isSystem = isSystem,
                    isRunning = pkg in runningPkgs,
                    ramKb = ramByPkg[pkg] ?: 0L,
                    lastUsed = lastUsedByPkg[pkg] ?: 0L
                )
            } catch (_: Exception) {
                // Skip one bad entry, never fail the whole list.
            }
        }
        // Running first, then by RAM usage, then alphabetically.
        return out.sortedWith(
            compareByDescending<AppEntry> { it.isRunning }
                .thenByDescending { it.ramKb }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun defaultLauncherPackage(): String {
        return try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            // MATCH_DEFAULT_ONLY (65536), like the reference's C2674f.b.
            val ri = if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(
                    home,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }
            ri?.activityInfo?.packageName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun activeKeyboardPackage(): String {
        return try {
            val current = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return ""
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList
                .firstOrNull { it.id == current }
                ?.packageName ?: ""
        } catch (_:Exception) {
            ""
        }
    }
}
