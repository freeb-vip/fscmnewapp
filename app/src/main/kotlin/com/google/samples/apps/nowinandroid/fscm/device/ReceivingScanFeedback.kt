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

package com.google.samples.apps.nowinandroid.fscm.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.samples.apps.nowinandroid.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ScanFeedbackPlaybackResult {
    Loading,
    Playing,
    Failed,
}

data class ScanAudioDiagnostics(
    val mediaVolume: Int = 0,
    val mediaVolumeMax: Int = 0,
    val notificationVolume: Int = 0,
    val notificationVolumeMax: Int = 0,
    val ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
    val soundsReady: Boolean = false,
    val lastPlayback: ScanFeedbackPlaybackResult = ScanFeedbackPlaybackResult.Loading,
)

internal fun scanFeedbackPlaybackResult(ready: Boolean, streamId: Int): ScanFeedbackPlaybackResult = when {
    !ready -> ScanFeedbackPlaybackResult.Loading
    streamId == 0 -> ScanFeedbackPlaybackResult.Failed
    else -> ScanFeedbackPlaybackResult.Playing
}

@Singleton
class ReceivingScanFeedback @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private val loadedSoundIds = mutableSetOf<Int>()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var successSoundId = 0
    private var failureSoundId = 0
    private var scanReceivedSoundId = 0
    private var lastPlayback = ScanFeedbackPlaybackResult.Loading
    private val _audioDiagnostics = MutableStateFlow(readDiagnostics())
    val audioDiagnostics: StateFlow<ScanAudioDiagnostics> = _audioDiagnostics.asStateFlow()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(lock) {
                if (status == 0) {
                    loadedSoundIds += sampleId
                } else {
                    Log.w(TAG, "Failed to load scan feedback sound: sampleId=$sampleId status=$status")
                    lastPlayback = ScanFeedbackPlaybackResult.Failed
                }
                publishDiagnostics()
            }
        }
        successSoundId = soundPool.load(context, R.raw.scan_feedback_success, 1)
        failureSoundId = soundPool.load(context, R.raw.scan_feedback_failure, 1)
        scanReceivedSoundId = soundPool.load(context, R.raw.scan_feedback_received, 1)
        refreshAudioDiagnostics()
    }

    fun success() {
        play(successSoundId)
        vibrate(longArrayOf(0, 70))
    }

    fun failure() {
        play(failureSoundId)
        vibrate(longArrayOf(0, 90, 45, 90, 45, 150))
    }

    fun scanReceived() {
        play(scanReceivedSoundId)
    }

    fun refreshAudioDiagnostics() {
        synchronized(lock) { publishDiagnostics() }
    }

    private fun play(soundId: Int) {
        synchronized(lock) {
            val ready = soundId != 0 && soundId in loadedSoundIds
            val streamId = if (ready) soundPool.play(soundId, 1f, 1f, 1, 0, 1f) else 0
            lastPlayback = scanFeedbackPlaybackResult(ready, streamId)
            when (lastPlayback) {
                ScanFeedbackPlaybackResult.Loading -> Log.w(TAG, "Scan feedback sound is not ready: soundId=$soundId")
                ScanFeedbackPlaybackResult.Failed -> Log.w(TAG, "Failed to play scan feedback sound: soundId=$soundId")
                ScanFeedbackPlaybackResult.Playing -> Unit
            }
            publishDiagnostics()
        }
    }

    private fun publishDiagnostics() {
        _audioDiagnostics.value = readDiagnostics()
    }

    private fun readDiagnostics(): ScanAudioDiagnostics = ScanAudioDiagnostics(
        mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
        mediaVolumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        notificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
        notificationVolumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
        ringerMode = audioManager.ringerMode,
        soundsReady = successSoundId in loadedSoundIds &&
            failureSoundId in loadedSoundIds &&
            scanReceivedSoundId in loadedSoundIds,
        lastPlayback = lastPlayback,
    )

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private companion object {
        const val TAG = "ScanFeedback"
    }
}
