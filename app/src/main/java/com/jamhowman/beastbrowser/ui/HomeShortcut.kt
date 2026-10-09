package com.jamhowman.beastbrowser.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * "Add to Home Screen": a pinned launcher shortcut that opens one URL in a normal Ulfur tab.
 * Deliberately not a PWA runtime. Never offered for private or Ghost tabs, so private pages
 * can't end up on the home screen.
 */
object HomeShortcut {

    /** Offered only for a real http(s) page in a non-private tab. */
    fun eligible(url: String?, isPrivate: Boolean, onPage: Boolean): Boolean {
        if (isPrivate || !onPage || url.isNullOrBlank()) return false
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    /** Shortcut label: page title, else the host without "www.", capped for launchers. */
    fun label(title: String?, url: String): String {
        val t = title?.trim().orEmpty()
        val base = t.ifEmpty { host(url).ifEmpty { url } }
        return if (base.length > 24) base.take(23).trimEnd() + "…" else base
    }

    /** Letter shown on the icon: first letter or digit of the host, else "U". */
    fun letter(url: String): String =
        host(url).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "U"

    /** Stable id per URL, so pinning the same page twice updates rather than duplicates. */
    fun id(url: String): String = "page-" + Integer.toHexString(url.hashCode())

    internal fun host(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.")

    fun supported(ctx: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

    /** Returns false if the launcher can't pin shortcuts. */
    fun request(ctx: Context, title: String?, url: String, bg: Int, fg: Int): Boolean {
        if (!supported(ctx)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = ShortcutInfoCompat.Builder(ctx, id(url))
            .setShortLabel(label(title, url))
            .setLongLabel(title?.takeIf { it.isNotBlank() } ?: url)
            .setIcon(IconCompat.createWithAdaptiveBitmap(letterIcon(letter(url), bg, fg)))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    /** 108dp-style adaptive bitmap: solid accent background, letter centred in the safe zone. */
    private fun letterIcon(letter: String, bg: Int, fg: Int): Bitmap {
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.36f
        }
        val y = size / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(letter, size / 2f, y, p)
        return bmp
    }
}
