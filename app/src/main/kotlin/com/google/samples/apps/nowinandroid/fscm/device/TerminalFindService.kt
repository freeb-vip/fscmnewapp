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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.samples.apps.nowinandroid.MainActivity
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.EdgeConnectionPolicy
import com.google.samples.apps.nowinandroid.fscm.data.EdgeNode
import com.google.samples.apps.nowinandroid.fscm.data.EdgeTerminalConnectionTarget
import com.google.samples.apps.nowinandroid.fscm.data.EdgeTerminalRepository
import com.google.samples.apps.nowinandroid.fscm.data.EdgeTicketProvider
import com.google.samples.apps.nowinandroid.fscm.data.FscmJson
import com.google.samples.apps.nowinandroid.fscm.data.SessionStore
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

private const val TERMINAL_PROTOCOL_VERSION = 1

enum class TerminalFindConnectionStatus {
    Stopped,
    Waiting,
    Connecting,
    Online,
    Reconnecting,
    Ringing,
}

data class TerminalFindState(
    val status: TerminalFindConnectionStatus = TerminalFindConnectionStatus.Stopped,
    val nodeName: String = "",
    val lastError: String = "",
)

@Singleton
class TerminalFindStatusStore @Inject constructor() {
    private val _state = MutableStateFlow(TerminalFindState())
    val state: StateFlow<TerminalFindState> = _state.asStateFlow()

    fun update(value: TerminalFindState) {
        _state.value = value
    }
}

@Serializable
internal data class TerminalRegisterMessage(
    val type: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("terminal_name") val terminalName: String,
    @SerialName("app_version") val appVersion: String,
    val platform: String,
    @SerialName("lan_ip") val lanIp: String,
    val capabilities: List<String>,
)

@Serializable
private data class TerminalIncomingMessage(
    val type: String = "",
    @SerialName("command_id") val commandId: String = "",
    @SerialName("target_command_id") val targetCommandId: String = "",
    val action: String = "",
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val message: String = "",
)

@Serializable
private data class TerminalOutgoingMessage(
    val type: String,
    @SerialName("command_id") val commandId: String = "",
    val status: String = "",
    val message: String = "",
)

internal fun terminalReconnectDelayMillis(attempt: Int): Long {
    val seconds = listOf(5, 30)[min(attempt.coerceAtLeast(0), 1)]
    return seconds * 1_000L
}

internal suspend inline fun withTerminalConnectionAttempts(attempt: (Int) -> Unit) {
    repeat(3) { index ->
        if (index > 0) delay(terminalReconnectDelayMillis(index - 1))
        attempt(index)
    }
}

internal const val TERMINAL_IDLE_RETRY_INTERVAL_MS = 60 * 60 * 1_000L

internal suspend fun retryIdleTerminalHourly(isIdle: suspend () -> Boolean, retry: suspend () -> Unit) {
    while (true) {
        delay(TERMINAL_IDLE_RETRY_INTERVAL_MS)
        if (isIdle()) retry()
    }
}

@AndroidEntryPoint
class TerminalFindService : Service() {
    @Inject
    lateinit var sessionStore: SessionStore

    @Inject
    lateinit var connectionPolicy: EdgeConnectionPolicy

    @Inject
    lateinit var lazyTerminalRepository: Lazy<EdgeTerminalRepository>

    private val terminalRepository: EdgeTerminalRepository
        get() = lazyTerminalRepository.get()

    @Inject
    lateinit var ticketProvider: EdgeTicketProvider

    @Inject
    lateinit var alarm: FindDeviceAlarm

    @Inject
    lateinit var statusStore: TerminalFindStatusStore

