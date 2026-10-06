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
 * Beast Control (2.3.5): live CPU / RAM / network figures for the app plus three "limit" sliders.
 *
 * The limits are **soft**: they only drive the "over soft limit" warnings in the sheet.
 * [applySoftHints] is intentionally a no-op — nothing is throttled (same as the 2.3.5 release).
 */
object BeastControl {
    private const val KEY_CPU = "beast_ctrl_cpu"
    private const val KEY_RAM = "beast_ctrl_ram"
    private const val KEY_NET = "beast_ctrl_net"

    /** Soft network budget at 100 %: 50 MiB per session. */
    private const val NET_BUDGET_BYTES = 50L * 1024 * 1024

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
    ) {
        val cpuOverSoft: Boolean get() = cpuPercent != null && cpuPercent > cpuCap
        val ramOverSoft: Boolean get() = deviceRamBytes > 0 && ramBytes * 100 > deviceRamBytes * ramCap
        val netOverSoft: Boolean get() = netBytes >= 0 && netBytes * 100 > netCap.toLong() * NET_BUDGET_BYTES
    }

    var cpuCap: Int
        get() = Prefs.sp.getInt(KEY_CPU, 100).coerceIn(25, 100)
        set(v) = Prefs.sp.edit { putInt(KEY_CPU, v.coerceIn(25, 100)) }
    var ramCap: Int
        get() = Prefs.sp.getInt(KEY_RAM, 100).coerceIn(25, 100)
        set(v) = Prefs.sp.edit { putInt(KEY_RAM, v.coerceIn(25, 100)) }
    var netCap: Int
        get() = Prefs.sp.getInt(KEY_NET, 100).coerceIn(25, 100)
        set(v) = Prefs.sp.edit { putInt(KEY_NET, v.coerceIn(25, 100)) }

    fun snapshot(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        var pssKb = Debug.getPss()
        if (pssKb <= 0) pssKb = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()
        return Snapshot(sampleCpuPercent(), pssKb * 1024, mem.totalMem, sessionNetBytes())
    }

    /** Placeholder for real throttling; the limits only produce warnings for now. */
    @Suppress("UNUSED_PARAMETER")
    fun applySoftHints(snapshot: Snapshot) {}

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
