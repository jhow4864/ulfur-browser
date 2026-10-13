package com.jamhowman.beastbrowser.filters

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jamhowman.beastbrowser.data.Prefs
import java.util.concurrent.TimeUnit

/**
 * 2.11: weekly automatic filter-list updates through WorkManager. Same path as "Update" in Settings
 * ([FilterStore.update] with [FilterFetcher.fetch]: https only, size caps, old rules kept on failure). The
 * Designer's 7-day nudge is unchanged, so it still shows when auto-update is off or keeps failing.
 */
object FilterAutoUpdate {
    const val WORK_NAME = "ulfur-filter-auto-update"
    const val KEY_ENABLED = "filters_auto"
    const val KEY_MOBILE = "filters_auto_mobile"
    const val KEY_LAST = "filters_auto_last"
    const val KEY_LAST_ERROR = "filters_auto_error"
    const val PERIOD_DAYS = 7L

    fun enabled(sp: SharedPreferences) = sp.getBoolean(KEY_ENABLED, true)
    fun allowMobile(sp: SharedPreferences) = sp.getBoolean(KEY_MOBILE, false)

    /** Unmetered (Wi-Fi) unless mobile data is allowed; battery not low, always. */
    fun constraints(allowMobile: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (allowMobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Device state the worker re-checks itself (WorkManager can start a job just as the state flips). */
    data class Conditions(val metered: Boolean, val powerSave: Boolean, val dataSaver: Boolean)

    sealed class Gate {
        object Run : Gate()
        data class Skip(val reason: String) : Gate()
    }

    fun gate(enabled: Boolean, allowMobile: Boolean, c: Conditions): Gate = when {
        !enabled -> Gate.Skip("off")
        c.powerSave -> Gate.Skip("battery saver")
        c.dataSaver -> Gate.Skip("data saver")
        c.metered && !allowMobile -> Gate.Skip("metered network, Wi-Fi only")
        else -> Gate.Run
    }

    fun conditions(ctx: Context): Conditions {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        return Conditions(
            metered = cm?.isActiveNetworkMetered ?: true,
            powerSave = pm?.isPowerSaveMode ?: false,
            dataSaver = cm?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
    }

    data class Outcome(val updated: Int, val failed: Int, val firstError: String?)

    /** Updates every enabled list once with [fetch]; failures keep the old rules (FilterStore.update). */
    fun runOnce(store: FilterStore, fetch: (String) -> String): Outcome {
        var ok = 0; var bad = 0; var err: String? = null
        store.lists.filter { it.enabled }.forEach { l ->
            val r = store.update(l.id, fetch)
            if (r != null && (r.error == null || r.updatedAt > l.updatedAt)) ok++
            else { bad++; if (err == null) err = r?.error }
        }
        return Outcome(ok, bad, err)
    }

    fun record(sp: SharedPreferences, now: Long, o: Outcome) {
        sp.edit().putLong(KEY_LAST, now).apply {
            if (o.failed == 0) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, o.firstError ?: "error")
        }.apply()
    }

    /** (Re)schedules or cancels the weekly job to match Settings. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        val sp = Prefs.sp
        if (!enabled(sp)) { wm.cancelUniqueWork(WORK_NAME); return }
        val req = PeriodicWorkRequestBuilder<Job>(PERIOD_DAYS, TimeUnit.DAYS)
            .setConstraints(constraints(allowMobile(sp)))
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    class Job(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            Prefs.init(applicationContext)
            val sp = Prefs.sp
            return when (gate(enabled(sp), allowMobile(sp), conditions(applicationContext))) {
                is Gate.Skip -> if (enabled(sp)) Result.retry() else Result.success()
                Gate.Run -> {
                    val o = Filters.updateBlocking(applicationContext) { FilterFetcher.fetch(it) }
                    record(sp, System.currentTimeMillis(), o)
                    Result.success()
                }
            }
        }
    }
}