    @Inject
    @FscmJson
    lateinit var json: Json

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }
    private var sessionJob: Job? = null
    private var connectionJob: Job? = null
    private var centerPresenceJob: Job? = null
    private var expiryJob: Job? = null
    private var webSocket: WebSocket? = null
    private var connectedTarget: EdgeTerminalConnectionTarget? = null
    private var activeCommandId = ""
    private val processedCommands = LinkedHashSet<String>()
    private var networkJob: Job? = null
    private var hourlyRetryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        showNotification(TerminalFindConnectionStatus.Connecting)
    }

    private fun observeSession() {
        if (sessionJob != null) return
        hourlyRetryJob = serviceScope.launch {
            retryIdleTerminalHourly(
                isIdle = {
                    val loggedIn = sessionStore.current().isLoggedIn
                    // Recover a stopped center heartbeat without interrupting a healthy edge socket.
                    if (loggedIn) startCenterPresence()
                    loggedIn && connectionJob?.isActive != true
                },
                retry = {
                    try {
                        // Refresh the registry and invalidate old health results. The policy change
                        // wakes the existing connection observer; this never starts LAN discovery.
                        connectionPolicy.registeredNodes(force = true)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Log.w(LOG_TAG, "Hourly edge connection check failed", error)
                    }
                },
            )
        }
        networkJob = serviceScope.launch {
            connectionPolicy.changes.collect {
                centerPresenceJob?.cancel()
                startCenterPresence()
                restartConnection()
            }
        }
        sessionJob = serviceScope.launch {
            sessionStore.session
                .map { session -> listOf(session.isLoggedIn, session.currentNamespace?.id, session.sessionMode, session.user?.id) }
                .distinctUntilChanged()
                .collect { scope ->
                    if (scope.first() == true) {
                        centerPresenceJob?.cancel()
                        startCenterPresence()
                        restartConnection()
                    } else {
                        stopForLogout()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every foreground start must be acknowledged before session callbacks can stop us.
        // onCreate alone is insufficient when starts are queued for an existing service.
        showNotification(statusStore.state.value.status)
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForLogout()
            return START_NOT_STICKY
        }
        observeSession()
        when (intent?.action) {
            ACTION_STOP_RINGING -> stopRingingLocally("stopped")
            ACTION_TEST_RINGING -> startLocalTest()
            else -> restartConnectionIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkJob?.cancel()
        hourlyRetryJob?.cancel()
        expiryJob?.cancel()
        centerPresenceJob?.cancel()
        alarm.stop()
        webSocket?.close(1000, "service stopped")
        connectionJob?.cancel()
        statusStore.update(TerminalFindState())
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restartConnectionIfNeeded() {
        if (connectionJob?.isActive != true) {
            startCenterPresence()
            restartConnection()
        }
    }

    private fun restartConnection() {
        connectionJob?.cancel()
        webSocket?.cancel()
        webSocket = null
        connectionJob = serviceScope.launch { connectionLoop() }
    }

    private fun startCenterPresence() {
        if (centerPresenceJob?.isActive == true) return
        centerPresenceJob = serviceScope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    if (!terminalRepository.maintainCenterPresence()) return@launch
                    attempt = 0
                    delay(CENTER_HEARTBEAT_INTERVAL_MS)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w(LOG_TAG, "Central terminal presence update failed", error)
                    updateStatus(
                        statusStore.state.value.status,
                        error = "中心终端心跳失败：${error.message.orEmpty()}",
                    )
                    if (attempt >= 2) return@launch
                    delay(terminalReconnectDelayMillis(attempt++))
                }
            }
        }
    }

    private suspend fun connectionLoop() {
        val target = try {
            terminalRepository.connectionTarget()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            updateStatus(TerminalFindConnectionStatus.Waiting, error = error.message.orEmpty())
            return
        }
        if (target == null) {
            updateStatus(TerminalFindConnectionStatus.Waiting)
            return
        }
        val scope = connectionPolicy.scopeKey()
        connectedTarget = target
        withTerminalConnectionAttempts { attempt ->
            if (!sessionStore.current().isLoggedIn || scope != connectionPolicy.scopeKey()) return
            if (scope != connectionPolicy.scopeKey() || !connectionPolicy.permits(EdgeNode(nodeId = target.nodeId, lanBaseUrl = target.baseUrl))) {
                updateStatus(TerminalFindConnectionStatus.Waiting)
                return
            }
            updateStatus(if (attempt == 0) TerminalFindConnectionStatus.Connecting else TerminalFindConnectionStatus.Reconnecting, target.nodeName)
            try {
                terminalRepository.register(target)
                val closed = CompletableDeferred<Unit>()
                val request = Request.Builder()
                    .url(target.webSocketUrl())
                    .header("X-Edge-Ticket", ticketProvider.ticket(target.nodeId) ?: target.ticket)
                    .build()
                val socket = client.newWebSocket(request, listener(target, closed))
                webSocket = socket
                try {
                    closed.await()
                } finally {
                    socket.cancel()
                    if (webSocket == socket) webSocket = null
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                updateStatus(TerminalFindConnectionStatus.Reconnecting, target.nodeName, error.message.orEmpty())
            }
        }
        connectedTarget = null
        updateStatus(TerminalFindConnectionStatus.Waiting)
    }

    private fun listener(
        target: EdgeTerminalConnectionTarget,
        closed: CompletableDeferred<Unit>,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val registration = target.registration
            val message = TerminalRegisterMessage(
                type = "register",
                protocolVersion = TERMINAL_PROTOCOL_VERSION,
                terminalId = registration.terminalId,
                terminalName = registration.terminalName,
                appVersion = registration.appVersion,
                platform = registration.platform,
                lanIp = registration.lanIp,
                capabilities = registration.capabilities,
            )
            webSocket.send(json.encodeToString(message))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { json.decodeFromString<TerminalIncomingMessage>(text) }.getOrNull() ?: return
            when (message.type) {
                "registered" -> updateStatus(TerminalFindConnectionStatus.Online, target.nodeName)
                "ping" -> webSocket.send(json.encodeToString(TerminalOutgoingMessage(type = "pong")))
                "command" -> serviceScope.launch { handleCommand(message) }
                "error" -> updateStatus(
                    TerminalFindConnectionStatus.Reconnecting,
                    target.nodeName,
                    message.message.ifBlank { "Edge node rejected terminal connection" },
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            updateStatus(TerminalFindConnectionStatus.Reconnecting, target.nodeName, t.message.orEmpty())
            closed.complete(Unit)
        }
    }

    private fun handleCommand(message: TerminalIncomingMessage) {
        if (message.commandId.isBlank() || !rememberCommand(message.commandId)) return
        when (message.action) {
            "find_device" -> {
                val duration = message.durationSeconds.coerceIn(1, MAX_RING_DURATION_SECONDS)
                val started = alarm.start()
                if (!started) {
                    sendResult(message.commandId, "failed", "Unable to start alarm audio")
                    return
                }
                activeCommandId = message.commandId
                updateStatus(TerminalFindConnectionStatus.Ringing, connectedTarget?.nodeName.orEmpty())
                sendResult(message.commandId, "playing")
                expiryJob?.cancel()
                expiryJob = serviceScope.launch {
                    delay(duration * 1_000L)
                    if (activeCommandId == message.commandId) stopRingingLocally("expired")
                }
            }
            "stop_find_device" -> {
                if (message.targetCommandId.isBlank() || message.targetCommandId == activeCommandId) {
                    expiryJob?.cancel()
                    alarm.stop()
                    activeCommandId = ""
                    updateStatus(TerminalFindConnectionStatus.Online, connectedTarget?.nodeName.orEmpty())
                }
                sendResult(message.commandId, "stopped")
            }
            else -> sendResult(message.commandId, "failed", "Unsupported terminal action")
        }
    }

    private fun rememberCommand(commandId: String): Boolean {
        if (!processedCommands.add(commandId)) return false
        while (processedCommands.size > MAX_PROCESSED_COMMANDS) {
            processedCommands.remove(processedCommands.first())
        }
        return true
    }

    private fun stopRingingLocally(status: String) {
        val commandId = activeCommandId
        expiryJob?.cancel()
        alarm.stop()
        activeCommandId = ""
        updateStatus(
            if (webSocket == null) TerminalFindConnectionStatus.Waiting else TerminalFindConnectionStatus.Online,
            connectedTarget?.nodeName.orEmpty(),
        )
        if (commandId.isNotBlank()) sendResult(commandId, status)
    }

    private fun startLocalTest() {
        if (!alarm.start()) return
        updateStatus(TerminalFindConnectionStatus.Ringing, connectedTarget?.nodeName.orEmpty())
        expiryJob?.cancel()
        expiryJob = serviceScope.launch {
            delay(TEST_RING_DURATION_MS)
            alarm.stop()
            updateStatus(
                if (webSocket == null) TerminalFindConnectionStatus.Waiting else TerminalFindConnectionStatus.Online,
                connectedTarget?.nodeName.orEmpty(),
            )
        }
    }

    private fun sendResult(commandId: String, status: String, message: String = "") {
        webSocket?.send(
            json.encodeToString(
                TerminalOutgoingMessage(
                    type = "command_result",
                    commandId = commandId,
                    status = status,
                    message = message,
                ),
            ),
        )
    }

    private fun stopForLogout() {
        expiryJob?.cancel()
        centerPresenceJob?.cancel()
        centerPresenceJob = null
        alarm.stop()
        webSocket?.close(1000, "logged out")
        webSocket = null
        connectionJob?.cancel()
        terminalRepository.clearConnectionState()
        serviceScope.launch { ticketProvider.clear() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(
        status: TerminalFindConnectionStatus,
        nodeName: String = connectedTarget?.nodeName.orEmpty(),
        error: String? = null,
    ) {
        val lastError = when {
            error != null -> error
            status == TerminalFindConnectionStatus.Online ||
                status == TerminalFindConnectionStatus.Ringing ||
                status == TerminalFindConnectionStatus.Stopped -> ""
            else -> statusStore.state.value.lastError
        }
        statusStore.update(TerminalFindState(status, nodeName, lastError))
        showNotification(status, nodeName)
    }

    private fun showNotification(status: TerminalFindConnectionStatus, nodeName: String = "") {
        val title = getString(R.string.terminal_find_notification_title)
        val text = when (status) {
            TerminalFindConnectionStatus.Online -> getString(R.string.terminal_find_notification_online, nodeName)
            TerminalFindConnectionStatus.Ringing -> getString(R.string.terminal_find_notification_ringing)
            TerminalFindConnectionStatus.Waiting -> getString(R.string.edge_waiting)
            TerminalFindConnectionStatus.Stopped -> getString(R.string.terminal_find_notification_stopped)
            else -> getString(R.string.terminal_find_notification_connecting)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (status == TerminalFindConnectionStatus.Ringing) {
            val stopIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, TerminalFindService::class.java).setAction(ACTION_STOP_RINGING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.terminal_find_stop), stopIntent)
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            builder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.terminal_find_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.terminal_find_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun EdgeTerminalConnectionTarget.webSocketUrl(): String {
        val normalized = baseUrl.trimEnd('/')
        val socketBase = when {
            normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}"
            normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}"
            else -> normalized
        }
        return "$socketBase/edge/terminals/connect"
    }

    companion object {
        private const val LOG_TAG = "TerminalFindService"
        private const val NOTIFICATION_CHANNEL_ID = "fscm_terminal_find"
        private const val NOTIFICATION_ID = 7301
        private const val MAX_RING_DURATION_SECONDS = 60
        private const val MAX_PROCESSED_COMMANDS = 100
        private const val TEST_RING_DURATION_MS = 5_000L
        private const val CENTER_HEARTBEAT_INTERVAL_MS = 30_000L
        const val ACTION_STOP_RINGING = "com.fscm.app.action.STOP_FIND_RINGING"
        const val ACTION_TEST_RINGING = "com.fscm.app.action.TEST_FIND_RINGING"
        const val ACTION_STOP_SERVICE = "com.fscm.app.action.STOP_TERMINAL_SERVICE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TerminalFindService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TerminalFindService::class.java).setAction(ACTION_STOP_SERVICE))
        }

        fun testAlarm(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TerminalFindService::class.java).setAction(ACTION_TEST_RINGING),
            )
        }
    }
}

