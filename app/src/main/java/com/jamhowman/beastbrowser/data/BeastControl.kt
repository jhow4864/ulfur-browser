package com.jamhowman.beastbrowser.data

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import androidx.core.content.edit
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Ulfur Monitor (was "Beast Control"): live CPU / RAM / network figures for the app.
 *
 * 2.8: the three "limit" sliders were removed. They never throttled anything (they only coloured
 * the figures), and a control that does nothing is worse than none. [clearLegacyLimits] drops
 * their saved values.
 */
object BeastControl {
    private const val KEY_CPU = "beast_ctrl_cpu"
    private const val KEY_RAM = "beast_ctrl_ram"
    private const val KEY_NET = "beast_ctrl_net"

    private var lastCpuJiffies = -1L
    private var lastCpuWallNs = -1L
    private var sessionNetBase = -1L

    data class Snapshot(
        /** App CPU use in percent of one core (null until two samples exist). */
        val cpuPercent: Int?,
        /** App PSS in bytes. */
        val ramBytes: Long,
        val deviceRamBytes: Long,
        /** Bytes sent + received by the app since the session started (-1 = unsupported). */
        val netBytes: Long,
    )

    /** Remove the old slider prefs (2.3.5-2.7.x). Safe to call every launch. */
    fun clearLegacyLimits() {
        if (Prefs.sp.contains(KEY_CPU) || Prefs.sp.contains(KEY_RAM) || Prefs.sp.contains(KEY_NET))
            Prefs.sp.edit { remove(KEY_CPU); remove(KEY_RAM); remove(KEY_NET) }
    }

    fun snapshot(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        var pssKb = Debug.getPss()
        if (pssKb <= 0) pssKb = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()
        return Snapshot(sampleCpuPercent(), pssKb * 1024, mem.totalMem, sessionNetBytes())
    }

    /** utime + stime of this process, in clock ticks. */
    private fun readSelfCpuJiffies(): Long? = try {
        RandomAccessFile("/proc/self/stat", "r").use { f ->
            // Fields after "(comm) ": index 11 = utime, 12 = stime.
            val fields = f.readLine().substringAfterLast(") ").split(' ')
            fields[11].toLong() + fields[12].toLong()
        }
    } catch (_: Throwable) { null }

    private fun sampleCpuPercent(): Int? {
        val jiffies = readSelfCpuJiffies() ?: return null
        val now = System.nanoTime()
        val prevJiffies = lastCpuJiffies
        val prevNs = lastCpuWallNs
        lastCpuJiffies = jiffies
        lastCpuWallNs = now
        if (prevJiffies < 0 || prevNs < 0 || now <= prevNs) return null
        val cpuSec = (jiffies - prevJiffies) / 100.0          // USER_HZ = 100 on Android
        val wallSec = (now - prevNs) / 1e9
        if (wallSec <= 0) return null
        val pct = ((cpuSec / wallSec) * 100).toInt().coerceIn(0, Runtime.getRuntime().availableProcessors() * 100)
        return pct.coerceAtMost(999)
    }

    private fun sessionNetBytes(): Long {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return -1
        val total = rx + tx
        if (sessionNetBase < 0) sessionNetBase = total
        return (total - sessionNetBase).coerceAtLeast(0)
    }

    fun formatCpu(pct: Int?): String = if (pct == null) "—" else "$pct%"

    fun formatRam(bytes: Long): String {
        val mb = bytes / 1048576.0
        return when {
            mb >= 1024 -> String.format(Locale.ROOT, "%.1fG", mb / 1024)
            mb >= 10 -> String.format(Locale.ROOT, "%.0fM", mb)
            else -> String.format(Locale.ROOT, "%.1fM", mb)
        }
    }

    fun formatNet(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1024 -> String.format(Locale.ROOT, "%.1fG", mb / 1024)
            mb >= 10 -> String.format(Locale.ROOT, "%.0fM", mb)
            mb >= 1 -> String.format(Locale.ROOT, "%.1fM", mb)
            kb >= 1 -> String.format(Locale.ROOT, "%.0fK", kb)
            else -> "${bytes}B"
        }
    }
}
