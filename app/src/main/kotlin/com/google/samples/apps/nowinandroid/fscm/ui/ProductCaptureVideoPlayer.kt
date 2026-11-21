/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.fscm.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.samples.apps.nowinandroid.R
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private const val BUNDLED_ASSET = "file:///android_asset/"

internal fun captureTimestamp(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

private enum class Playback { Loading, Playing, Paused, Ended, Failed }

@Composable
internal fun ProductCaptureVideoPlayer(source: Uri, modifier: Modifier = Modifier, expand: Boolean = false) {
    var attempt by remember(source) { mutableIntStateOf(0) }
    key(source, attempt) {
        var view by remember { mutableStateOf<CaptureVideoTexture?>(null) }
        var playback by remember { mutableStateOf(Playback.Loading) }
        var duration by remember { mutableIntStateOf(0) }
        var position by remember { mutableIntStateOf(0) }
        var aspect by remember { mutableStateOf(9f / 16f) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(view, lifecycle) {
            val current = view ?: return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        if (playback == Playback.Playing) position = current.position()
                        delay(200)
                    }
                } finally {
                    current.pause()
                }
            }
        }
        Column(modifier.fillMaxWidth()) {
            val frame = if (expand) Modifier.weight(1f) else Modifier.height(240.dp)
            Surface(color = Color.Black, modifier = frame.fillMaxWidth()) {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    val frameWidth = minOf(maxWidth, maxHeight * aspect)
                    AndroidView(
                        factory = { context ->
                            CaptureVideoTexture(context, source, { playback = it }, { duration = it }, { aspect = it })
                                .also { view = it }
                        },
                        onRelease = { it.close() },
                        modifier = Modifier.width(frameWidth).aspectRatio(aspect),
                    )
                    if (playback == Playback.Loading) CircularProgressIndicator(color = Color.White)
                    if (playback == Playback.Failed) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.capture_video_error), color = Color.White)
                        }
                    }
                }
            }
            if (playback == Playback.Failed) {
                OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.capture_retry)) }
            } else {
                val displayedPosition = if (playback == Playback.Ended) duration else position
                LinearProgressIndicator(
                    progress = { if (duration > 0) (displayedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = playback != Playback.Loading,
                        onClick = { if (playback == Playback.Playing) view?.pause() else view?.play() },
                    ) {
                        Text(stringResource(if (playback == Playback.Playing) R.string.capture_pause_video else R.string.capture_watch))
                    }
                    Text(
                        stringResource(R.string.capture_video_time, captureTimestamp(displayedPosition.toLong()), captureTimestamp(duration.toLong())),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private class CaptureVideoTexture(
    context: Context,
    private val source: Uri,
    private val onState: (Playback) -> Unit,
    private val onDuration: (Int) -> Unit,
    private val onAspect: (Float) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var prepared = false

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        close()
        onState(Playback.Loading)
        val surface = Surface(texture).also { output = it }
        val media = MediaPlayer().also { player = it }
        try {
            media.setSurface(surface)
            media.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) onAspect(w.toFloat() / h)
            }
            media.setOnPreparedListener {
                prepared = true
                onDuration(it.duration.coerceAtLeast(0))
                play()
            }
            media.setOnCompletionListener { onState(Playback.Ended) }
            media.setOnErrorListener { _, _, _ ->
                fail()
                true
            }
            when {
                source.toString().startsWith(BUNDLED_ASSET) ->
                    context.assets.openFd(source.toString().removePrefix(BUNDLED_ASSET)).use {
                        media.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                source.scheme == "file" -> File(requireNotNull(source.path)).inputStream().use { media.setDataSource(it.fd) }
                else -> media.setDataSource(context, source)
            }
            media.prepareAsync()
        } catch (failure: Exception) {
            android.util.Log.w("CaptureVideo", "Video preparation failed", failure)
            fail()
        }
    }

    private fun fail() {
        close()
        onState(Playback.Failed)
    }

    fun play() {
        if (!prepared) return
        player?.start()
        onState(Playback.Playing)
    }

    fun pause() {
        if (!prepared || player?.isPlaying != true) return
        player?.pause()
        onState(Playback.Paused)
    }

    fun position(): Int = if (prepared) player?.currentPosition ?: 0 else 0

    fun close() {
        prepared = false
        player?.release()
        player = null
        output?.release()
        output = null
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        close()
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
