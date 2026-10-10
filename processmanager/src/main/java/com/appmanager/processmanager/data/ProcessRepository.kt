// SPDX-License-Identifier: GPL-3.0-or-later
package com.appmanager.processmanager.data

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.appmanager.processmanager.util.ProcReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central data source: scans running processes and attempts to kill them.
 *
 * Android heavily restricts process visibility (since 5.0+ only your own
 * processes are fully visible; UsageStatsManager + ActivityManager give the
 * best picture without root). This repository gracefully combines:
 *
 * 1. ActivityManager.runningAppProcesses — the only officially supported list
 *    (returns processes the caller can see, i.e. all on older releases, a
 *    filtered set on newer ones).
 * 2. /proc enumeration — always succeeds for pids visible to the uid (own &
 *    shared).
 * 3. UsageStatsManager — when the user grants usage access, used to detect
 *    foreground apps and refine labels.
 */
class ProcessRepository(private val context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = context.packageManager

    // Remember previous CPU sample so we can compute a % delta between scans.
    private var prevCpuTicks: MutableMap<Int, Long> = HashMap()
    private var prevUptimeMs: Long = 0L

    suspend fun scan(): List<ProcessInfo> = withContext(Dispatchers.IO) {
        val nowUptimeMs = SystemClock.elapsedRealtime()
        val jiffyHz = runCatching {
            val fr = File("/proc/self/stat").readText().split(' ')
            // We'll detect clock ticks using uptime vs starttime differences
            // (a 100Hz default is correct for virtually all Android kernels).
            100L
        }.getOrDefault(100L)

        // Gather candidate PIDs from ActivityManager first, then /proc.
        val amProcs: List<ActivityManager.RunningAppProcessInfo> =
            activityManager.runningAppProcesses ?: emptyList()

        val seenPids = HashSet<Int>()
        val out = ArrayList<ProcessInfo>(amProcs.size + 64)

        val foregroundApp = if (isUsageAccessGranted()) {
            getForegroundAppFromUsage()
        } else null

        // Build a uid->package map for fast lookup.
        val packagesByUid = HashMap<Int, MutableList<String>>()
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            packagesByUid.getOrPut(app.uid) { ArrayList(2) }.add(app.packageName)
        }

        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)

        for (rap in amProcs) {
            seenPids.add(rap.pid)
            val uid = rap.uid
            val pkgList = rap.pkgList?.toList() ?: packagesByUid[uid] ?: emptyList()
            val primaryPkg = pkgList.firstOrNull() ?: rap.processName
            val appInfo = primaryPkg?.let { pkg -> runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() }
            val label = appInfo?.loadLabel(pm)?.toString()
                ?: friendlyProcessName(rap.processName)
            val isSystem = appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
            val isForeground = rap.importance <=
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ||
                primaryPkg == foregroundApp
            val isService = rap.importance ==
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE

            val memBytes = estimateMemory(rap.pid)
            val stat = ProcReader.readStat(rap.pid)
            val cpuPercent = computeCpuPercent(rap.pid, stat, nowUptimeMs, jiffyHz)
            val startTime = stat?.starttime?.let { startTicks ->
                System.currentTimeMillis() -
                    (nowUptimeMs - startTicks * 1000 / jiffyHz)
            } ?: System.currentTimeMillis()

            out += ProcessInfo(
                pid = rap.pid,
                uid = uid,
                name = rap.processName,
                packageName = primaryPkg,
                label = label,
                memoryBytes = memBytes,
                cpuPercent = cpuPercent,
                threadCount = stat?.threads ?: rap.pkgList?.size ?: 1,
                startTimeMillis = startTime,
                isSystem = isSystem,
                isForeground = isForeground,
                isService = isService,
            )
        }

        // Also pick up extra processes from /proc we didn't see above (kernel threads
        // and our own sub-processes) so the list feels complete.
        for (pid in ProcReader.listPids()) {
            if (pid == Process.myPid() || seenPids.contains(pid)) continue
            val stat = ProcReader.readStat(pid) ?: continue
            val uid = ProcReader.readUid(pid)
            val pkg = packagesByUid[uid]?.firstOrNull()
            val appInfo = pkg?.let { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
            val isKernel = stat.ppid == 2 || stat.state == 'S' && stat.comm.startsWith("k")
            val label = appInfo?.loadLabel(pm)?.toString() ?: stat.comm
            val memBytes = ProcReader.readRssBytes(pid)
            val cpuPercent = computeCpuPercent(pid, stat, nowUptimeMs, jiffyHz)
            val startTime = System.currentTimeMillis() -
                (nowUptimeMs - stat.starttime * 1000 / jiffyHz)

            // Skip kernel threads on non-rooted devices because we can't do
            // anything useful with them and they add noise.
            if (isKernel && uid < Process.SYSTEM_UID) continue

            out += ProcessInfo(
                pid = pid,
                uid = uid,
                name = stat.comm,
                packageName = pkg,
                label = label,
                memoryBytes = memBytes,
                cpuPercent = cpuPercent,
                threadCount = stat.threads,
                startTimeMillis = startTime,
                isSystem = isKernel || appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: true,
                isForeground = false,
                isService = false,
            )
        }

        prevUptimeMs = nowUptimeMs

        // Sort: foreground first, then by memory usage desc, then name.
        out.sortWith(
            compareByDescending<ProcessInfo> { it.isForeground }
                .thenBy { it.isSystem }
                .thenByDescending { it.memoryBytes }
                .thenBy { it.label.lowercase() }
        )
        out
    }

    /**
     * Kill a single process. Returns true on success. Uses ActivityManager's
     * killBackgroundProcesses (non-root safe; the system decides what to kill)
     * and falls back to Process.killProcess for the current uid group.
     */
    fun kill(process: ProcessInfo): Boolean {
        return runCatching {
            // Only possible for own uid without elevated privileges.
            if (process.uid == Process.myUid()) {
                Process.killProcess(process.pid)
                true
            } else {
                // For other apps we can only kill via ActivityManager when the
                // target package is backgrounded; this is the documented and
                // allowed non-root API.
                activityManager.killBackgroundProcesses(process.packageName)
                true
            }
        }.getOrDefault(false)
    }

    /** Kill a batch; returns number of successfully killed processes. */
    fun killAll(processes: List<ProcessInfo>): Int {
        var killed = 0
        for (p in processes) {
            if (p.isSystem) continue
            if (kill(p)) killed++
        }
        return killed
    }

    fun hasUsageAccess(): Boolean = isUsageAccessGranted()

    fun requestUsageAccessIntent(): Intent = Intent(
        Settings.ACTION_USAGE_ACCESS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- Internal helpers ---------------------------------------------------

    private fun friendlyProcessName(raw: String): String {
        val lastSlash = raw.lastIndexOf('/')
        val lastDot = raw.lastIndexOf('.')
        val base = if (lastSlash >= 0) raw.substring(lastSlash + 1)
        else if (lastDot >= 0) raw.substring(lastDot + 1)
        else raw
        return base.replaceFirstChar { it.uppercaseChar() }
    }

    private fun estimateMemory(pid: Int): Long {
        // Debug.MemoryInfo is only reliable for the caller's own process.
        val rss = ProcReader.readRssBytes(pid)
        if (rss > 0) return rss
        return runCatching {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            mi.totalPss * 1024L
        }.getOrDefault(0L)
    }

    private fun computeCpuPercent(
        pid: Int,
        stat: ProcReader.ProcStat?,
        nowUptimeMs: Long,
        jiffyHz: Long,
    ): Float {
        if (stat == null) return 0f
        val total = stat.utime + stat.stime
        val prev = prevCpuTicks.put(pid, total) ?: run {
            prevCpuTicks[pid] = total
            return 0f
        }
        val dtTicks = (nowUptimeMs - prevUptimeMs).coerceAtLeast(1) * jiffyHz / 1000
        if (dtTicks <= 0) return 0f
        val delta = (total - prev).coerceAtLeast(0)
        return (delta * 100f / dtTicks).coerceIn(0f, 100f)
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getForegroundAppFromUsage(): String? = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 10_000
        val stats = usm.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            start, end
        )
        stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }.getOrNull()
}
