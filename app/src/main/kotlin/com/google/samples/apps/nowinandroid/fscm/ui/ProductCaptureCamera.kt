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

import android.util.Rational
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import java.io.File

@Composable
internal fun ProductCaptureCamera(
    file: File,
    isVideo: Boolean,
    steps: List<String>,
    onReady: () -> Unit,
    onFailure: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val preview = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var saving by remember { mutableStateOf(false) }
    var recordedMillis by remember { mutableLongStateOf(0L) }
    var recordingStarted by remember { mutableStateOf(false) }
    DisposableEffect(owner, isVideo, file) {
        val provider = ProcessCameraProvider.getInstance(context)
        var alive = true
        var unbind: () -> Unit = {}
        provider.addListener({
            if (alive) {
                runCatching {
                    val manager = provider.get()
                    val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                    val feed = Preview.Builder().setTargetRotation(rotation).build().apply { surfaceProvider = preview.surfaceProvider }
                    if (isVideo) {
                        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD))).build()
                        val video = VideoCapture.withOutput(recorder).apply { targetRotation = rotation }
                        manager.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, feed, video)
                        videoCapture = video
                        unbind = { manager.unbind(feed, video) }
                    } else {
                        val photo = ImageCapture.Builder().setTargetRotation(rotation)
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                            .apply { setCropAspectRatio(Rational(1, 1)) }
                        manager.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, feed, photo)
                        imageCapture = photo
                        unbind = { manager.unbind(feed, photo) }
                    }
                }.onFailure { onFailure() }
            }
        }, executor)
        onDispose {
            alive = false
            recording?.close()
            unbind()
        }
    }
    val takeMedia: () -> Unit = {
        val active = recording
        if (active != null) {
            saving = true
            active.stop()
        } else if (isVideo) {
            file.delete()
            recordedMillis = 0L
            recordingStarted = false
            val output = FileOutputOptions.Builder(file).setDurationLimitMillis(29_900).setFileSizeLimit(300L * 1024 * 1024).build()
            recording = videoCapture?.output?.prepareRecording(context, output)?.start(executor) { event ->
                recordedMillis = (event.recordingStats.recordedDurationNanos / 1_000_000).coerceIn(0, 30_000)
                when (event) {
                    is VideoRecordEvent.Start -> recordingStarted = true
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        recordingStarted = false
                        saving = false
                        if (file.length() > 0 && event.error in setOf(VideoRecordEvent.Finalize.ERROR_NONE, VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)) onReady() else onFailure()
                    }
                }
            }
        } else {
            saving = true
            file.delete()
            imageCapture?.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) = onReady()
                    override fun onError(exception: ImageCaptureException) = onFailure()
                },
            )
        }
    }
    Dialog(
        onDismissRequest = { if (!saving && recording == null) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !isVideo),
    ) {
        if (isVideo) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { preview }, modifier = Modifier.matchParentSize())
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                    CaptureRecordingPanel(recordingStarted, recording != null, saving, recordedMillis, steps)
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = FscmInkStrong.copy(alpha = .84f),
                    ) {
                        Row(
                            Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (recording == null && !saving) {
                                OutlinedButton(
                                    onClick = onClose,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = .72f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                ) { Text(stringResource(R.string.capture_close)) }
                            }
                            Button(onClick = takeMedia, enabled = !saving && videoCapture != null, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                Text(stringResource(if (recording != null) R.string.capture_stop else R.string.capture_record))
                            }
                        }
                    }
                }
            }
        } else {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val frameSize = minOf(maxWidth, maxHeight)
                        AndroidView(factory = { preview }, modifier = Modifier.width(frameSize).aspectRatio(1f))
                        Surface(
                            modifier = Modifier.width(frameSize * .7f).aspectRatio(1f),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        ) {}
                    }
                    Button(onClick = takeMedia, enabled = !saving && imageCapture != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.capture_photo))
                    }
                    OutlinedButton(onClick = onClose, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.capture_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureRecordingPanel(started: Boolean, pending: Boolean, saving: Boolean, millis: Long, steps: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = FscmInkStrong.copy(alpha = .84f),
        contentColor = Color.White,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (started && !saving) Box(Modifier.size(9.dp).background(FscmDanger, CircleShape))
                val label = when {
                    saving -> R.string.capture_video_saving
                    started -> R.string.capture_video_recording
                    pending -> R.string.capture_video_starting
                    else -> R.string.capture_video_ready
                }
                Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.capture_video_time, captureTimestamp(millis), captureTimestamp(30_000)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (started || saving) {
                LinearProgressIndicator(
                    progress = { millis.toFloat() / 30_000f },
                    modifier = Modifier.fillMaxWidth(),
                    color = FscmDanger,
                    trackColor = Color.White.copy(alpha = .24f),
                )
            }
            if (!saving && steps.isNotEmpty()) {
                val index = (millis / 3_000).toInt().coerceAtMost(steps.lastIndex)
                Text(steps[index], style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
