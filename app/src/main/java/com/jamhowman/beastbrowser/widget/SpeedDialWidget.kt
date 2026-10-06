package com.jamhowman.beastbrowser.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.jamhowman.beastbrowser.R
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.ui.MainActivity
import com.jamhowman.beastbrowser.ui.SpeedDialStore
import com.jamhowman.beastbrowser.ui.Tile
import java.util.Locale

/**
 * 2×2 home-screen widget (2.3.5): the first four Speed Dial tiles as accent-coloured letters.
 * Tapping a tile opens its URL in Beast; tapping elsewhere just opens Beast.
 */
class SpeedDialWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        Prefs.init(context)
        val tiles = SpeedDialStore.load()
        appWidgetIds.forEach { manager.updateAppWidget(it, build(context, tiles)) }
    }

    companion object {
        private val TILE_IDS = intArrayOf(R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3)
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Call after Speed Dial tiles are added or removed. */
        fun refreshAll(context: Context) {
            Prefs.init(context)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SpeedDialWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context, SpeedDialStore.load())
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun build(context: Context, tiles: List<Tile>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_speed_dial)
            val accent = Prefs.accent.color
            val newTask = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(newTask), FLAGS,
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openApp)
            TILE_IDS.forEachIndexed { i, viewId ->
                val tile = tiles.getOrNull(i)
                if (tile == null) {
                    views.setTextViewText(viewId, "·")
                    views.setTextColor(viewId, context.getColor(R.color.text_hint))
                    views.setOnClickPendingIntent(viewId, openApp)
                } else {
                    views.setTextViewText(viewId, tile.title.firstOrNull()?.toString()?.uppercase(Locale.ROOT) ?: "?")
                    views.setTextColor(viewId, accent)
                    val open = Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(runCatching { Uri.parse(tile.url) }.getOrNull())
                        .addFlags(newTask)
                    views.setOnClickPendingIntent(viewId, PendingIntent.getActivity(context, i + 1, open, FLAGS))
                }
            }
            return views
        }
    }
}
