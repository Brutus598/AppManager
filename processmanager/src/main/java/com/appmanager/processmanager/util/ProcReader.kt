// SPDX-License-Identifier: GPL-3.0-or-later
package com.appmanager.processmanager.util

import java.io.File

/**
 * Tiny helper that reads entries from /proc. Works on every Android version
 * but only returns data the calling uid is allowed to see (usually other apps
 * require root/ADB; on non-root devices we still get a useful picture via
 * ActivityManager and augment it with /proc data for our own & visible processes).
 */
object ProcReader {

    fun listPids(): List<Int> = runCatching {
        File("/proc").listFiles()
            ?.mapNotNull { it.name.toIntOrNull() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    data class ProcStat(
        val pid: Int,
        val comm: String,
        val state: Char,
        val ppid: Int,
        val utime: Long,
        val stime: Long,
        val threads: Int,
        val starttime: Long,     // in clock ticks since boot
    )

    /**
     * Parses `/proc/[pid]/stat`. The format is: pid (comm) state ppid ...
     * Because `comm` may contain spaces and parentheses we search from the right.
     */
    fun readStat(pid: Int): ProcStat? = runCatching {
        val raw = File("/proc/$pid/stat").readText().trim()
        val openBracket = raw.indexOf('(')
        val closeBracket = raw.lastIndexOf(')')
        if (openBracket < 0 || closeBracket < 0) return null
        val comm = raw.substring(openBracket + 1, closeBracket)
        val rest = raw.substring(closeBracket + 2).split(' ')
        if (rest.size < 50) return null
        val state = rest[0].getOrElse(0) { '?' }
        val ppid = rest[1].toIntOrNull() ?: -1
        // utime=rest[11], stime=rest[12], threads=rest[17], starttime=rest[19]
        // (after state we have: ppid(1), pgrp(2), session(3), tty_nr(4), tpgid(5),
        // flags(6), minflt(7), cminflt(8), majflt(9), cmajflt(10), utime(11)...)
        val utime = rest[11].toLongOrNull() ?: 0L
        val stime = rest[12].toLongOrNull() ?: 0L
        val threads = rest[17].toIntOrNull() ?: 1
        val starttime = rest[19].toLongOrNull() ?: 0L
        ProcStat(pid, comm, state, ppid, utime, stime, threads, starttime)
    }.getOrNull()

    /** Reads VmRSS from `/proc/[pid]/status` in bytes. */
    fun readRssBytes(pid: Int): Long = runCatching {
        val file = File("/proc/$pid/status")
        file.useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024) ?: 0L
        }
    }.getOrDefault(0L)

    /** Returns the Uid (real) for the process from `/proc/[pid]/status`. */
    fun readUid(pid: Int): Int = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toIntOrNull() ?: -1
        }
    }.getOrDefault(-1)
}
