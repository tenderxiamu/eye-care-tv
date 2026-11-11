package com.tenderxiamu.eyecaretv

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
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
    private var videoWidth = 0
    private var videoHeight = 0
    private var resumePositionMs = 0
    private var audioOnly = false

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
                if (player == null) startPlayback()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                attached = false
                // 纯音频模式不依赖画面，surface 销毁后继续播放声音
                if (!audioOnly) releasePlayer()
            }
        })
    }

    /**
     * [resumePositionMs] 用于布局重建后续播，避免从头播放；
     * [audioOnly] 为 true 时不绑定画面，只播放声音。
     */
    fun play(
        source: Source,
        volumePercent: Int,
        resumePositionMs: Int = 0,
        audioOnly: Boolean = false,
    ) {
        this.audioOnly = audioOnly
        this.resumePositionMs = resumePositionMs.coerceAtLeast(0)
        pending = source to volumePercent
        if (attached || audioOnly) startPlayback()
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
            if (!audioOnly) mediaPlayer.setDisplay(surfaceView.holder)
            mediaPlayer.setOnPreparedListener {
                started = true
                if (resumePositionMs > 0) it.seekTo(resumePositionMs)
                it.start()
            }
            mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                fitSurface()
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

    /**
     * 按视频真实宽高比居中缩放 SurfaceView。
     * 不依赖 SCALE_TO_FIT 的拉伸行为，横竖屏都不会变形。
     */
    private fun fitSurface() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val containerW = width
        val containerH = height
        if (containerW <= 0 || containerH <= 0) return
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val containerRatio = containerW.toFloat() / containerH.toFloat()
        val lp = surfaceView.layoutParams as LayoutParams
        if (videoRatio > containerRatio) {
            lp.width = containerW
            lp.height = (containerW / videoRatio).toInt()
        } else {
            lp.height = containerH
            lp.width = (containerH * videoRatio).toInt()
        }
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitSurface()
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