@Singleton
class FindDeviceAlarm @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var originalAlarmVolume: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    private var running = false
    private val toneLoop = object : Runnable {
        override fun run() {
            if (!running) return
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
            handler.postDelayed(this, 1_200L)
        }
    }

    @Synchronized
    fun start(): Boolean {
        stop()
        running = true
        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maximum, 0) }
        requestAudioFocus()
        acquireWakeLock()
        vibrate()
        val started = runCatching {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }.also { mediaPlayer = it }
        }.isSuccess
        if (!started) {
            toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
            if (toneGenerator != null) handler.post(toneLoop)
        }
        return started || toneGenerator != null
    }

    @Synchronized
    fun stop() {
        running = false
        handler.removeCallbacks(toneLoop)
        runCatching { mediaPlayer?.stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        toneGenerator?.release()
        toneGenerator = null
        vibrator()?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        abandonAudioFocus()
        originalAlarmVolume?.let { original ->
            val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == maximum) {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, original, 0) }
            }
        }
        originalAlarmVolume = null
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
                .also(audioManager::requestAudioFocus)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    private fun acquireWakeLock() {
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FSCM:FindDevice")
            .apply {
                setReferenceCounted(false)
                acquire(65_000L)
            }
    }

    private fun vibrate() {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createWaveform(longArrayOf(0, 500, 400), 0)
        } else {
            null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator()?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator()?.vibrate(longArrayOf(0, 500, 400), 0)
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}

@AndroidEntryPoint
class TerminalFindBootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var sessionStore: SessionStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (sessionStore.current().isLoggedIn) runCatching { TerminalFindService.start(context) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
