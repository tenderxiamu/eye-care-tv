package com.tenderxiamu.eyecaretv

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * 极简视频播放器：SurfaceView + MediaPlayer。
 * 不依赖 Media3，安装包更小，也能直接播放内置 raw 视频。
 */
class VideoPlayerView(context: Context) : FrameLayout(context) {

    sealed interface Source {
        data class Raw(val resId: Int) : Source
        data class File(val path: String) : Source
    }

    private val surfaceView = SurfaceView(context)
    private var player: MediaPlayer? = null
    private var pending: Pair<Source, Int>? = null
    private var attached = false
    private var started = false

    var onEnded: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    init {
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attached = true
                startPlayback()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                attached = false
                releasePlayer()
            }
        })
    }

    fun play(source: Source, volumePercent: Int) {
        pending = source to volumePercent
        if (attached) startPlayback()
    }

    fun isStarted(): Boolean = started

    fun positionMs(): Int = runCatching { player?.currentPosition ?: -1 }.getOrDefault(-1)

    fun durationMs(): Int = runCatching { player?.duration ?: -1 }.getOrDefault(-1)

    fun pausePlayback() {
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
    }

    fun resumePlayback() {
        runCatching { player?.let { if (!it.isPlaying) it.start() } }
    }

    fun release() {
        pending = null
        releasePlayer()
    }

    private fun startPlayback() {
        releasePlayer()
        val (source, volumePercent) = pending ?: return
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            when (source) {
                is Source.Raw -> mediaPlayer.setDataSource(
                    context,
                    Uri.parse("android.resource://${context.packageName}/${source.resId}"),
                )
                is Source.File -> mediaPlayer.setDataSource(source.path)
            }
            val volume = volumePercent.coerceIn(0, 100) / 100f
            mediaPlayer.setVolume(volume, volume)
            mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            mediaPlayer.setDisplay(surfaceView.holder)
            mediaPlayer.setOnPreparedListener {
                started = true
                it.start()
            }
            mediaPlayer.setOnCompletionListener { onEnded?.invoke() }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                onError?.invoke()
                true
            }
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (e: Exception) {
            mediaPlayer.release()
            onError?.invoke()
        }
    }

    private fun releasePlayer() {
        started = false
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}
