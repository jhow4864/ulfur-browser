package com.jamhowman.beastbrowser.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.PrivateLock
import com.jamhowman.beastbrowser.downloads.VaultDataSource
import java.io.File

/**
 * 2.11.1: in-app player for private videos (and audio). Media3 ExoPlayer reading through [VaultDataSource], which
 * decrypts only the chunks it needs, in memory; no cache, no temp files. Controls: play/pause, scrub bar, ±10 s,
 * fullscreen. FLAG_SECURE keeps frames out of screenshots and Recents. Needs the vault unlocked.
 */
@OptIn(UnstableApi::class)
class PrivateVideoActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private var fullscreen = false
    private var resumeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.setBackgroundColor(Color.BLACK)
        resumeAt = savedInstanceState?.getLong(STATE_POS) ?: 0L
        fullscreen = savedInstanceState?.getBoolean(STATE_FULL) ?: false
        view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowRewindButton(true); setShowFastForwardButton(true)
            setShowNextButton(false); setShowPreviousButton(false)
            setFullscreenButtonClickListener { full -> setFullscreen(full) }
            keepScreenOn = true
        }
        setContentView(view)
        setFullscreen(fullscreen)
    }

    override fun onStart() {
        super.onStart()
        if (!PrivateLock.isUnlocked()) { finish(); return }
        PrivateLock.unlock()
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val item = DownloadCenter.privateItems.value.firstOrNull { it.id == id }
        val f = item?.filePath?.let(::File)?.takeIf { it.isFile }
        if (f == null) { Toast.makeText(this, "File was deleted", Toast.LENGTH_SHORT).show(); finish(); return }
        title = item.fileName
        val p = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_MS).setSeekForwardIncrementMs(SEEK_MS)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(VaultDataSource.Factory(f)))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PrivateVideoActivity, "Can't play: the file is damaged or can't be decrypted", Toast.LENGTH_LONG).show()
                p.stop()
            }
        })
        // A neutral URI: the real name never leaves this screen. Type from the vault entry (2.11.1 fix-ups).
        p.setMediaItem(MediaItem.Builder().setUri(Uri.parse("ulfur-vault://$id")).setMimeType(item.mime).build(), resumeAt)
        p.prepare(); p.playWhenReady = true
        view.player = p
        player = p
    }

    override fun onStop() {
        super.onStop()
        player?.let { resumeAt = it.currentPosition; it.release() }
        player = null; view.player = null
        PrivateLock.softLock()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong(STATE_POS, player?.currentPosition ?: resumeAt)
        out.putBoolean(STATE_FULL, fullscreen)
    }

    private fun setFullscreen(full: Boolean) {
        fullscreen = full
        val c = WindowInsetsControllerCompat(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !full)
        if (full) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val SEEK_MS = 10_000L
        private const val STATE_POS = "pos"
        private const val STATE_FULL = "full"
        fun intent(ctx: Context, id: Long) = Intent(ctx, PrivateVideoActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
