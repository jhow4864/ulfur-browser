package com.jamhowman.beastbrowser.data

import java.util.concurrent.atomic.AtomicLong

object Stats {
    val total = AtomicLong()
    private const val BYTES_PER_BLOCK = 40 * 1024L   // rough average size of an ad/tracker request
    private const val MS_PER_BLOCK = 50L

    fun init() = total.set(Prefs.totalBlocked)
    fun save() { Prefs.totalBlocked = total.get() }
    fun reset() { total.set(0); save() }

    fun dataSaved(count: Long = total.get()): String {
        val mb = count * BYTES_PER_BLOCK / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> String.format("%.1f GB", mb / 1024)
            mb >= 10 -> String.format("%.0f MB", mb)
            else -> String.format("%.1f MB", mb)
        }
    }

    fun timeSaved(count: Long = total.get()): String {
        val s = count * MS_PER_BLOCK / 1000
        return when {
            s >= 3600 -> String.format("%.1fh", s / 3600.0)
            s >= 60 -> "${s / 60}m"
            else -> "${s}s"
        }
    }
}
