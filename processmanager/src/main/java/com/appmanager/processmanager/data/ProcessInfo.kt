// SPDX-License-Identifier: GPL-3.0-or-later
package com.appmanager.processmanager.data

/**
 * Represents a running process on the device with the details the UI needs.
 */
data class ProcessInfo(
    val pid: Int,
    val uid: Int,
    val name: String,
    val packageName: String?,
    val label: String,              // User-facing name (app label or process base name)
    val memoryBytes: Long,          // USS/PSS or RSS in bytes (best effort)
    val cpuPercent: Float,          // 0..100 current CPU utilisation (approx.)
    val threadCount: Int,
    val startTimeMillis: Long,      // Start time as wall-clock millis
    val isSystem: Boolean,
    val isForeground: Boolean,
    val isService: Boolean,
    val iconPackage: String? = packageName,
) {
    val memoryMegabytes: Float get() = memoryBytes / (1024f * 1024f)

    /** Uptime in milliseconds. */
    val uptimeMillis: Long
        get() = System.currentTimeMillis() - startTimeMillis

    /** Categories to decide which processes can safely be killed. */
    fun isKillable(): Boolean = !isSystem
}
