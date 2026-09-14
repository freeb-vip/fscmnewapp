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

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.samples.apps.nowinandroid.BuildConfig
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.model.data.DisplayMode
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelDocumentRef
import com.google.samples.apps.nowinandroid.fscm.data.BoxMarkPrintAvailability
import com.google.samples.apps.nowinandroid.fscm.data.BusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.CatalogMediaRequest
import com.google.samples.apps.nowinandroid.fscm.data.CatalogSource
import com.google.samples.apps.nowinandroid.fscm.data.ConnectivityMode
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeOption
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeStatus
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintTemplate
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchHit
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchResponse
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppUsage
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.ProfitOverview
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingProductBusiness
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SearchDestination
import com.google.samples.apps.nowinandroid.fscm.data.SessionMode
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.ShopOverview
import com.google.samples.apps.nowinandroid.fscm.data.Sku
import com.google.samples.apps.nowinandroid.fscm.data.boxMarkPrintAvailability
import com.google.samples.apps.nowinandroid.fscm.data.commonMobileApps
import com.google.samples.apps.nowinandroid.fscm.data.formatAmount
import com.google.samples.apps.nowinandroid.fscm.data.formatCount
import com.google.samples.apps.nowinandroid.fscm.data.formatPercent
import com.google.samples.apps.nowinandroid.fscm.data.hiddenMobileApps
import com.google.samples.apps.nowinandroid.fscm.data.shortTime
import com.google.samples.apps.nowinandroid.fscm.data.shownMobileApps
import com.google.samples.apps.nowinandroid.fscm.data.toBusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import com.google.samples.apps.nowinandroid.fscm.device.PdaPermissions
import com.google.samples.apps.nowinandroid.fscm.device.ScanAudioDiagnostics
import com.google.samples.apps.nowinandroid.fscm.device.ScanFeedbackPlaybackResult
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerRuntimeState
import com.google.samples.apps.nowinandroid.fscm.device.TerminalFindConnectionStatus
import com.google.samples.apps.nowinandroid.fscm.device.TerminalFindService
import com.google.samples.apps.nowinandroid.fscm.device.TerminalFindState
import okhttp3.Headers
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BrandMain = Color(0xFF1677FF)
private val BrandDeep = Color(0xFF0958D9)
private val BrandSoft = Color(0xFFE8F1FF)
private val PageTop = Color(0xFFF5F8FF)
private val PageBottom = Color(0xFFEFF4FB)
private val SurfaceRaised = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF6F9FD)
private val StrokeSoft = Color(0xFFDCE7F5)
private val InkStrong = Color(0xFF172033)
private val InkSoft = Color(0xFF66758A)
private val Success = Color(0xFF52C41A)
private val Warning = Color(0xFFFAAD14)
private val Danger = Color(0xFFFF4D4F)
private val Teal = Color(0xFF13A8A8)

@Composable
fun FscmApp(
    rootViewModel: FscmRootViewModel = hiltViewModel(),
    appUpdateViewModel: AppUpdateViewModel = hiltViewModel(),
) {
    val startupState by rootViewModel.startupState.collectAsStateWithLifecycle()
    val session = (startupState as? FscmStartupUiState.Ready)?.session ?: SessionState()
    val message by rootViewModel.message.collectAsStateWithLifecycle()
    val loginState by rootViewModel.loginState.collectAsStateWithLifecycle()
    val globalScanResult by rootViewModel.globalScanResult.collectAsStateWithLifecycle()
    val pendingSyncCount by rootViewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val userData by rootViewModel.userData.collectAsStateWithLifecycle()
    val appUpdateState by appUpdateViewModel.state.collectAsStateWithLifecycle()
    val terminalFindState by rootViewModel.terminalFindState.collectAsStateWithLifecycle()
    val appUpdateMessage by appUpdateViewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val devicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        appUpdateViewModel.onUnknownSourcesResult()
    }
    val installerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.resolve(context))
        rootViewModel.clearMessage()
    }

    LaunchedEffect(appUpdateMessage) {
        val current = appUpdateMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.resolve(context))
        appUpdateViewModel.clearMessage()
    }

    LaunchedEffect(appUpdateViewModel) {
        appUpdateViewModel.effects.collect { effect ->
            when (effect) {
                is AppUpdateEffect.OpenUnknownSourcesSettings -> unknownSourcesLauncher.launch(effect.intent)
                is AppUpdateEffect.OpenInstaller -> installerLauncher.launch(effect.intent)
            }
        }
    }

    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn) {
            val permissions = buildList {
                addAll(PdaPermissions.bluetoothRuntimePermissions.asList())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }.filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (permissions.isNotEmpty()) devicePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // Global scanner registration for logged-in session
    DisposableEffect(session.isLoggedIn, lifecycleOwner) {
        if (session.isLoggedIn) {
            rootViewModel.registerScanner(lifecycleOwner)
        }
        onDispose {
            if (session.isLoggedIn) {
                rootViewModel.unregisterScanner()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                rootViewModel.validateActiveSession()
                rootViewModel.refreshEdgeConnection()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    FscmTheme {
        Box(Modifier.fillMaxSize()) {
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(Brush.verticalGradient(listOf(PageTop, PageBottom))),
                ) {
                    when {
                        startupState is FscmStartupUiState.Restoring ->
                            LoadingScreen(stringResource(R.string.connecting_fscm))
                        !session.isLoggedIn -> LoginScreen(
                            state = loginState,
                            onUsernameChanged = rootViewModel::onLoginUsernameChanged,
                            onRefreshCaptcha = rootViewModel::refreshLoginCaptcha,
                            onLogin = rootViewModel::login,
                        )
                        else -> MainShell(
                            session = session,
                            pendingSyncCount = pendingSyncCount,
                            onLogout = rootViewModel::logout,
                            onSwitchNamespace = rootViewModel::switchNamespace,
                            language = AppLanguage.selected(),
                            onUpdateLanguage = ::setAppLanguage,
                            displayMode = userData.displayMode,
                            onUpdateDisplayMode = rootViewModel::updateDisplayMode,
                            onCheckForUpdate = appUpdateViewModel::checkManually,
                            terminalFindState = terminalFindState,
                        )
                    }
                }
            }
            AppUpdateDialog(
                state = appUpdateState,
                onDownload = appUpdateViewModel::startDownload,
                onPostpone = appUpdateViewModel::postpone,
                onCancelDownload = appUpdateViewModel::cancelDownload,
                onInstall = appUpdateViewModel::requestInstall,
                onExit = { (context as? Activity)?.finishAffinity() },
            )
        }
    }
}

@Composable
private fun AppUpdateDialog(
    state: AppUpdateUiState,
    onDownload: () -> Unit,
    onPostpone: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onExit: () -> Unit,
) {
    if (state is AppUpdateUiState.Idle || state is AppUpdateUiState.Checking && !state.manual) return

    val context = LocalContext.current
    val release = when (state) {
        is AppUpdateUiState.Available -> state.release
        is AppUpdateUiState.Downloading -> state.release
        is AppUpdateUiState.Ready -> state.release
        is AppUpdateUiState.Failed -> state.release
        else -> null
    }
    val forceUpdate = when (state) {
        is AppUpdateUiState.Available -> state.forceUpdate
        is AppUpdateUiState.Downloading -> state.forceUpdate
        is AppUpdateUiState.Ready -> state.forceUpdate
        is AppUpdateUiState.Failed -> state.forceUpdate
        else -> false
    }
    val title = when {
        state is AppUpdateUiState.Checking -> stringResource(R.string.app_update)
        forceUpdate -> stringResource(R.string.app_update_required_title)
        else -> stringResource(R.string.app_update_available_title)
    }

    AlertDialog(
        onDismissRequest = { if (!forceUpdate) onPostpone() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is AppUpdateUiState.Checking -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.app_update_checking))
                        }
                    }
                    is AppUpdateUiState.Available -> {
                        Text(
                            stringResource(
                                R.string.app_update_available_message,
                                state.release.versionName,
                                state.release.versionCode,
                            ),
                        )
                    }
                    is AppUpdateUiState.Downloading -> {
                        Text(stringResource(R.string.app_update_downloading))
                        val progress = if (state.totalBytes > 0) {
                            (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.totalBytes > 0) {
                            Text(
                                stringResource(
                                    R.string.app_update_download_progress,
                                    (progress * 100).toInt(),
                                ),
                                color = InkSoft,
                            )
                        }
                    }
                    is AppUpdateUiState.Ready -> Text(stringResource(R.string.app_update_ready))
                    is AppUpdateUiState.Failed -> Text(state.message.resolve(context), color = Danger)
                    AppUpdateUiState.Idle -> Unit
                }
                if (!release?.releaseNotes.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.app_update_release_notes),
                        color = InkStrong,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(release.releaseNotes, color = InkSoft)
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateUiState.Available -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.app_update_now))
                }
                is AppUpdateUiState.Ready -> Button(onClick = onInstall) {
                    Text(stringResource(R.string.app_update_install))
                }
                is AppUpdateUiState.Failed -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.app_update_retry))
                }
                else -> Unit
            }
        },
        dismissButton = {
            when {
                forceUpdate -> TextButton(onClick = onExit) {
                    Text(stringResource(R.string.app_update_exit))
                }
                state is AppUpdateUiState.Downloading -> TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.app_update_cancel_download))
                }
                state is AppUpdateUiState.Available || state is AppUpdateUiState.Failed ->
                    TextButton(onClick = onPostpone) {
                        Text(stringResource(R.string.app_update_later))
                    }
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !forceUpdate,
            dismissOnClickOutside = !forceUpdate,
        ),
    )
}

@Composable
private fun FscmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = BrandMain,
            onPrimary = Color.White,
            primaryContainer = BrandSoft,
            onPrimaryContainer = BrandDeep,
            secondary = BrandDeep,
            secondaryContainer = Color(0xFFE6F7FF),
            background = PageBottom,
            surface = SurfaceRaised,
            surfaceVariant = SurfaceMuted,
            outlineVariant = StrokeSoft,
            error = Danger,
        ),
        typography = MaterialTheme.typography,
        content = content,
    )
}

@Composable
private fun LoadingScreen(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = BrandMain)
            Text(label, color = InkSoft)
        }
    }
}

@Composable
private fun fscmTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandMain,
    unfocusedBorderColor = StrokeSoft,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    cursorColor = BrandMain,
    focusedLabelColor = BrandMain,
)

@Composable
private fun LoginScreen(
    state: LoginUiState,
    onUsernameChanged: (String) -> Unit,
    onRefreshCaptcha: (String) -> Unit,
    onLogin: (String, String, String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var captchaAnswer by rememberSaveable { mutableStateOf("") }
    val captcha = state.security?.captcha
    val loginEnabled = isLoginSubmissionEnabled(username, password, captchaAnswer, state)
    LaunchedEffect(captcha?.captchaId) {
        captchaAnswer = ""
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(BrandMain, BrandDeep)))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.brand_mark), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.mobile_workbench), color = Color.White.copy(alpha = 0.76f), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                FscmCard(padding = 20) {
                    Text(stringResource(R.string.account_login), color = InkStrong, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.account_login_description), color = InkSoft, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            onUsernameChanged(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.username)) },
                        enabled = !state.submitting,
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = fscmTextFieldColors(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.password)) },
                        enabled = !state.submitting,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = fscmTextFieldColors(),
                    )
                    if (state.requiresCaptcha) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = captchaAnswer,
                                onValueChange = { captchaAnswer = it.filter(Char::isDigit) },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.login_captcha)) },
                                enabled = !state.isLocked && !state.submitting,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (loginEnabled) onLogin(username.trim(), password, captchaAnswer)
                                    },
                                ),
                                shape = RoundedCornerShape(8.dp),
                                colors = fscmTextFieldColors(),
                            )
                            OutlinedButton(
                                onClick = { onRefreshCaptcha(username.trim()) },
                                modifier = Modifier.width(124.dp),
                                enabled = !state.isLocked && !state.submitting && !state.refreshingCaptcha,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 15.dp),
                            ) {
                                if (state.refreshingCaptcha) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.login_captcha_refresh),
                                    )
                                    captcha?.question?.takeIf(String::isNotBlank)?.let { question ->
                                        Spacer(Modifier.width(6.dp))
                                        Text(question, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    state.security?.takeIf { it.failedCount > 0 || it.lockRemainingSeconds > 0 }?.let { security ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (state.isLocked) {
                                stringResource(
                                    R.string.login_security_locked,
                                    security.failedCount,
                                    security.lockRemainingSeconds,
                                )
                            } else {
                                stringResource(
                                    R.string.login_security_failed_hint,
                                    security.failedCount,
                                    security.remainingAttempts,
                                )
                            },
                            color = if (state.isLocked) Danger else Warning,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { onLogin(username.trim(), password, captchaAnswer) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = loginEnabled,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandMain),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.login))
                    }
                }
            }
        }
    }
}

private enum class MainTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    Workbench(R.string.home, Icons.Rounded.Home),
    Apps(R.string.apps, Icons.Rounded.Apps),
    Messages(R.string.messages, Icons.Rounded.Mail),
    Profile(R.string.profile, Icons.Rounded.Person),
}

internal sealed interface FeatureRoute : Serializable {
    data object None : FeatureRoute
    data object Profit : FeatureRoute
    data object ReceivingList : FeatureRoute
    data class ReceivingDetail(val id: Long) : FeatureRoute
    data object InboundManagement : FeatureRoute
    data class InboundSession(val id: Long) : FeatureRoute
    data object CentralReceivingList : FeatureRoute
    data class CentralReceivingDetail(val id: Long) : FeatureRoute
    data class ReceivingProductProgress(val business: ReceivingProductBusiness, val documentId: Long) : FeatureRoute
    data object SupplierOrders : FeatureRoute
    data class SupplierOrderDetail(val id: Long, val returnToPaymentBillId: Long? = null) : FeatureRoute
    data object ManufacturerPaymentBills : FeatureRoute
    data class ManufacturerPaymentBillDetail(val id: Long) : FeatureRoute
    data object CameraScanner : FeatureRoute
    data object ProductManagement : FeatureRoute
    data object ProductCountingList : FeatureRoute
    data class ProductCountingDetail(val taskId: String) : FeatureRoute
    data class ProductSkuList(val productId: Long, val productName: String) : FeatureRoute
    data class SkuDetail(val idOrCode: String) : FeatureRoute
    data class BoxLabelDetail(val code: String) : FeatureRoute
    data object CartonManagement : FeatureRoute
    data object PdaMode : FeatureRoute
    data object AppSettings : FeatureRoute
    data object PdaSettings : FeatureRoute
    data object EdgeNodes : FeatureRoute
    data class EdgeNodeDetail(val nodeId: Long) : FeatureRoute
    data object LabelPrint : FeatureRoute
}

private fun FeatureRoute.parentRoute(): FeatureRoute = when (this) {
    is FeatureRoute.ReceivingDetail -> FeatureRoute.ReceivingList
    is FeatureRoute.InboundSession -> FeatureRoute.InboundManagement
    is FeatureRoute.CentralReceivingDetail -> FeatureRoute.CentralReceivingList
    is FeatureRoute.ReceivingProductProgress -> when (business) {
        ReceivingProductBusiness.Warehouse -> FeatureRoute.ReceivingDetail(documentId)
        ReceivingProductBusiness.Central -> FeatureRoute.CentralReceivingDetail(documentId)
        ReceivingProductBusiness.Inbound -> FeatureRoute.InboundSession(documentId)
    }
    is FeatureRoute.SupplierOrderDetail -> returnToPaymentBillId?.let { FeatureRoute.ManufacturerPaymentBillDetail(it) }
        ?: FeatureRoute.SupplierOrders
    is FeatureRoute.ManufacturerPaymentBillDetail -> FeatureRoute.ManufacturerPaymentBills
    is FeatureRoute.ProductCountingDetail -> FeatureRoute.ProductCountingList
    is FeatureRoute.ProductSkuList,
    is FeatureRoute.SkuDetail,
    -> FeatureRoute.ProductManagement
    is FeatureRoute.BoxLabelDetail -> FeatureRoute.CartonManagement
    FeatureRoute.PdaSettings, FeatureRoute.EdgeNodes -> FeatureRoute.AppSettings
    is FeatureRoute.EdgeNodeDetail -> FeatureRoute.EdgeNodes
    else -> FeatureRoute.None
}

private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

@Composable
private fun MainShell(
    session: SessionState,
    pendingSyncCount: Int,
    onLogout: () -> Unit,
    onSwitchNamespace: (Long) -> Unit,
    language: AppLanguage,
    onUpdateLanguage: (AppLanguage) -> Unit,
    displayMode: DisplayMode,
    onUpdateDisplayMode: (DisplayMode) -> Unit,
    onCheckForUpdate: () -> Unit,
    terminalFindState: TerminalFindState,
) {
    val rootViewModel: FscmRootViewModel = hiltViewModel()
    val workbenchViewModel: WorkbenchViewModel = hiltViewModel()
    val globalScanResult by rootViewModel.globalScanResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val displayPolicy = remember(displayMode, configuration.screenWidthDp, configuration.fontScale) {
        fscmDisplayPolicy(
            displayMode = displayMode,
            screenWidthDp = configuration.screenWidthDp,
            fontScale = configuration.fontScale,
        )
    }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Workbench) }
    var featureRoute by rememberSaveable { mutableStateOf<FeatureRoute>(FeatureRoute.None) }
    var lastBackPressedAt by remember { mutableStateOf(0L) }
    val exitConfirmMessage = stringResource(R.string.exit_confirm)

    LaunchedEffect(globalScanResult) {
        if (globalScanResult == null) return@LaunchedEffect
        rootViewModel.consumeGlobalScan()
    }

    BackHandler {
        if (!rootViewModel.consumeBackEventAfterScan()) {
            when {
                featureRoute != FeatureRoute.None -> featureRoute = featureRoute.parentRoute()
                selectedTab != MainTab.Workbench -> selectedTab = MainTab.Workbench
                else -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressedAt <= EXIT_CONFIRM_WINDOW_MS) {
                        (context as? Activity)?.finish()
                    } else {
                        lastBackPressedAt = now
                        Toast.makeText(context, exitConfirmMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (featureRoute !is FeatureRoute.None) {
        FeatureScreen(
            settingsContent = {
                ProfileScreen(
                    session = session, onLogout = onLogout, onSwitchNamespace = onSwitchNamespace,
                    language = language, onUpdateLanguage = onUpdateLanguage,
                    displayMode = displayMode, onUpdateDisplayMode = onUpdateDisplayMode,
                    onCheckForUpdate = onCheckForUpdate, terminalFindState = terminalFindState,
                    displayPolicy = displayPolicy, settingsOnly = true, onOpenFeature = { featureRoute = it },
                )
            },
            route = featureRoute,
            session = session,
            onBack = { featureRoute = featureRoute.parentRoute() },
            onNavigate = { featureRoute = it },
            onOpenReceivingDetail = { featureRoute = FeatureRoute.ReceivingDetail(it) },
            onScanResult = { barcode ->
                featureRoute = FeatureRoute.None
                // TODO: Handle global barcode scan if needed
            },
        )
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(
                containerColor = Color.White.copy(alpha = 0.96f),
                tonalElevation = 8.dp,
            ) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.titleRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrandMain,
                            selectedTextColor = BrandDeep,
                            indicatorColor = BrandSoft,
                            unselectedIconColor = InkSoft,
                            unselectedTextColor = InkSoft,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                MainTab.Workbench -> WorkbenchScreen(
                    session = session,
                    pendingSyncCount = pendingSyncCount,
                    onOpenFeature = { featureRoute = it },
                    viewModel = workbenchViewModel,
                    displayPolicy = displayPolicy,
                )
                MainTab.Apps -> AppsScreen(
                    session = session,
                    onOpenFeature = { featureRoute = it },
                    viewModel = workbenchViewModel,
                    displayPolicy = displayPolicy,
                )
                MainTab.Messages -> MessagesScreen(
                    session = session,
                    viewModel = workbenchViewModel,
                    displayPolicy = displayPolicy,
                )
                MainTab.Profile -> ProfileScreen(
                    onOpenFeature = { featureRoute = it },
                    session = session,
                    onLogout = onLogout,
                    onSwitchNamespace = onSwitchNamespace,
                    language = language,
                    onUpdateLanguage = onUpdateLanguage,
                    displayMode = displayMode,
                    onUpdateDisplayMode = onUpdateDisplayMode,
                    onCheckForUpdate = onCheckForUpdate,
                    terminalFindState = terminalFindState,
                    displayPolicy = displayPolicy,
                )
            }
        }
    }
}

@Composable
private fun FeatureScreen(
    settingsContent: @Composable () -> Unit,
    route: FeatureRoute,
    session: SessionState,
    onBack: () -> Unit,
    onNavigate: (FeatureRoute) -> Unit = {},
    onOpenReceivingDetail: (Long) -> Unit,
    onScanResult: (String) -> Unit = {},
) {
    val receivingDetailId = (route as? FeatureRoute.ReceivingDetail)?.id
    val receivingDetailViewModel: ReceivingDetailViewModel? =
        if (receivingDetailId != null) {
            hiltViewModel(key = "receiving-detail-$receivingDetailId")
        } else {
            null
        }
    val productCountingTaskId = (route as? FeatureRoute.ProductCountingDetail)?.taskId
    val productCountingDetailViewModel: ProductCountingDetailViewModel? =
        if (productCountingTaskId != null) {
            hiltViewModel(key = "product-counting-detail-$productCountingTaskId")
        } else {
            null
        }
    val supplierOrdersViewModel: SupplierOrdersViewModel? =
        if (route == FeatureRoute.SupplierOrders) {
            hiltViewModel(key = "supplier-orders")
        } else {
            null
        }
    val manufacturerPaymentBillDetailViewModel: ManufacturerPaymentBillDetailViewModel? =
        if (route is FeatureRoute.ManufacturerPaymentBillDetail) {
            hiltViewModel(key = "manufacturer-payment-bill-${route.id}")
        } else {
            null
        }
    val manufacturerPaymentBillDetailState = if (manufacturerPaymentBillDetailViewModel != null) {
        val state by manufacturerPaymentBillDetailViewModel.state.collectAsStateWithLifecycle()
        state
    } else {
        null
    }

    BackHandler(enabled = manufacturerPaymentBillDetailState?.hasUnsubmittedAttachments == true) {
        manufacturerPaymentBillDetailViewModel?.requestDiscardNavigation()
    }

    Scaffold(
        topBar = {
            if (route != FeatureRoute.CameraScanner) {
                FscmTopBar(
                    title = when (route) {
                        FeatureRoute.Profit -> stringResource(R.string.profit_dashboard)
                        FeatureRoute.ReceivingList -> stringResource(R.string.warehouse_receiving)
                        is FeatureRoute.ReceivingDetail -> stringResource(R.string.scan_receiving)
                        FeatureRoute.InboundManagement -> stringResource(R.string.inbound_title)
                        is FeatureRoute.InboundSession -> stringResource(R.string.inbound_title)
                        FeatureRoute.CentralReceivingList -> stringResource(R.string.feature_central_receiving)
                        is FeatureRoute.CentralReceivingDetail -> stringResource(R.string.feature_central_receiving_detail)
                        is FeatureRoute.ReceivingProductProgress -> stringResource(R.string.receiving_product_details_title)
                        FeatureRoute.SupplierOrders -> stringResource(R.string.feature_supplier_orders)
                        is FeatureRoute.SupplierOrderDetail -> stringResource(R.string.feature_supplier_order_detail)
                        FeatureRoute.ManufacturerPaymentBills -> stringResource(R.string.feature_manufacturer_payment_bills)
                        is FeatureRoute.ManufacturerPaymentBillDetail -> stringResource(R.string.feature_manufacturer_payment_bill_detail)
                        FeatureRoute.ProductManagement -> stringResource(R.string.feature_product_management)
                        FeatureRoute.ProductCountingList -> stringResource(R.string.feature_product_counting)
                        is FeatureRoute.ProductCountingDetail -> stringResource(R.string.feature_product_counting)
                        is FeatureRoute.ProductSkuList -> route.productName
                        is FeatureRoute.SkuDetail -> stringResource(R.string.feature_sku_detail)
                        is FeatureRoute.BoxLabelDetail -> stringResource(R.string.feature_box_label_detail)
                        FeatureRoute.CartonManagement -> stringResource(R.string.feature_box_label_management)
                        FeatureRoute.PdaMode -> stringResource(R.string.feature_pda_scan_mode)
                        FeatureRoute.AppSettings -> stringResource(R.string.app_settings_title)
                        FeatureRoute.PdaSettings -> stringResource(R.string.feature_pda_settings)
                        FeatureRoute.EdgeNodes -> stringResource(R.string.feature_edge_nodes)
                        is FeatureRoute.EdgeNodeDetail -> stringResource(R.string.feature_edge_nodes)
                        FeatureRoute.LabelPrint -> stringResource(R.string.feature_label_printing)
                        FeatureRoute.None -> ""
                        FeatureRoute.CameraScanner -> ""
                    },
                    onBack = {
                        if (manufacturerPaymentBillDetailState?.hasUnsubmittedAttachments == true) {
                            manufacturerPaymentBillDetailViewModel?.requestDiscardNavigation()
                        } else {
                            onBack()
                        }
                    },
                    actions = {
                        receivingDetailViewModel?.let { viewModel ->
                            val detailState by viewModel.state.collectAsStateWithLifecycle()
                            IconButton(
                                onClick = { viewModel.sync(requireNotNull(receivingDetailId)) },
                                enabled = !detailState.syncing,
                            ) {
                                if (detailState.syncing) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.receiving_sync_now),
                                    )
                                }
                            }
                        }
                        productCountingDetailViewModel?.let { viewModel ->
                            val detailState by viewModel.state.collectAsStateWithLifecycle()
                            if (detailState.task?.task?.status == com.google.samples.apps.nowinandroid.fscm.data.ProductCountTaskStatus.InProgress.value) {
                                TextButton(
                                    onClick = viewModel::requestReset,
                                    enabled = detailState.editable && !detailState.processing,
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.product_counting_reset))
                                }
                            }
                        }
                        supplierOrdersViewModel?.let { viewModel ->
                            val supplierState by viewModel.state.collectAsStateWithLifecycle()
                            IconButton(onClick = viewModel::openFilters) {
                                Box {
                                    Icon(
                                        Icons.Rounded.FilterList,
                                        contentDescription = stringResource(R.string.business_supplier_filters),
                                    )
                                    if (supplierState.appliedFilters.activeCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 8.dp, y = (-8).dp),
                                        ) {
                                            Badge(supplierState.appliedFilters.activeCount.toString(), BrandMain)
                                        }
                                    }
                                }
                            }
                        }

                        val productContext = when (route) {
                            is FeatureRoute.ReceivingDetail -> ReceivingProductBusiness.Warehouse to route.id
                            is FeatureRoute.CentralReceivingDetail -> ReceivingProductBusiness.Central to route.id
                            is FeatureRoute.InboundSession -> ReceivingProductBusiness.Inbound to route.id
                            else -> null
                        }
                        productContext?.let { (business, documentId) ->
                            TextButton(
                                onClick = {
                                    onNavigate(
                                        FeatureRoute.ReceivingProductProgress(
                                            business = business,
                                            documentId = documentId,
                                        ),
                                    )
                                },
                            ) {
                                Icon(Icons.Rounded.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.receiving_product_details_action))
                            }
                        }
                    },
                )
            }
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (route) {
                FeatureRoute.Profit -> ProfitScreen(session)
                FeatureRoute.ReceivingList -> WarehouseReceivingScreen(onOpenReceivingDetail)
                is FeatureRoute.ReceivingDetail -> ReceivingDetailScreen(
                    route.id,
                    onOpenCamera = { onScanResult("") },
                    viewModel = requireNotNull(receivingDetailViewModel),
                )
                FeatureRoute.InboundManagement -> InboundSessionListScreen(
                    onOpenSession = { onNavigate(FeatureRoute.InboundSession(it)) },
                )
                is FeatureRoute.InboundSession -> InboundSessionScreen(route.id)
                FeatureRoute.CentralReceivingList -> CentralReceivingScreen(
                    onOpenDetail = { onNavigate(FeatureRoute.CentralReceivingDetail(it)) },
                )
                is FeatureRoute.CentralReceivingDetail -> CentralReceiptDetailScreen(route.id)
                is FeatureRoute.ReceivingProductProgress -> ReceivingProductProgressScreen(
                    business = route.business,
                    documentId = route.documentId,
                )
                FeatureRoute.SupplierOrders -> SupplierOrdersScreen(
                    onOpenDetail = { onNavigate(FeatureRoute.SupplierOrderDetail(it)) },
                    viewModel = requireNotNull(supplierOrdersViewModel),
                )
                is FeatureRoute.SupplierOrderDetail -> SupplierOrderDetailScreen(route.id)
                FeatureRoute.ManufacturerPaymentBills -> ManufacturerPaymentBillsScreen(
                    onOpenBill = { onNavigate(FeatureRoute.ManufacturerPaymentBillDetail(it)) },
                    onOpenSupplierOrder = { onNavigate(FeatureRoute.SupplierOrderDetail(it)) },
                )
                is FeatureRoute.ManufacturerPaymentBillDetail -> ManufacturerPaymentBillDetailScreen(
                    id = route.id,
                    onOpenSupplierOrder = { onNavigate(FeatureRoute.SupplierOrderDetail(it, route.id)) },
                    onBack = onBack,
                    viewModel = requireNotNull(manufacturerPaymentBillDetailViewModel),
                )
                FeatureRoute.CameraScanner -> CameraScannerScreen(onBack = onBack, onResult = onScanResult)
                FeatureRoute.ProductManagement -> ProductManagementScreen(
                    onOpenSkuDetail = { onNavigate(FeatureRoute.SkuDetail(it)) },
                )
                FeatureRoute.ProductCountingList -> ProductCountingTaskListScreen(
                    onOpenTask = { onNavigate(FeatureRoute.ProductCountingDetail(it)) },
                )
                is FeatureRoute.ProductCountingDetail -> ProductCountingTaskDetailScreen(
                    taskId = route.taskId,
                    viewModel = requireNotNull(productCountingDetailViewModel),
                )
                is FeatureRoute.ProductSkuList -> ProductManagementScreen(
                    productId = route.productId,
                    onOpenSkuDetail = { onNavigate(FeatureRoute.SkuDetail(it)) },
                )
                is FeatureRoute.SkuDetail -> SkuSearchDetailScreen(route.idOrCode)
                is FeatureRoute.BoxLabelDetail -> BoxLabelSearchDetailScreen(route.code, onNavigate)
                FeatureRoute.CartonManagement -> CartonManagementScreen(onNavigate = onNavigate)
                FeatureRoute.PdaMode -> PdaModeScreen()
                FeatureRoute.AppSettings -> settingsContent()
                FeatureRoute.PdaSettings -> PdaSettingsScreen()
                FeatureRoute.EdgeNodes -> EdgeNodesScreen(onOpenDetail = { onNavigate(FeatureRoute.EdgeNodeDetail(it)) })
                is FeatureRoute.EdgeNodeDetail -> EdgeNodeDetailScreen(route.nodeId)
                FeatureRoute.LabelPrint -> LabelPrintScreen()
                FeatureRoute.None -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FscmTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, color = InkStrong, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
private fun WorkbenchScreen(
    session: SessionState,
    pendingSyncCount: Int,
    onOpenFeature: (FeatureRoute) -> Unit,
    viewModel: WorkbenchViewModel,
    displayPolicy: FscmDisplayPolicy,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val mobileAppUsage by viewModel.mobileAppUsage.collectAsStateWithLifecycle()
    LaunchedEffect(session.user?.id, session.currentNamespace?.id, session.sessionMode) {
        if (session.isLoggedIn) viewModel.load(session)
    }
    LaunchedEffect(viewModel) {
        viewModel.searchNavigation.collect { destination ->
            openSearchDestination(
                context = context,
                destination = destination,
                onOpenFeature = onOpenFeature,
                showUnavailable = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
            )
        }
    }
    val overview = state.data
    val allApps = visibleMobileApps(overview, session.permissions, session.sessionMode)
    val apps = shownMobileApps(allApps, mobileAppUsage)
    val commonApps = commonMobileApps(apps, mobileAppUsage)
    val density = LocalDensity.current
    val refreshThresholdPx = with(density) { 76.dp.toPx() }
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    val pullRefreshConnection = remember(refreshThresholdPx, state.loading, session) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || pullDistancePx <= 0f) return Offset.Zero
                val consumed = minOf(-available.y, pullDistancePx)
                pullDistancePx -= consumed
                return Offset(0f, -consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || state.loading) return Offset.Zero
                val consumedY = available.y * 0.5f
                pullDistancePx = (pullDistancePx + consumedY).coerceAtMost(refreshThresholdPx * 1.5f)
                return Offset(0f, consumedY)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.loading && pullDistancePx >= refreshThresholdPx) {
                    viewModel.refresh(session)
                }
                pullDistancePx = 0f
                return Velocity.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullRefreshConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = displayPolicy.pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(displayPolicy.sectionSpacing()),
        ) {
            item {
                WorkbenchHero(
                    sessionMode = session.sessionMode,
                    connectivityMode = session.connectivityMode,
                    pendingSyncCount = pendingSyncCount,
                    offlineExpiresAt = session.offlineLeaseExpiresAt,
                    searchQuery = searchQuery,
                    onSearch = { query -> viewModel.search(query) },
                    onQueryChange = { query -> viewModel.updateSearchQuery(query) },
                )
            }
            state.error?.let { item { FscmErrorCard(it, onRetry = { viewModel.refresh(session) }) } }
            if (commonApps.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(BrandMain, BrandDeep)))
                            .padding(12.dp),
                    ) {
                        AppGrid(
                            apps = commonApps,
                            usage = mobileAppUsage,
                            displayPolicy = displayPolicy,
                            onOpenFeature = onOpenFeature,
                            onOpenApp = { viewModel.recordMobileAppUsage(session, it.key) },
                            onToggleCommon = { viewModel.togglePinnedMobileApp(session, it.key) },
                            onHideApp = { viewModel.setMobileAppHidden(session, it.key, true) },
                        )
                    }
                }
            }
            item {
                TaskSection(
                    overview = overview,
                    apps = apps,
                    usage = mobileAppUsage,
                    onOpenFeature = onOpenFeature,
                    onOpenApp = { viewModel.recordMobileAppUsage(session, it.key) },
                    onToggleCommon = { viewModel.togglePinnedMobileApp(session, it.key) },
                    onHideApp = { viewModel.setMobileAppHidden(session, it.key, true) },
                    displayPolicy = displayPolicy,
                )
            }
            item {
                MessagesPreview(overview, displayPolicy)
            }
        }
        if (state.loading || pullDistancePx > 0f) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = with(density) { pullDistancePx.coerceAtMost(refreshThresholdPx).toDp() + 8.dp })
                    .size(28.dp),
                strokeWidth = 3.dp,
                color = BrandMain,
            )
        }

        if (searchState.loading || searchState.data != null || searchState.error != null) {
            GlobalSearchResultsOverlay(
                state = searchState,
                onClose = { viewModel.clearSearch() },
                onNavigate = { hit ->
                    viewModel.selectSearchHit(hit)
                },
            )
        }
    }
}

@Composable
private fun WorkbenchHero(
    sessionMode: SessionMode,
    connectivityMode: ConnectivityMode,
    pendingSyncCount: Int,
    offlineExpiresAt: Long,
    searchQuery: String,
    onSearch: (String) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var showCamera by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) showCamera = true
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(
                    onBarcodeDetected = { barcode ->
                        showCamera = false
                        onSearch(barcode.normalizeBarcode())
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FscmTopBar(title = stringResource(R.string.operation_scan_app_feature), onBack = { showCamera = false })
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(BrandMain, BrandDeep)))
            .padding(12.dp),
    ) {
        if (sessionMode == SessionMode.Offline) {
            Text(
                stringResource(R.string.operation_offline_summary, pendingSyncCount, formatOfflineExpiry(offlineExpiresAt)),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            if (connectivityMode == ConnectivityMode.EdgeOnly) {
                Text(
                    stringResource(R.string.operation_edge_available),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        SearchPanel(
            query = searchQuery,
            onScan = {
                if (PdaPermissions.hasCameraPermission(context)) {
                    showCamera = true
                } else {
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            },
            onSearch = onSearch,
            onQueryChange = onQueryChange,
        )
    }
}

@Composable
private fun SearchPanel(
    query: String,
    onScan: () -> Unit,
    onSearch: (String) -> Unit,
    onQueryChange: (String) -> Unit = {},
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var fieldValue by remember { mutableStateOf(TextFieldValue(query, selection = TextRange(0, query.length))) }

    LaunchedEffect(query) {
        if (fieldValue.text != query) {
            fieldValue = TextFieldValue(
                text = query,
                selection = TextRange(0, query.length),
            )
        }
    }

    fun submitSearch(value: String) {
        val normalized = value.trim()
        onQueryChange(normalized)
        onSearch(normalized)
        fieldValue = TextFieldValue(
            text = normalized,
            selection = TextRange(0, normalized.length),
        )
        keyboardController?.hide()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.96f))
            .padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { value ->
                fieldValue = value
                onQueryChange(value.text)
            },
            placeholder = {
                Text(
                    stringResource(R.string.operation_search_features),
                    color = InkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = BrandMain,
            ),
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = BrandMain, modifier = Modifier.size(20.dp))
            },
            trailingIcon = {
                IconButton(onClick = onScan) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.business_camera_scan), tint = BrandMain)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { submitSearch(fieldValue.text) }),
        )
    }
}

@Composable
private fun TaskSection(
    overview: MobileWorkbenchOverview?,
    apps: List<MobileAppEntry>,
    usage: MobileAppUsage,
    onOpenFeature: (FeatureRoute) -> Unit,
    onOpenApp: (MobileAppEntry) -> Unit,
    onToggleCommon: (MobileAppEntry) -> Unit,
    onHideApp: (MobileAppEntry) -> Unit,
    displayPolicy: FscmDisplayPolicy,
) {
    val todoCount = overview?.todos?.count { it.status !in listOf("done", "completed", "finished") } ?: 0
    val taskApps = apps.filter { it.key in setOf("receiving", "central_receiving", "manufacturer_payment_bills", "purchase_approvals", "supplier_orders", "tickets", "todos") }
        .map { app -> app to if (app.key == "todos") todoCount else app.badgeCount }
        .filter { (app, count) -> count > 0 || app.key in setOf("receiving", "central_receiving") }
    if (taskApps.isEmpty()) return
    SectionHeader(stringResource(R.string.operation_today_pending), stringResource(R.string.operation_task_count, taskApps.sumOf { it.second }))
    val rowCount = (taskApps.size + displayPolicy.taskGridColumns - 1) / displayPolicy.taskGridColumns
    val tileHeight = if (displayPolicy.isCompact) 112.dp else 120.dp
    val verticalSpacing = if (displayPolicy.isCompact) 8.dp else 12.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(displayPolicy.taskGridColumns),
        modifier = Modifier.height(tileHeight * rowCount + verticalSpacing * (rowCount - 1)),
        horizontalArrangement = Arrangement.spacedBy(if (displayPolicy.isCompact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        userScrollEnabled = false,
    ) {
        items(taskApps) { (app, count) ->
            BusinessCard(
                app = app,
                count = count,
                usage = usage,
                onOpenFeature = onOpenFeature,
                onOpenApp = onOpenApp,
                onToggleCommon = onToggleCommon,
                onHideApp = onHideApp,
                displayPolicy = displayPolicy,
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun BusinessCard(
    app: MobileAppEntry,
    count: Int,
    usage: MobileAppUsage,
    onOpenFeature: (FeatureRoute) -> Unit,
    onOpenApp: (MobileAppEntry) -> Unit,
    onToggleCommon: (MobileAppEntry) -> Unit,
    onHideApp: (MobileAppEntry) -> Unit,
    displayPolicy: FscmDisplayPolicy,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        FscmCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (displayPolicy.isCompact) 112.dp else 120.dp)
                .combinedClickable(
                    onClick = {
                        if (openAppEntry(context, app, onOpenFeature) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }) {
                            onOpenApp(app)
                        }
                    },
                    onLongClick = { menuExpanded = true },
                ),
            padding = if (displayPolicy.isCompact) 12 else 14,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FscmAppIcon(app, size = if (displayPolicy.isCompact) 40 else 44)
                Spacer(Modifier.weight(1f))
                if (count > 0) Badge(count.toString(), Danger)
            }
            Spacer(Modifier.height(if (displayPolicy.isCompact) 6.dp else 8.dp))
            Text(localizedAppTitle(app), color = InkStrong, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        }
        AppActionsMenu(
            expanded = menuExpanded,
            isCommon = usage.isPinned(app.key),
            onDismiss = { menuExpanded = false },
            onToggleCommon = { onToggleCommon(app) },
            onHideApp = { onHideApp(app) },
        )
    }
}

@Composable
private fun AppsScreen(
    session: SessionState,
    onOpenFeature: (FeatureRoute) -> Unit,
    viewModel: WorkbenchViewModel = hiltViewModel(),
    displayPolicy: FscmDisplayPolicy,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mobileAppUsage by viewModel.mobileAppUsage.collectAsStateWithLifecycle()
    LaunchedEffect(session.user?.id, session.currentNamespace?.id, session.sessionMode) {
        if (session.isLoggedIn) viewModel.load(session)
    }
    val allApps = visibleMobileApps(state.data, session.permissions, session.sessionMode)
    val apps = shownMobileApps(allApps, mobileAppUsage)
    val hiddenApps = hiddenMobileApps(allApps, mobileAppUsage)
    var showHiddenApps by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hiddenApps.isEmpty()) {
        if (hiddenApps.isEmpty()) showHiddenApps = false
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = displayPolicy.pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(displayPolicy.sectionSpacing()),
    ) {
        item {
            PageTitle(stringResource(R.string.apps), stringResource(R.string.apps_visible_count, apps.size))
        }
        state.error?.let { item { FscmErrorCard(it) { viewModel.refresh(session) } } }
        if (apps.isNotEmpty()) {
            item {
                AppGrid(
                    apps = apps,
                    usage = mobileAppUsage,
                    displayPolicy = displayPolicy,
                    onOpenFeature = onOpenFeature,
                    onOpenApp = { viewModel.recordMobileAppUsage(session, it.key) },
                    onToggleCommon = { viewModel.togglePinnedMobileApp(session, it.key) },
                    onHideApp = { viewModel.setMobileAppHidden(session, it.key, true) },
                )
            }
        }
        if (hiddenApps.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = { showHiddenApps = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.VisibilityOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.hidden_apps_count, hiddenApps.size))
                }
            }
        }
    }
    if (showHiddenApps && hiddenApps.isNotEmpty()) {
        HiddenAppsDialog(
            apps = hiddenApps,
            onShowApp = { viewModel.setMobileAppHidden(session, it.key, false) },
            onDismiss = { showHiddenApps = false },
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun AppGrid(
    apps: List<MobileAppEntry>,
    usage: MobileAppUsage,
    displayPolicy: FscmDisplayPolicy,
    onOpenFeature: (FeatureRoute) -> Unit,
    onOpenApp: (MobileAppEntry) -> Unit,
    onToggleCommon: (MobileAppEntry) -> Unit,
    onHideApp: (MobileAppEntry) -> Unit,
) {
    val context = LocalContext.current
    var menuAppKey by remember { mutableStateOf<String?>(null) }
    val rowCount = ((apps.size + displayPolicy.appGridColumns - 1) / displayPolicy.appGridColumns).coerceAtLeast(1)
    val tileHeight = if (displayPolicy.isCompact) 96.dp else 108.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(displayPolicy.appGridColumns),
        modifier = Modifier.height(tileHeight * rowCount + 8.dp * (rowCount - 1)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(apps) { app ->
            Box(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tileHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.94f))
                        .combinedClickable(
                            onClick = {
                                if (openAppEntry(context, app, onOpenFeature) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }) {
                                    onOpenApp(app)
                                }
                            },
                            onLongClick = { menuAppKey = app.key },
                        )
                        .padding(horizontal = 6.dp, vertical = if (displayPolicy.isCompact) 8.dp else 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        FscmAppIcon(app, size = if (displayPolicy.isCompact) 40 else 48)
                        if (app.badgeCount > 0) {
                            Box(Modifier.align(Alignment.TopEnd)) { Badge(app.badgeCount.toString(), Danger) }
                        }
                    }
                    Spacer(Modifier.height(if (displayPolicy.isCompact) 6.dp else 7.dp))
                    Text(
                        localizedAppTitle(app),
                        color = InkStrong,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.heightIn(min = 32.dp),
                    )
                }
                AppActionsMenu(
                    expanded = menuAppKey == app.key,
                    isCommon = usage.isPinned(app.key),
                    onDismiss = { menuAppKey = null },
                    onToggleCommon = { onToggleCommon(app) },
                    onHideApp = { onHideApp(app) },
                )
            }
        }
    }
}

@Composable
private fun AppActionsMenu(
    expanded: Boolean,
    isCommon: Boolean,
    onDismiss: () -> Unit,
    onToggleCommon: () -> Unit,
    onHideApp: () -> Unit,
) {
    val context = LocalContext.current
    val updateMessage = stringResource(if (isCommon) R.string.app_unpinned else R.string.app_pinned)
    val hiddenMessage = stringResource(R.string.app_hidden)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(if (isCommon) R.string.unpin_app else R.string.pin_app)) },
            leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
            onClick = {
                onToggleCommon()
                onDismiss()
                Toast.makeText(context, updateMessage, Toast.LENGTH_SHORT).show()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hide_app)) },
            leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
            onClick = {
                onHideApp()
                onDismiss()
                Toast.makeText(context, hiddenMessage, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun HiddenAppsDialog(
    apps: List<MobileAppEntry>,
    onShowApp: (MobileAppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shownMessage = stringResource(R.string.app_shown)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hidden_apps)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(apps, key = MobileAppEntry::key) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FscmAppIcon(app, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            localizedAppTitle(app),
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = {
                                onShowApp(app)
                                Toast.makeText(context, shownMessage, Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = stringResource(R.string.show_app))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun MessagesScreen(
    session: SessionState,
    viewModel: WorkbenchViewModel = hiltViewModel(),
    displayPolicy: FscmDisplayPolicy,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(session.user?.id, session.currentNamespace?.id, session.sessionMode) {
        if (session.isLoggedIn) viewModel.load(session)
    }
    val overview = state.data
    val todos = overview?.todos.orEmpty().filter { it.status !in listOf("done", "completed", "finished") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = displayPolicy.pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(if (displayPolicy.isCompact) 8.dp else 12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.messages), style = MaterialTheme.typography.headlineMedium, color = InkStrong, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.messages_summary, todos.size + overview?.announcements.orEmpty().size), color = InkSoft)
                }
                Icon(Icons.Rounded.FilterList, contentDescription = null, tint = BrandMain)
            }
        }
        if (state.loading) item { LinearLoadingCard(stringResource(R.string.loading_messages)) }
        state.error?.let { item { FscmErrorCard(it) { viewModel.refresh(session) } } }
        items(todos) { todo -> MessageRow(todo.title, todo.description.ifBlank { todo.content }, stringResource(R.string.message_todo), todo.status, todo.dueAt.ifBlank { todo.updatedAt }, displayPolicy = displayPolicy) }
        items(overview?.announcements.orEmpty()) { item -> MessageRow(item.title, item.content, stringResource(R.string.message_announcement), "notice", item.publishedAt, displayPolicy = displayPolicy) }
        if (!state.loading && todos.isEmpty() && overview?.announcements.orEmpty().isEmpty()) {
            item { EmptyCard(stringResource(R.string.no_messages)) }
        }
    }
}

@Composable
private fun MessagesPreview(overview: MobileWorkbenchOverview?, displayPolicy: FscmDisplayPolicy) {
    FscmCard(padding = if (displayPolicy.isCompact) 14 else 18) {
        SectionHeader(stringResource(R.string.message_preview), "")
        val todos = overview?.todos.orEmpty().take(3)
        val announcements = overview?.announcements.orEmpty().take(2)
        if (todos.isEmpty() && announcements.isEmpty()) {
            Text(stringResource(R.string.no_messages), color = InkSoft, modifier = Modifier.padding(vertical = 18.dp))
        }
        todos.forEach { MessageRow(it.title, it.description.ifBlank { it.content }, stringResource(R.string.message_todo), it.status, it.updatedAt, compact = true, displayPolicy = displayPolicy) }
        announcements.forEach { MessageRow(it.title, it.content, stringResource(R.string.message_announcement), "notice", it.publishedAt, compact = true, displayPolicy = displayPolicy) }
    }
}

@Composable
private fun MessageRow(
    title: String,
    description: String,
    type: String,
    status: String,
    time: String,
    compact: Boolean = false,
    displayPolicy: FscmDisplayPolicy,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (compact) Color.Transparent else Color.White.copy(alpha = 0.92f))
            .padding(
                horizontal = if (compact) {
                    0.dp
                } else if (displayPolicy.isCompact) {
                    10.dp
                } else {
                    14.dp
                },
                vertical = if (compact) {
                    8.dp
                } else if (displayPolicy.isCompact) {
                    10.dp
                } else {
                    12.dp
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.ifBlank { type }, color = InkStrong, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shortTime(time), color = InkSoft, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(type, BrandMain)
            StatusChip(localizedStatusText(status), if (status in listOf("done", "completed", "notice")) Success else Warning)
        }
        if (description.isNotBlank()) {
            Text(description, color = InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ProfileScreen(
    settingsOnly: Boolean = false,
    onOpenFeature: (FeatureRoute) -> Unit,
    session: SessionState,
    onLogout: () -> Unit,
    onSwitchNamespace: (Long) -> Unit,
    language: AppLanguage,
    onUpdateLanguage: (AppLanguage) -> Unit,
    displayMode: DisplayMode,
    onUpdateDisplayMode: (DisplayMode) -> Unit,
    onCheckForUpdate: () -> Unit,
    terminalFindState: TerminalFindState,
    displayPolicy: FscmDisplayPolicy,
) {
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showDisplayModeDialog by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = displayPolicy.pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(displayPolicy.sectionSpacing()),
    ) {
        if (!settingsOnly) {
            item {
                FscmCard(padding = displayPolicy.cardPadding(), containerColor = BrandDeep) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(if (displayPolicy.isCompact) 52.dp else 58.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.user?.name?.ifBlank { session.user.username } ?: stringResource(R.string.profile_fallback_user), color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                            Text(session.effectiveRole.ifBlank { stringResource(R.string.profile_mobile_role) }, color = Color.White.copy(alpha = 0.72f))
                        }
                    }
                }
            }
            item {
                FscmCard(padding = displayPolicy.cardPadding()) {
                    Text(stringResource(R.string.profile_workspace), color = InkStrong, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    session.namespaces.forEach { namespace ->
                        NamespaceRow(namespace, selected = namespace.id == session.currentNamespace?.id, onSwitchNamespace)
                    }
                }
            }
            item {
                FscmCard(modifier = Modifier.clickable { onOpenFeature(FeatureRoute.AppSettings) }, padding = displayPolicy.cardPadding()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_settings_title), Modifier.weight(1f), color = InkStrong, fontWeight = FontWeight.Bold)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = InkSoft)
                    }
                }
            }
        }
        if (settingsOnly) {
            item {
                FscmCard(padding = displayPolicy.cardPadding()) {
                    TextButton(onClick = { onOpenFeature(FeatureRoute.PdaSettings) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feature_pda_settings))
                    }
                    TextButton(onClick = { onOpenFeature(FeatureRoute.EdgeNodes) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feature_edge_nodes))
                    }
                }
            }
            item {
                FscmCard(modifier = Modifier.clickable { showLanguageDialog = true }, padding = displayPolicy.cardPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_language), color = InkStrong, fontWeight = FontWeight.Black)
                            Text(languageLabel(language), color = InkSoft, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.settings_language),
                            tint = InkSoft,
                        )
                    }
                }
            }
            item {
                FscmCard(modifier = Modifier.clickable { showDisplayModeDialog = true }, padding = displayPolicy.cardPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_display_mode), color = InkStrong, fontWeight = FontWeight.Black)
                            Text(displayModeLabel(displayMode), color = InkSoft, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.settings_display_mode),
                            tint = InkSoft,
                        )
                    }
                }
            }
            item {
                TerminalFindSettingsCard(terminalFindState, displayPolicy)
            }
            item {
                FscmCard(modifier = Modifier.clickable(onClick = onCheckForUpdate), padding = displayPolicy.cardPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_update), color = InkStrong, fontWeight = FontWeight.Black)
                            Text(
                                stringResource(
                                    R.string.app_update_current_version,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                ),
                                color = InkSoft,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.app_update), tint = BrandMain)
                    }
                }
            }
        }
        if (!settingsOnly) {
            item {
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                    border = BorderStroke(1.dp, Danger.copy(alpha = 0.32f)),
                ) {
                    Text(stringResource(R.string.profile_logout))
                }
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    LanguageOption(
                        label = stringResource(R.string.language_default),
                        selected = language == AppLanguage.System,
                    ) {
                        onUpdateLanguage(AppLanguage.System)
                        showLanguageDialog = false
                    }
                    LanguageOption(
                        label = stringResource(R.string.language_zh_hans),
                        selected = language == AppLanguage.SimplifiedChinese,
                    ) {
                        onUpdateLanguage(AppLanguage.SimplifiedChinese)
                        showLanguageDialog = false
                    }
                    LanguageOption(
                        label = stringResource(R.string.language_en),
                        selected = language == AppLanguage.English,
                    ) {
                        onUpdateLanguage(AppLanguage.English)
                        showLanguageDialog = false
                    }
                    LanguageOption(
                        label = stringResource(R.string.language_th),
                        selected = language == AppLanguage.Thai,
                    ) {
                        onUpdateLanguage(AppLanguage.Thai)
                        showLanguageDialog = false
                    }
                    LanguageOption(
                        label = stringResource(R.string.language_my),
                        selected = language == AppLanguage.Burmese,
                    ) {
                        onUpdateLanguage(AppLanguage.Burmese)
                        showLanguageDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.back))
                }
            },
        )
    }

    if (showDisplayModeDialog) {
        AlertDialog(
            onDismissRequest = { showDisplayModeDialog = false },
            title = { Text(stringResource(R.string.settings_display_mode)) },
            text = {
                Column {
                    DisplayModeOption(
                        label = stringResource(R.string.display_mode_full),
                        selected = displayMode == DisplayMode.FULL,
                    ) {
                        onUpdateDisplayMode(DisplayMode.FULL)
                        showDisplayModeDialog = false
                    }
                    DisplayModeOption(
                        label = stringResource(R.string.display_mode_compact),
                        selected = displayMode == DisplayMode.COMPACT,
                    ) {
                        onUpdateDisplayMode(DisplayMode.COMPACT)
                        showDisplayModeDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDisplayModeDialog = false }) {
                    Text(stringResource(R.string.back))
                }
            },
        )
    }
}

@Composable
private fun TerminalFindSettingsCard(
    state: TerminalFindState,
    displayPolicy: FscmDisplayPolicy,
) {
    val context = LocalContext.current
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val notificationReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val batteryReady = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    val dndReady = notificationManager.isNotificationPolicyAccessGranted
    val statusText = when (state.status) {
        TerminalFindConnectionStatus.Online -> stringResource(R.string.terminal_find_status_online, state.nodeName)
        TerminalFindConnectionStatus.Ringing -> stringResource(R.string.terminal_find_status_ringing)
        TerminalFindConnectionStatus.Connecting -> stringResource(R.string.terminal_find_status_connecting)
        TerminalFindConnectionStatus.Reconnecting -> stringResource(R.string.terminal_find_status_reconnecting)
        TerminalFindConnectionStatus.Waiting -> stringResource(R.string.edge_waiting)
        TerminalFindConnectionStatus.Stopped -> stringResource(R.string.terminal_find_status_stopped)
    }
    FscmCard(padding = displayPolicy.cardPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.terminal_find_title), color = InkStrong, fontWeight = FontWeight.Black)
                Text(statusText, color = InkSoft, style = MaterialTheme.typography.bodySmall)
                if (state.lastError.isNotBlank()) {
                    Text(state.lastError, color = Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
            StatusChip(
                stringResource(
                    if (
                        state.status == TerminalFindConnectionStatus.Online ||
                        state.status == TerminalFindConnectionStatus.Ringing
                    ) {
                        R.string.terminal_find_ready
                    } else {
                        R.string.terminal_find_not_ready
                    },
                ),
                if (
                    state.status == TerminalFindConnectionStatus.Online ||
                    state.status == TerminalFindConnectionStatus.Ringing
                ) {
                    Success
                } else {
                    Warning
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { TerminalFindService.testAlarm(context) },
                label = { Text(stringResource(R.string.terminal_find_test)) },
                leadingIcon = { Icon(Icons.Rounded.VolumeUp, contentDescription = null) },
            )
            if (!batteryReady) {
                AssistChip(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    label = { Text(stringResource(R.string.terminal_find_allow_background)) },
                )
            }
        }
        if (!notificationReady || !dndReady) {
            TextButton(
                onClick = {
                    val action = if (!dndReady) {
                        Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                    } else {
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    }
                    context.startActivity(
                        Intent(action).apply { putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) },
                    )
                },
            ) {
                Text(stringResource(R.string.terminal_find_complete_permissions))
            }
        }
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String = stringResource(language.labelRes)

@Composable
private fun displayModeLabel(displayMode: DisplayMode): String = when (displayMode) {
    DisplayMode.FULL -> stringResource(R.string.display_mode_full)
    DisplayMode.COMPACT -> stringResource(R.string.display_mode_compact)
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, color = InkStrong)
    }
}

@Composable
private fun DisplayModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    LanguageOption(label = label, selected = selected, onClick = onClick)
}

@Composable
private fun NamespaceRow(
    namespace: NamespaceProfile,
    selected: Boolean,
    onSwitchNamespace: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrandSoft else Color.Transparent)
            .clickable { onSwitchNamespace(namespace.id) }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Workspaces, contentDescription = null, tint = if (selected) BrandMain else InkSoft)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(namespace.name, color = InkStrong, fontWeight = FontWeight.Bold)
            Text(namespace.role.ifBlank { namespace.roleCode }, color = InkSoft, style = MaterialTheme.typography.labelMedium)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Success)
    }
}

@Composable
private fun ProfitScreen(
    session: SessionState,
    viewModel: ProfitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(session.permissions) { viewModel.load(session.permissions) }
    val data = state.data
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageTitle(stringResource(R.string.profit_dashboard), stringResource(R.string.profit_description))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("profit" to stringResource(R.string.profit_metric_profit), "gmv" to "GMV", "orders" to stringResource(R.string.profit_metric_orders)).forEach { (metric, label) ->
                    AssistChip(
                        onClick = { viewModel.setMetric(metric, session.permissions) },
                        label = { Text(label) },
                        leadingIcon = if (state.metric == metric) ({ Icon(Icons.Rounded.CheckCircle, contentDescription = null, Modifier.size(16.dp)) }) else null,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (state.metric == metric) BrandSoft else Color.White,
                            labelColor = if (state.metric == metric) BrandDeep else InkStrong,
                            leadingIconContentColor = BrandMain,
                        ),
                        border = BorderStroke(1.dp, if (state.metric == metric) BrandMain.copy(alpha = 0.30f) else StrokeSoft),
                    )
                }
            }
        }
        if (state.loading) item { LinearLoadingCard(stringResource(R.string.profit_loading)) }
        state.error?.let { item { FscmErrorCard(it) { viewModel.load(session.permissions) } } }
        if (data != null) {
            item { ProfitSummaryCards(data) }
            item { ShopRanking(data.shops) }
        }
    }
}

@Composable
private fun ProfitSummaryCards(data: ProfitOverview) {
    val summary = data.summary
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.height(210.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        item { MetricCard(stringResource(R.string.profit_total_gmv), formatAmount(summary.totalGmv), BrandMain) }
        item { MetricCard(stringResource(R.string.profit_total_profit), formatAmount(summary.totalProfit), if (summary.totalProfit >= 0) Success else Danger) }
        item { MetricCard(stringResource(R.string.profit_margin), formatPercent(summary.avgProfitMargin), BrandDeep) }
        item { MetricCard(stringResource(R.string.profit_order_count), formatCount(summary.totalOrders), Warning) }
    }
}

@Composable
private fun MetricCard(title: String, value: String, color: Color) {
    FscmCard {
        Text(title, color = InkSoft)
        Spacer(Modifier.height(8.dp))
        Text(value, color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun ShopRanking(shops: List<ShopOverview>) {
    FscmCard {
        SectionHeader(stringResource(R.string.profit_shop_ranking), stringResource(R.string.profit_shop_count, shops.size))
        shops.sortedByDescending { it.profit }.take(10).forEachIndexed { index, shop ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (index < 3) BrandMain else Color(0xFFE6F4FF)), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = if (index < 3) Color.White else BrandMain, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(shop.shopName, color = InkStrong, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.profit_shop_line, shop.platform, formatAmount(shop.grossSales), shop.totalOrders), color = InkSoft, style = MaterialTheme.typography.labelMedium)
                }
                Text(formatAmount(shop.profit), color = if (shop.profit >= 0) Success else Danger, fontWeight = FontWeight.Black)
            }
        }
        if (shops.isEmpty()) Text(stringResource(R.string.profit_no_data), color = InkSoft, modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun ProductManagementScreen(
    productId: Long? = null,
    onOpenSkuDetail: ((String) -> Unit)? = null,
    viewModel: ProductManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(productId) {
        if (productId != null) {
            viewModel.initForProduct(productId)
        }
    }
    DisposableEffect(productId, state.isBroadcastMode, lifecycleOwner) {
        if (productId == null && state.isBroadcastMode) {
            viewModel.registerScanner(lifecycleOwner)
        }
        onDispose {
            if (productId == null && state.isBroadcastMode) {
                viewModel.unregisterScanner()
            }
        }
    }
    var showCamera by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) showCamera = true
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(
                    onBarcodeDetected = { barcode ->
                        showCamera = false
                        viewModel.scanSku(barcode)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FscmTopBar(title = stringResource(R.string.product_scan_find_sku), onBack = { showCamera = false })
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        FscmCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            padding = 12,
        ) {
            val isLocalCatalog = state.catalogSource == CatalogSource.Edge
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                StatusChip(
                    text = if (isLocalCatalog) "本地" else "远程",
                    color = when {
                        isLocalCatalog && state.catalogStale -> Warning
                        isLocalCatalog -> Success
                        else -> BrandMain
                    },
                    containerColor = when {
                        isLocalCatalog && state.catalogStale -> Warning.copy(alpha = 0.12f)
                        isLocalCatalog -> Success.copy(alpha = 0.12f)
                        else -> BrandSoft
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            if (productId == null) {
                if (state.isBroadcastMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.product_broadcast_search)) },
                            placeholder = { Text(stringResource(R.string.product_manual_search)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.load(reset = true) }),
                        )
                        ExactSearchButton(state.exactSearch, viewModel::toggleExactSearch)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PageTop)
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = InkSoft,
                            modifier = Modifier.size(20.dp),
                        )
                        val clipboardManager = LocalClipboardManager.current
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = { viewModel.setQuery(it) },
                            placeholder = {
                                Text(
                                    stringResource(R.string.product_manual_search),
                                    color = InkSoft,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = BrandMain,
                            ),
                            trailingIcon = {
                                Row {
                                    if (state.query.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.setQuery("") }) {
                                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.product_clear), tint = InkSoft, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    IconButton(onClick = {
                                        clipboardManager.getText()?.text?.let { viewModel.setQuery(it) }
                                    }) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.product_paste), tint = BrandMain, modifier = Modifier.size(18.dp))
                                    }
                                    ExactSearchButton(state.exactSearch, viewModel::toggleExactSearch)
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.load(reset = true) }),
                        )
                        IconButton(onClick = {
                            if (PdaPermissions.hasCameraPermission(context)) {
                                showCamera = true
                            } else {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        }) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.scan_receiving), tint = BrandMain)
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.product_related_skus),
                    style = MaterialTheme.typography.titleSmall,
                    color = InkStrong,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading && state.skus.isEmpty()) {
                item { LinearLoadingCard(stringResource(R.string.product_loading)) }
            }
            state.error?.let { item { FscmErrorCard(it, onRetry = { viewModel.load(reset = true) }) } }
            state.printMessage?.let { message ->
                item { StatusChip(message.resolve(), Success, Success.copy(alpha = 0.12f)) }
            }

            items(state.skus) { sku ->
                SkuItem(
                    sku = sku,
                    printing = state.printingSkuId == sku.id,
                    directPrint = state.isBroadcastMode,
                    onClick = {
                        if (state.isBroadcastMode) {
                            viewModel.printSku(sku)
                        } else {
                            onOpenSkuDetail?.invoke(
                                sku.id.takeIf { it > 0 }?.toString() ?: sku.code,
                            )
                        }
                    },
                )
            }

            if (state.hasMore && !state.loading && state.skus.isNotEmpty()) {
                item {
                    LaunchedEffect(Unit) { viewModel.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (!state.loading && !state.hasMore && state.skus.isEmpty() && state.error == null) {
                item { EmptyCard(stringResource(R.string.product_no_match)) }
            }
        }
    }
}

@Composable
private fun ExactSearchButton(exactSearch: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "精确查找",
            tint = if (exactSearch) BrandMain else InkSoft,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun catalogImageModel(
    media: CatalogMediaRequest?,
    fallbackUrl: String,
    sizePx: Int,
): Any {
    val context = LocalContext.current
    return remember(media, fallbackUrl, sizePx) {
        if (media == null) {
            fallbackUrl
        } else {
            ImageRequest.Builder(context)
                .data(media.url)
                .headers(
                    Headers.Builder()
                        .add(media.headerName, media.headerValue)
                        .build(),
                )
                .memoryCacheKey(media.cacheKey)
                .diskCacheKey(media.cacheKey)
                .size(sizePx)
                .build()
        }
    }
}

@Composable
private fun CatalogAsyncImage(
    media: CatalogMediaRequest?,
    fallbackUrl: String,
    sizePx: Int,
    modifier: Modifier,
) {
    var useCenter by remember(media) { mutableStateOf(false) }
    val centerUrl = catalogMediaFallbackUrl(media, fallbackUrl)
    AsyncImage(
        model = if (useCenter) centerUrl else catalogImageModel(media, centerUrl, sizePx),
        contentDescription = null,
        onError = {
            if (media != null && centerUrl.isNotBlank()) useCenter = true
        },
        modifier = modifier,
    )
}

internal fun catalogMediaFallbackUrl(media: CatalogMediaRequest?, fallbackUrl: String): String =
    media?.centralUrl?.ifBlank { fallbackUrl } ?: fallbackUrl

@Composable
private fun SkuItem(
    sku: Sku,
    printing: Boolean = false,
    directPrint: Boolean = false,
    onClick: () -> Unit,
) {
    val skuName = sku.name.ifBlank { sku.product?.name ?: stringResource(R.string.product_unnamed_sku) }

    FscmCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !printing, onClick = onClick),
        padding = 14,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CatalogAsyncImage(
                media = sku.catalogMedia,
                fallbackUrl = sku.thumbnailUrl.ifBlank { sku.imageUrl }
                    .ifBlank { sku.product?.thumbnailUrl ?: "" },
                sizePx = 160,
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceMuted),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = skuName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = InkStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sku.code, style = MaterialTheme.typography.labelLarge, color = BrandMain, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(4.dp))
                    if (printing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else if (directPrint) {
                        Icon(Icons.Rounded.Print, contentDescription = stringResource(R.string.product_print_label), tint = BrandMain.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = BrandMain.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    listOfNotNull(sku.spec.takeIf { it.isNotBlank() }, sku.color.takeIf { it.isNotBlank() }, sku.size.takeIf { it.isNotBlank() }).joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                )
            }
        }
    }
}

@Composable
private fun ReceivingListScreen(
    onOpenDetail: (Long) -> Unit,
    viewModel: ReceivingListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.loading) item { LinearLoadingCard(stringResource(R.string.receiving_loading_list)) }
        state.error?.let { item { FscmErrorCard(it, viewModel::load) } }
        items(state.data.orEmpty()) { session ->
            ReceivingSessionCard(session, onOpenDetail)
        }
        if (!state.loading && state.data.orEmpty().isEmpty()) item { EmptyCard(stringResource(R.string.receiving_no_receipts)) }
    }
}

@Composable
private fun ReceivingSessionCard(session: ReceivingSession, onOpenDetail: (Long) -> Unit) {
    FscmCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail(session.id) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.code, color = InkStrong, fontWeight = FontWeight.Black)
                Text(session.containerNo.ifBlank { session.warehouseName }, color = InkSoft)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(localizedStatusText(session.status), if (session.status == "completed") Success else Warning)
                if (session.localCached) {
                    StatusChip(stringResource(R.string.receiving_local), BrandMain)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat(stringResource(R.string.receiving_pending), session.pendingBoxCount.takeIf { it > 0 } ?: session.expectedBoxCount, modifier = Modifier.weight(1f))
            MiniStat(stringResource(R.string.receiving_received), session.talliedBoxCount.takeIf { it > 0 } ?: session.scannedBoxCount, modifier = Modifier.weight(1f))
            MiniStat(stringResource(R.string.receiving_exception), session.exceptionBoxCount, color = Danger, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReceivingDetailScreen(
    sessionId: Long,
    onOpenCamera: () -> Unit = {},
    viewModel: ReceivingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scanCode by rememberSaveable(sessionId) { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusRequester = remember { FocusRequester() }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) showCamera = true
    }

    fun submitBarcode(rawCode: String = scanCode) {
        val normalized = rawCode.normalizeBarcode()
        if (normalized.isBlank() || state.submitting) return
        scanCode = ""
        viewModel.scanAndReceive(sessionId, normalized)
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize()) {
                BarcodeScanner(
                    onBarcodeDetected = { barcode ->
                        showCamera = false
                        submitBarcode(barcode)
                    },
                    modifier = Modifier.fillMaxSize(),
                    showSelectionDialogForMultiple = true,
                    preferredBarcodeMatcher = viewModel::isKnownBoxLabelCode,
                    preferredBarcodeWaitMillis = 800L,
                    noPreferredBarcodeTitle = stringResource(R.string.box_label_scan_not_detected),
                    noPreferredBarcodePrompt = stringResource(R.string.box_label_scan_retry_prompt),
                    retryScanLabel = stringResource(R.string.box_label_scan_retry),
                )
                FscmTopBar(title = stringResource(R.string.scan_receiving), onBack = { showCamera = false })
            }
        }
    }
    LaunchedEffect(sessionId) {
        viewModel.load(sessionId)
    }
    DisposableEffect(sessionId, lifecycleOwner) {
        viewModel.registerScanner(lifecycleOwner) { rawCode ->
            submitBarcode(rawCode)
        }
        onDispose { viewModel.unregisterScanner() }
    }
    val session = state.session
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.error?.let { item { FscmErrorCard(it) { viewModel.load(sessionId) } } }
        if (session != null) {
            item { CrossOrderReceivingPanel(viewModel.crossOrder) }
            item {
                FscmCard {
                    SectionHeader(stringResource(R.string.receiving_scan_label), stringResource(R.string.receiving_scanner_or_manual))
                    OutlinedTextField(
                        value = scanCode,
                        onValueChange = { value ->
                            scanCode = value.normalizeBarcode()
                            if (value.hasBarcodeTerminator()) {
                                submitBarcode(value)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (
                                    event.type == KeyEventType.KeyUp &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    submitBarcode()
                                    true
                                } else {
                                    false
                                }
                            },
                        label = { Text(stringResource(R.string.receiving_label_code)) },
                        leadingIcon = {
                            IconButton(onClick = {
                                if (PdaPermissions.hasCameraPermission(context)) {
                                    showCamera = true
                                } else {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            }) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.business_camera_scan))
                            }
                        },
                        supportingText = { Text(stringResource(R.string.receiving_input_support)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitBarcode() }),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { submitBarcode() },
                        enabled = scanCode.isNotBlank() && !state.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (state.submitting) R.string.receiving_processing else R.string.receiving_identify_label))
                    }
                }
            }
            item {
                FscmProgressOverview(
                    title = stringResource(R.string.business_receiving_progress_overview),
                    totalLabel = stringResource(R.string.business_metric_total_boxes),
                    pendingLabel = stringResource(R.string.business_metric_pending),
                    completedLabel = stringResource(R.string.business_metric_received),
                    exceptionLabel = stringResource(R.string.business_metric_exception),
                    metrics = warehouseReceivingProgress(session),
                )
            }
            item {
                FscmCard(containerColor = BrandSoft) {
                    SectionHeader(stringResource(R.string.business_recent_scans), "")
                    session.scanItems.take(10).forEach { item ->
                        ScanItemRow(item, onVoid = { viewModel.voidScan(sessionId, item) })
                    }
                    if (session.scanItems.isEmpty()) Text(stringResource(R.string.receiving_no_scan_records), color = InkSoft, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        } else if (state.loading) {
            item { LinearLoadingCard(stringResource(R.string.receiving_loading_detail)) }
        }
    }
}

@Composable
private fun ScanItemRow(item: ReceivingScanItem, onVoid: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.boxNo.ifBlank { item.labelCode.ifBlank { item.boxUid } }, color = InkStrong, fontWeight = FontWeight.Bold)
            if (item.rawCode.isNotBlank() && !item.rawCode.equals(item.labelCode, ignoreCase = true)) {
                Text(item.rawCode, color = BrandDeep, style = MaterialTheme.typography.labelSmall)
            }
            Text(stringResource(R.string.receiving_pieces_per_box, item.skuCode.ifBlank { "-" }, item.boxQty.takeIf { it > 0 } ?: item.qtyPerCarton), color = InkSoft, style = MaterialTheme.typography.labelMedium)
        }
        StatusChip(localizedStatusText(item.status), if (item.status == "normal") Success else Warning)
        TextButton(onClick = onVoid) { Text(stringResource(R.string.common_cancel)) }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: Int,
    color: Color = BrandMain,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), color = color, fontWeight = FontWeight.Black)
        Text(label, color = InkSoft, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = InkStrong, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = InkSoft, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String, onTrailing: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(
                trailing,
                color = BrandMain,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier,
            )
        }
    }
}

@Composable
private fun FscmCard(
    modifier: Modifier = Modifier,
    padding: Int = 18,
    containerColor: Color = SurfaceRaised,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        border = BorderStroke(1.dp, if (containerColor == SurfaceRaised) StrokeSoft.copy(alpha = 0.78f) else Color.Transparent),
    ) {
        Column(Modifier.padding(padding.dp), content = content)
    }
}

private fun FscmDisplayPolicy.pageContentPadding(): PaddingValues {
    val horizontal = if (isCompact) 12.dp else 18.dp
    return PaddingValues(start = horizontal, top = horizontal, end = horizontal, bottom = if (isCompact) 18.dp else 24.dp)
}

private fun FscmDisplayPolicy.sectionSpacing() = if (isCompact) 12.dp else 16.dp

private fun FscmDisplayPolicy.cardPadding() = if (isCompact) 14 else 18

@StringRes
internal fun localAppTitleResource(key: String): Int? = when (key) {
    "todos" -> R.string.local_app_todos_title
    "notes" -> R.string.local_app_notes_title
    "tickets" -> R.string.local_app_tickets_title
    "documents" -> R.string.local_app_documents_title
    "receiving" -> R.string.local_app_receiving_title
    "inbound_management" -> R.string.local_app_inbound_management_title
    "central_receiving" -> R.string.local_app_central_receiving_title
    "profit" -> R.string.local_app_profit_title
    "product_management" -> R.string.local_app_product_management_title
    "product_counting" -> R.string.local_app_product_counting_title
    "box_labels", "carton_management" -> R.string.local_app_box_labels_title
    "supplier_orders" -> R.string.local_app_supplier_orders_title
    "purchase_approvals" -> R.string.local_app_purchase_approvals_title
    "manufacturer_payment_bills" -> R.string.local_app_manufacturer_payment_bills_title
    "label_print", "label_printing", "print_label", "custom_label_print" -> R.string.local_app_label_print_title
    "edge_nodes", "edge_node", "print_settings", "printer_settings", "label_print_settings", "edge_print_settings" -> R.string.local_app_edge_nodes_title
    "pda_settings", "pda_scanner_settings", "scanner_settings" -> R.string.local_app_pda_settings_title
    else -> null
}

@StringRes
private fun localAppDescriptionResource(key: String): Int? = when (key) {
    "todos" -> R.string.local_app_todos_description
    "notes" -> R.string.local_app_notes_description
    "tickets" -> R.string.local_app_tickets_description
    "documents" -> R.string.local_app_documents_description
    "receiving" -> R.string.local_app_receiving_description
    "inbound_management" -> R.string.local_app_inbound_management_description
    "central_receiving" -> R.string.local_app_central_receiving_description
    "profit" -> R.string.local_app_profit_description
    "product_management" -> R.string.local_app_product_management_description
    "product_counting" -> R.string.local_app_product_counting_description
    "box_labels", "carton_management" -> R.string.local_app_box_labels_description
    "supplier_orders" -> R.string.local_app_supplier_orders_description
    "purchase_approvals" -> R.string.local_app_purchase_approvals_description
    "manufacturer_payment_bills" -> R.string.local_app_manufacturer_payment_bills_description
    "label_print", "label_printing", "print_label", "custom_label_print" -> R.string.local_app_label_print_description
    "edge_nodes", "edge_node", "print_settings", "printer_settings", "label_print_settings", "edge_print_settings" -> R.string.local_app_edge_nodes_description
    "pda_settings", "pda_scanner_settings", "scanner_settings" -> R.string.local_app_pda_settings_description
    else -> null
}

@Composable
private fun localizedAppTitle(app: MobileAppEntry): String =
    localAppTitleResource(app.key)?.let { stringResource(it) } ?: app.title

private fun localizedAppTitle(context: Context, app: MobileAppEntry): String =
    localAppTitleResource(app.key)?.let(context::getString) ?: app.title

@Composable
private fun localizedAppDescription(app: MobileAppEntry): String =
    if (app.localFallback) {
        localAppDescriptionResource(app.key)?.let { stringResource(it) } ?: app.description
    } else {
        app.description
    }

@Composable
internal fun StatusChip(text: String, color: Color) {
    StatusChip(text = text, color = color, containerColor = color.copy(alpha = 0.10f))
}

@Composable
private fun StatusChip(text: String, color: Color, containerColor: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun LinearLoadingCard(text: String) {
    FscmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(text, color = InkSoft)
        }
    }
}

@Composable
internal fun FscmErrorCard(message: UiText, onRetry: () -> Unit) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.error_title), color = Danger, fontWeight = FontWeight.Bold)
        Text(message.resolve(), color = InkSoft, modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.common_retry))
        }
    }
}

@Composable
private fun CameraScannerScreen(onBack: () -> Unit, onResult: (String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        BarcodeScanner(
            onBarcodeDetected = onResult,
            modifier = Modifier.fillMaxSize(),
        )
        FscmTopBar(title = stringResource(R.string.scan_identify), onBack = onBack)
    }
}

@Composable
private fun EmptyCard(text: String) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            color = InkSoft,
            modifier = Modifier
                .padding(vertical = 20.dp)
                .align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun GlobalSearchResultsOverlay(
    state: LoadState<GlobalSearchResponse>,
    onClose: () -> Unit,
    onNavigate: (GlobalSearchHit) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onClose)
            .padding(horizontal = 20.dp, vertical = 80.dp),
    ) {
        FscmCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            padding = 0,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.search_results), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandMain)
                }
            } else if (state.error != null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(state.error.resolve(), color = Danger)
                }
            } else {
                val hits = state.data?.hits.orEmpty()
                if (hits.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.search_no_results), color = InkSoft)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                    ) {
                        items(hits) { hit ->
                            SearchHitRow(hit, onClick = { onNavigate(hit) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: GlobalSearchHit, onClick: () -> Unit) {
    val localApp = hit.takeIf { it.source == "local" }
        ?.let { MobileAppEntry(key = it.targetCode, title = it.title, description = it.subtitle, localFallback = true) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when (hit.businessType.toBusinessTypeCode()) {
                        BusinessTypeCode.SkuDetail -> BrandMain.copy(alpha = 0.1f)
                        BusinessTypeCode.BoxLabelDetail -> Color(0xFF722ED1).copy(alpha = 0.1f)
                        else -> BrandSoft
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (hit.businessType.toBusinessTypeCode()) {
                    BusinessTypeCode.SkuDetail -> Icons.Rounded.QrCode
                    BusinessTypeCode.BoxLabelDetail -> Icons.Rounded.Workspaces
                    else -> Icons.Rounded.Search
                },
                contentDescription = null,
                tint = when (hit.businessType.toBusinessTypeCode()) {
                    BusinessTypeCode.SkuDetail -> BrandMain
                    BusinessTypeCode.BoxLabelDetail -> Color(0xFF722ED1)
                    else -> BrandMain
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(localApp?.let { localizedAppTitle(it) } ?: hit.title, color = InkStrong, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = localApp?.let { localizedAppDescription(it) } ?: hit.subtitle
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = InkSoft, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (hit.status.isNotBlank()) {
            StatusChip(hit.status, BrandMain)
        }
    }
}

private fun openAppEntry(
    context: Context,
    app: MobileAppEntry,
    onOpenFeature: (FeatureRoute) -> Unit,
    showUnavailable: (String) -> Unit,
) = when (app.key) {
    "profit" -> {
        onOpenFeature(FeatureRoute.Profit)
        true
    }
    "supplier_orders" -> {
        onOpenFeature(FeatureRoute.SupplierOrders)
        true
    }
    "manufacturer_payment_bills" -> {
        onOpenFeature(FeatureRoute.ManufacturerPaymentBills)
        true
    }
    "receiving" -> {
        onOpenFeature(FeatureRoute.ReceivingList)
        true
    }
    "central_receiving" -> {
        onOpenFeature(FeatureRoute.CentralReceivingList)
        true
    }
    "inbound_management" -> {
        onOpenFeature(FeatureRoute.InboundManagement)
        true
    }
    "product_management" -> {
        onOpenFeature(FeatureRoute.ProductManagement)
        true
    }
    "product_counting" -> {
        onOpenFeature(FeatureRoute.ProductCountingList)
        true
    }
    "box_labels", "carton_management" -> {
        onOpenFeature(FeatureRoute.CartonManagement)
        true
    }
    "pda_settings", "pda_scanner_settings", "scanner_settings" -> {
        onOpenFeature(FeatureRoute.PdaSettings)
        true
    }
    "edge_nodes", "edge_node", "print_settings", "printer_settings", "label_print_settings", "edge_print_settings" -> {
        onOpenFeature(FeatureRoute.EdgeNodes)
        true
    }
    "label_print", "label_printing", "print_label", "custom_label_print" -> {
        onOpenFeature(FeatureRoute.LabelPrint)
        true
    }
    else -> {
        showUnavailable(context.getString(R.string.native_page_unavailable, localizedAppTitle(context, app)))
        false
    }
}

private fun openSearchDestination(
    context: Context,
    destination: SearchDestination,
    onOpenFeature: (FeatureRoute) -> Unit,
    showUnavailable: (String) -> Unit,
) {
    when (destination) {
        is SearchDestination.LocalApp -> openAppEntry(context, destination.entry, onOpenFeature, showUnavailable)
        is SearchDestination.Business -> {
            val hit = destination.hit
            when (hit.businessType.toBusinessTypeCode()) {
                BusinessTypeCode.SkuDetail -> {
                    val target = hit.targetId.ifBlank { hit.targetCode }
                    if (target.isNotBlank()) {
                        onOpenFeature(FeatureRoute.SkuDetail(target))
                    } else {
                        showUnavailable(context.getString(R.string.search_missing_sku))
                    }
                }
                BusinessTypeCode.BoxLabelDetail -> {
                    if (hit.targetCode.isNotBlank()) {
                        onOpenFeature(FeatureRoute.BoxLabelDetail(hit.targetCode))
                    } else {
                        showUnavailable(context.getString(R.string.search_missing_box_label))
                    }
                }
                BusinessTypeCode.LocalApp,
                BusinessTypeCode.Unknown,
                -> showUnavailable(context.getString(R.string.business_type_unsupported, hit.businessType))
            }
        }
    }
}

private fun parseColor(value: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()

@Composable
private fun formatOfflineExpiry(expiresAt: Long): String {
    val remainingMs = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
    val remainingDays = (remainingMs / (24L * 60L * 60L * 1000L)).coerceAtLeast(1)
    return stringResource(R.string.relative_days_after, remainingDays)
}

private fun String.normalizeBarcode(): String =
    trim().trimEnd('\r', '\n', '\t')

private fun String.hasBarcodeTerminator(): Boolean =
    any { it == '\r' || it == '\n' || it == '\t' }

@Composable
private fun SkuSearchDetailScreen(
    idOrCode: String,
    viewModel: SkuDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val printState by viewModel.printState.collectAsStateWithLifecycle()
    LaunchedEffect(idOrCode) { viewModel.load(idOrCode) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.loading) item { LinearLoadingCard(stringResource(R.string.device_loading_sku)) }
        state.error?.let { error -> item { FscmErrorCard(error, onRetry = { viewModel.load(idOrCode) }) } }
        state.data?.let { sku -> item { SkuDetailCard(sku, onPrint = viewModel::openPrintDialog) } }
    }
    if (printState.visible) {
        LabelPrintDialog(
            state = printState,
            onDismiss = viewModel::closePrintDialog,
            onSelectOption = viewModel::selectPrintOption,
            onChangeCopies = viewModel::changePrintCopies,
            onPrefixChange = viewModel::setPrintPrefix,
            onSubmit = viewModel::submitPrint,
        )
    }
}

@Composable
private fun LabelPrintDialog(
    state: LabelPrintUiState,
    onDismiss: () -> Unit,
    onSelectOption: (String) -> Unit,
    onChangeCopies: (Int) -> Unit,
    onPrefixChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_print_sku_label), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.loadingNodes -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.device_loading_nodes), color = InkSoft)
                        }
                    }
                    state.options.isNotEmpty() -> {
                        Text(stringResource(R.string.device_print_node), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        state.options.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.submitting) { onSelectOption(option.selectionKey) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.selectedOptionKey == option.selectionKey,
                                    onClick = { onSelectOption(option.selectionKey) },
                                    enabled = !state.submitting,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(option.node.nodeName, fontWeight = FontWeight.Bold, color = InkStrong)
                                    Text(
                                        "${option.template.displayName()} · ${option.template.printerName}",
                                        color = InkSoft,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                StatusChip(
                                    stringResource(if (option.localReachable) R.string.device_local_reachable else R.string.device_remote_queue),
                                    if (option.localReachable) Success else BrandMain,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.device_print_copies), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { onChangeCopies(-1) }, enabled = state.copies > 1 && !state.submitting) {
                                Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.device_decrease_copies))
                            }
                            Text(state.copies.toString(), modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            IconButton(onClick = { onChangeCopies(1) }, enabled = state.copies < 100 && !state.submitting) {
                                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.device_increase_copies))
                            }
                        }
                        OutlinedTextField(
                            value = state.labelPrefix,
                            onValueChange = onPrefixChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.device_label_prefix)) },
                            supportingText = { Text(stringResource(R.string.device_print_content, "${state.labelPrefix}${state.skuCode}")) },
                            singleLine = true,
                        )
                    }
                }
                state.message?.let { Text(it.resolve(), color = Success, fontWeight = FontWeight.Bold) }
                state.error?.let { Text(it.resolve(), color = Danger) }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = state.selectedOptionKey != null && !state.loadingNodes && !state.submitting,
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.device_print))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.submitting) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun BoxLabelSearchDetailScreen(
    code: String,
    onNavigate: (FeatureRoute) -> Unit,
    viewModel: CartonManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BoxLabelManagementContent(state, viewModel, onNavigate, initialCode = code)
}

@Composable
private fun CartonManagementScreen(
    onNavigate: (FeatureRoute) -> Unit,
    viewModel: CartonManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BoxLabelManagementContent(state, viewModel, onNavigate)
}

@Composable
internal fun BoxMarkPrintAction(
    label: BoxLabel,
    loadingOptions: Boolean,
    submitting: Boolean,
    printMessage: UiText?,
    printError: UiText?,
    onPrint: () -> Unit,
) {
    val printAvailability = label.boxMarkPrintAvailability()
    val printEnabled = printAvailability == BoxMarkPrintAvailability.Ready
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onPrint,
        enabled = printEnabled && !submitting && !loadingOptions,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        if (submitting || loadingOptions) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (loadingOptions) R.string.device_loading_box_mark_nodes else R.string.device_print_box_mark))
    }
    if (!printEnabled) {
        Text(
            stringResource(
                printAvailability.messageResource(),
            ),
            modifier = Modifier.padding(top = 6.dp),
            color = Warning,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    printMessage?.let {
        Text(it.resolve(), modifier = Modifier.padding(top = 8.dp), color = Success, style = MaterialTheme.typography.bodySmall)
    }
    printError?.let {
        Text(it.resolve(), modifier = Modifier.padding(top = 8.dp), color = Danger, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BoxLabelDetailCard(
    label: BoxLabel,
    onOpenSupplierOrder: ((Long) -> Unit)? = null,
    onOpenCentralReceipt: ((Long) -> Unit)? = null,
    onOpenReceivingSession: ((Long) -> Unit)? = null,
    onPrintBoxMark: (() -> Unit)? = null,
    printSubmitting: Boolean = false,
    printMessage: UiText? = null,
    printError: UiText? = null,
) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        val clipboardManager = LocalClipboardManager.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.labelCode.ifBlank { stringResource(R.string.device_unassigned_code) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = InkStrong)
                Text(stringResource(R.string.device_unique_id, label.boxUid), style = MaterialTheme.typography.labelMedium, color = InkSoft)
            }
            StatusChip(
                if (label.localFallback) stringResource(R.string.device_offline_parsed) else localizedStatusText(label.status),
                if (label.localFallback) {
                    Warning
                } else if (label.status == "received") {
                    Success
                } else {
                    BrandMain
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        CopyableDetailItem(stringResource(R.string.receiving_label_code), label.labelCode)
        if (label.jifengBoxNo.isNotBlank()) {
            CopyableDetailItem(stringResource(R.string.device_jifeng_box_label), label.jifengBoxNo, clipboardManager)
            CopyableDetailItem(
                stringResource(R.string.device_supplier_box_label),
                label.supplierLabelCode.ifBlank { label.labelCode },
                clipboardManager,
            )
        }
        if (label.boxNo.isNotBlank() && label.boxNo != label.labelCode) {
            DetailItem(stringResource(R.string.device_box_number), label.boxNo)
        }
        DetailItem(stringResource(R.string.device_product), "[${label.displayProductCode}] ${label.productName}")
        if (label.skuItems.isEmpty()) {
            CopyableDetailItem("SKU", label.displaySkuCode, clipboardManager)
            DetailItem(stringResource(R.string.device_quantity), label.qtyPerCarton.takeIf { it > 0 }?.toString().orEmpty())
        } else {
            label.skuItems.forEach { item ->
                CopyableDetailItem("SKU", item.skuCode, clipboardManager)
                DetailItem(stringResource(R.string.device_quantity), item.qtyPerBox.toString())
            }
        }
        DetailItem(stringResource(R.string.device_case_quantity), stringResource(R.string.device_case_quantity_value, label.caseSpecName, label.qtyPerCarton))
        DetailItem(stringResource(R.string.device_planned_actual), stringResource(R.string.device_planned_actual_value, label.plannedBoxQty, label.actualBoxQty))

        if (label.warehouseCode.isNotBlank() || label.locationCode.isNotBlank()) {
            DetailItem(stringResource(R.string.device_location), "${label.warehouseCode} / ${label.locationCode}")
        }

        if (label.warnings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            label.warnings.forEach { warning ->
                Text("⚠️ $warning", color = Danger, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (label.localFallback && label.resolvedVia == "jifeng_mapping") {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.device_box_label_mapping_offline_warning),
                color = Danger,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        onPrintBoxMark?.let { print ->
            val printAvailability = label.boxMarkPrintAvailability()
            val printEnabled = printAvailability == BoxMarkPrintAvailability.Ready
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = print,
                enabled = printEnabled && !printSubmitting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (printSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (printSubmitting) R.string.device_loading_box_mark_nodes else R.string.device_print_box_mark))
            }
            if (!printEnabled) {
                Text(
                    stringResource(
                        printAvailability.messageResource(),
                    ),
                    modifier = Modifier.padding(top = 6.dp),
                    color = Warning,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            printMessage?.let {
                Text(it.resolve(), modifier = Modifier.padding(top = 8.dp), color = Success, style = MaterialTheme.typography.bodySmall)
            }
            printError?.let {
                Text(it.resolve(), modifier = Modifier.padding(top = 8.dp), color = Danger, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.device_related_documents), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = InkStrong)
        Spacer(Modifier.height(8.dp))

        DocumentRefRow(
            stringResource(R.string.device_supplier_order),
            label.supplierOrder,
            label.supplierOrder?.id?.takeIf { it > 0 }?.let { id -> onOpenSupplierOrder?.let { open -> { open(id) } } },
        )
        DocumentRefRow(stringResource(R.string.device_purchase_order), label.purchaseOrder)
        DocumentRefRow(
            stringResource(R.string.device_receiving_order),
            label.receivingSession,
            label.receivingSession?.id?.takeIf { it > 0 }?.let { id -> onOpenReceivingSession?.let { open -> { open(id) } } },
        )
        DocumentRefRow(stringResource(R.string.device_receipt), label.receivingRecord)
        val centralRef = label.centralReceipt ?: label.consolidationOrder
        DocumentRefRow(
            stringResource(R.string.device_central_order),
            centralRef,
            centralRef?.id?.takeIf { it > 0 }?.let { id -> onOpenCentralReceipt?.let { open -> { open(id) } } },
        )
    }
}

@StringRes
private fun BoxMarkPrintAvailability.messageResource(): Int = when (this) {
    BoxMarkPrintAvailability.OfflineFallback -> R.string.device_box_mark_offline_disabled
    BoxMarkPrintAvailability.NotPrintable -> R.string.device_box_mark_not_printable
    BoxMarkPrintAvailability.SnapshotMissing -> R.string.device_box_mark_snapshot_missing
    BoxMarkPrintAvailability.Ready -> error("Printable box mark does not have a disabled message")
}

@Composable
internal fun BoxMarkPrintDialog(
    options: List<com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeOption>,
    selectedOptionKey: String?,
    copies: Int,
    submitting: Boolean,
    onSelectOption: (String) -> Unit,
    onCopiesChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    label: BoxLabel? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_select_box_mark_node)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                label?.let {
                    Text(it.labelCode, fontWeight = FontWeight.Bold, color = InkStrong)
                    Text(it.skuItems.joinToString(" / ") { sku -> "${sku.skuCode} × ${sku.qtyPerBox}" })
                    Text("计划箱内数量 ${it.plannedBoxQty} · ${it.dimensionText()}")
                    Spacer(Modifier.height(12.dp))
                }
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !submitting) { onSelectOption(option.selectionKey) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.selectionKey == selectedOptionKey,
                            onClick = { onSelectOption(option.selectionKey) },
                            enabled = !submitting,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(option.node.nodeName.ifBlank { option.node.nodeId }, fontWeight = FontWeight.Bold, color = InkStrong)
                            Text(
                                "${option.template.displayName()} · ${option.template.printerName}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSoft,
                            )
                        }
                        StatusChip(
                            stringResource(if (option.localReachable) R.string.device_local_print else R.string.device_remote_queue),
                            if (option.localReachable) Success else BrandMain,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.device_print_copies), color = InkStrong, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onCopiesChange(-1) }, enabled = copies > 1 && !submitting) {
                            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.device_decrease_copies))
                        }
                        Text(copies.toString(), modifier = Modifier.width(36.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onCopiesChange(1) }, enabled = copies < 100 && !submitting) {
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.device_increase_copies))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selectedOptionKey != null && !submitting) {
                Text(stringResource(R.string.device_print))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(80.dp), color = InkSoft, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = InkStrong, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CopyableDetailItem(
    label: String,
    value: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager = LocalClipboardManager.current,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(80.dp), color = InkSoft, style = MaterialTheme.typography.bodyMedium)
        Text(
            value.ifBlank { "-" },
            color = InkStrong,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { clipboardManager.setText(AnnotatedString(value)) },
            enabled = value.isNotBlank(),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.common_copy), tint = BrandMain)
        }
    }
}

@Composable
private fun DocumentRefRow(
    label: String,
    ref: BoxLabelDocumentRef?,
    onClick: (() -> Unit)? = null,
) {
    if (ref == null || ref.code.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 6.dp),
    ) {
        Text(label, modifier = Modifier.width(80.dp), color = InkSoft, style = MaterialTheme.typography.labelSmall)
        Text(ref.code, color = BrandMain, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusChip(localizedStatusText(ref.status), InkSoft)
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.device_view_detail, label),
                tint = BrandMain,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PdaModeScreen(
    viewModel: PdaModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showCamera by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) showCamera = true
    }

    fun doResolve(code: String) {
        val normalized = code.normalizeBarcode()
        if (normalized.isBlank()) return
        viewModel.resolve(normalized)
    }

    DisposableEffect(lifecycleOwner) {
        viewModel.registerScanner(lifecycleOwner) { rawCode ->
            doResolve(rawCode)
        }
        onDispose { viewModel.unregisterScanner() }
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(
                    onBarcodeDetected = { barcode ->
                        showCamera = false
                        doResolve(barcode)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FscmTopBar(title = stringResource(R.string.scan_identify), onBack = { showCamera = false })
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky Header for Status/Instructions
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandSoft.copy(alpha = 0.5f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    tint = BrandMain,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(if (state.loading) R.string.device_scanning else R.string.feature_pda_scan_mode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandDeep,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (PdaPermissions.hasCameraPermission(context)) {
                        showCamera = true
                    } else {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                }) {
                    Text(stringResource(R.string.device_use_camera))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.query.isNotBlank()) {
                item {
                    FscmCard(modifier = Modifier.fillMaxWidth(), containerColor = BrandSoft.copy(alpha = 0.55f)) {
                        SectionHeader(stringResource(R.string.business_recent_scans), "")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.query,
                            color = BrandDeep,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }

            if (state.loading && state.result == null) {
                item { LinearLoadingCard(stringResource(R.string.loading)) }
            }

            state.error?.let { item { FscmErrorCard(it, onRetry = { state.query.takeIf { q -> q.isNotBlank() }?.let(::doResolve) }) } }

            state.result?.let { result ->
                item {
                    Column {
                        Text(
                            stringResource(R.string.device_scan_result, state.query),
                            style = MaterialTheme.typography.labelMedium,
                            color = InkSoft,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                        )
                        when (result) {
                            is Sku -> SkuDetailCard(result)
                            is BoxLabel -> BoxLabelDetailCard(result)
                            else -> Text(stringResource(R.string.device_unknown_result), color = Danger)
                        }
                    }
                }
            }

            if (state.result == null && !state.loading && state.error == null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.QrCode,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = StrokeSoft,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.device_waiting_scan), color = InkSoft)
                            Text(
                                stringResource(R.string.device_scan_instruction),
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSoft.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelPrintScreen(
    viewModel: LabelPrintAppViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val intentMode = scannerState.effectiveMode == ScannerInputMode.IntentBroadcast
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val selectedOption = state.options.firstOrNull { it.selectionKey == state.selectedOptionKey }
    var fieldValue by remember { mutableStateOf(TextFieldValue()) }
    var showCamera by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> showCamera = granted }

    DisposableEffect(lifecycleOwner) {
        viewModel.registerScanner(lifecycleOwner)
        onDispose { viewModel.unregisterScanner() }
    }
    LaunchedEffect(state.content, intentMode) {
        if (fieldValue.text != state.content) {
            fieldValue = TextFieldValue(
                text = state.content,
                selection = TextRange(0, state.content.length),
            )
        }
    }
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                BarcodeScanner(
                    onBarcodeDetected = { barcode ->
                        showCamera = false
                        viewModel.updateContent(barcode.normalizeBarcode())
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FscmTopBar(title = stringResource(R.string.device_scan_label_content), onBack = { showCamera = false })
            }
        }
    }

    if (state.confirmationVisible && selectedOption != null) {
        LabelPrintConfirmationDialog(
            option = selectedOption,
            content = state.content,
            copies = state.copies,
            submitting = state.submitting,
            error = state.error,
            onCopiesChange = viewModel::changeCopies,
            onConfirm = viewModel::submit,
            onDismiss = viewModel::dismissConfirmation,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FscmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device_label_content), modifier = Modifier.weight(1f), color = InkStrong, fontWeight = FontWeight.Black)
                    StatusChip(stringResource(if (intentMode) R.string.device_broadcast_mode else R.string.device_keyboard_mode), if (intentMode) Success else Warning)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { value ->
                        val normalized = value.text.normalizeBarcode().take(512)
                        fieldValue = if (value.text.hasBarcodeTerminator()) {
                            TextFieldValue(normalized, selection = TextRange(0, normalized.length))
                        } else {
                            value.copy(
                                text = normalized,
                                selection = TextRange(value.selection.end.coerceIn(0, normalized.length)),
                            )
                        }
                        viewModel.updateContent(normalized)
                    },
                    modifier = Modifier
                        .fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.device_enter_scan_value)) },
                    leadingIcon = { Icon(Icons.Rounded.QrCode, contentDescription = null, tint = BrandMain) },
                    trailingIcon = {
                        Row {
                            if (state.content.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateContent("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.device_clear_content))
                                }
                            }
                            IconButton(onClick = {
                                if (PdaPermissions.hasCameraPermission(context)) {
                                    showCamera = true
                                } else {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            }) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = stringResource(R.string.business_camera_scan))
                            }
                        }
                    },
                    supportingText = { Text("${state.content.length}/512") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
                            keyboardController?.hide()
                        },
                    ),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.device_available_templates), modifier = Modifier.weight(1f), color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = viewModel::loadOptions, enabled = !state.loadingOptions) {
                    if (state.loadingOptions) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.device_refresh_templates))
                    }
                }
            }
        }
        item {
            val rowCount = labelPrintTemplateGridRows(state.options.size)
            val tileHeight = 148.dp
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(if (rowCount == 0) 0.dp else tileHeight * rowCount + 8.dp * (rowCount - 1)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
            ) {
                items(state.options, key = { it.selectionKey }) { option ->
                    LabelPrintTemplateTile(
                        option = option,
                        selected = state.selectedOptionKey == option.selectionKey,
                        enabled = !state.submitting,
                        modifier = Modifier.height(tileHeight),
                        onClick = { viewModel.selectOption(option.selectionKey) },
                    )
                }
            }
        }

        state.message?.let { message ->
            item { Text(message.resolve(), color = Success, fontWeight = FontWeight.Bold) }
        }
        if (!state.confirmationVisible) {
            state.error?.let { error ->
                item { Text(error.resolve(), color = Danger) }
            }
        }
    }
}

@Composable
private fun LabelPrintTemplateTile(
    option: EdgePrintNodeOption,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) BrandSoft else SurfaceRaised),
        border = BorderStroke(1.dp, if (selected) BrandMain else StrokeSoft),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(BrandMain),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Print, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(
                option.template.displayName(),
                color = InkStrong,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${option.template.widthMM} × ${option.template.heightMM} mm · ${stringResource(labelLayoutResource(option.template.layoutStyle))}",
                color = InkSoft,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.node.nodeName,
                    modifier = Modifier.weight(1f),
                    color = InkSoft,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (option.localReachable) R.string.device_local_print else R.string.device_remote_queue),
                    color = if (option.localReachable) Success else BrandMain,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LabelPrintConfirmationDialog(
    option: EdgePrintNodeOption,
    content: String,
    copies: Int,
    submitting: Boolean,
    error: UiText?,
    onCopiesChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.device_confirm_label_print), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(option.template.displayName(), color = InkStrong, fontWeight = FontWeight.Bold)
                Text("${option.node.nodeName} · ${option.template.printerName}", color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(labelLayoutResource(option.template.layoutStyle)), color = InkSoft)
                Text(content, color = InkStrong, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device_print_copies), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onCopiesChange(-1) }, enabled = copies > 1 && !submitting) {
                        Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.device_decrease_copies))
                    }
                    Text(copies.toString(), modifier = Modifier.width(36.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Black)
                    IconButton(onClick = { onCopiesChange(1) }, enabled = copies < 100 && !submitting) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.device_increase_copies))
                    }
                }
                error?.let { Text(it.resolve(), color = Danger) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !submitting) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.device_dispatch_print))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@StringRes
private fun labelLayoutResource(layoutStyle: String): Int = when (layoutStyle) {
    "qr_left_text_right" -> R.string.device_label_layout_horizontal
    "location_code_quad_qr" -> R.string.device_label_layout_location
    else -> R.string.device_label_layout_stacked
}

private fun EdgePrintTemplate.displayName(): String {
    val templateName = name.ifBlank { code }
    return if (number.isBlank()) templateName else "$number · $templateName"
}

internal fun labelPrintTemplateGridRows(templateCount: Int): Int = (templateCount.coerceAtLeast(0) + 1) / 2

@Composable
private fun EdgeNodesScreen(
    onOpenDetail: (Long) -> Unit,
    viewModel: EdgeNodesViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) { viewModel.observeConnectionScope() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelDiscovery() }
    }
    LaunchedEffect(state.discoveryCompleted) {
        if (state.discoveryCompleted > 0) TerminalFindService.start(context)
    }
    val nodes = state.nodes.sortedByDescending { it.node.id == state.defaultEdgePrintNodeId }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_edge_service_nodes), color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.edge_nodes_summary, nodes.size, nodes.count { it.localReachable }), color = InkSoft, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = viewModel::load, enabled = !state.loading) {
                    if (state.loading && nodes.isNotEmpty()) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.device_refresh_nodes))
                    }
                }
            }
        }
        item {
            FscmCard {
                Text(stringResource(R.string.edge_auto_policy), color = InkSoft, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { if (state.discovering) viewModel.cancelDiscovery() else viewModel.discover() }, enabled = !state.loading) {
                    Text(stringResource(if (state.discovering) R.string.edge_cancel_discovery else R.string.edge_discover))
                }
                if (state.discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.discoveryMessage?.let { Text(it, color = InkSoft) }
            }
        }
        if (state.loading && nodes.isEmpty()) item { LinearLoadingCard(stringResource(R.string.device_loading_edge_nodes)) }
        state.error?.let { error -> item { FscmErrorCard(error, onRetry = viewModel::load) } }
        items(nodes, key = { it.identityKey }) { status ->
            EdgeNodeListCard(
                status = status,
                isDefault = status.node.id == state.defaultEdgePrintNodeId,
                onSetDefault = { viewModel.setDefaultNode(status.node.id) },
                onOpenDetail = { onOpenDetail(status.node.id) },
            )
        }
        if (!state.loading && state.error == null && nodes.isEmpty()) item { EmptyCard(stringResource(R.string.device_no_edge_nodes)) }
    }
}

@Composable
private fun EdgeNodeListCard(
    status: EdgePrintNodeStatus,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val node = status.node
    FscmCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDetail)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(node.nodeName.ifBlank { node.nodeId }, color = InkStrong, fontWeight = FontWeight.Black)
                Text(node.nodeId, color = InkSoft, style = MaterialTheme.typography.labelMedium)
            }
            StatusChip(
                stringResource(if (status.localReachable) R.string.device_local_reachable else R.string.device_remote_available),
                if (status.localReachable) Success else BrandMain,
            )
            if (isDefault) StatusChip(stringResource(R.string.device_default_node), BrandMain)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = InkSoft)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.device_printer_template_summary, node.printInventory.printers.count { it.status == "ready" }, node.printInventory.printers.size, node.printInventory.templates.count { it.available }, node.printInventory.templates.size), color = InkSoft, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onSetDefault, enabled = !isDefault) { Text(stringResource(R.string.device_set_default_node)) }
    }
}

@Composable
private fun EdgeNodeDetailScreen(
    nodeId: Long,
    viewModel: EdgeNodesViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) { viewModel.observeConnectionScope() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.nodes.firstOrNull { it.node.id == nodeId }
    if (status == null) {
        EmptyCard(stringResource(R.string.device_no_edge_nodes))
        return
    }
    val node = status.node
    val isDefault = node.id == state.defaultEdgePrintNodeId
    val skuTemplates = state.quickPrintOptions.filter { it.node.id == node.id }
    val boxTemplates = state.boxMarkOptions.filter { it.node.id == node.id }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FscmCard(modifier = Modifier.fillMaxWidth()) {
                Text(node.nodeName.ifBlank { node.nodeId }, color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                DiagnosticText(stringResource(R.string.device_node_id), node.nodeId)
                DiagnosticText(stringResource(R.string.device_lan_address), node.lanBaseUrl.ifBlank { stringResource(R.string.common_unavailable) })
                DiagnosticText(stringResource(R.string.device_connection_type), stringResource(if (status.localReachable) R.string.device_local_connection else R.string.device_backend_queue))
                TextButton(onClick = { viewModel.setDefaultNode(node.id) }, enabled = !isDefault) { Text(stringResource(R.string.device_set_default_node)) }
            }
        }
        if (isDefault) {
            item {
                FscmCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.device_sku_quick_print), color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = state.skuLabelPrefix, onValueChange = viewModel::updateSkuLabelPrefix, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.device_label_prefix)) }, singleLine = true)
                    skuTemplates.forEach { option ->
                        Row(Modifier.fillMaxWidth().clickable { viewModel.selectSkuTemplate(node.id, option.template.code) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = option.template.code == state.defaultSkuPrintTemplateCode, onClick = { viewModel.selectSkuTemplate(node.id, option.template.code) })
                            Text(option.template.displayName(), color = InkStrong, modifier = Modifier.weight(1f))
                        }
                    }
                    if (skuTemplates.isEmpty()) Text(stringResource(R.string.device_no_print_nodes), color = Warning)
                }
            }
            item {
                FscmCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.device_box_mark_quick_print), color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                    boxTemplates.forEach { option ->
                        Row(Modifier.fillMaxWidth().clickable { viewModel.selectBoxMarkTemplate(node.id, option.template.code) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = option.template.code == state.defaultBoxMarkPrintTemplateCode, onClick = { viewModel.selectBoxMarkTemplate(node.id, option.template.code) })
                            Text(option.template.displayName(), color = InkStrong, modifier = Modifier.weight(1f))
                        }
                    }
                    if (boxTemplates.isEmpty()) Text(stringResource(R.string.device_no_box_mark_nodes), color = Warning)
                }
            }
        } else {
            item { Text(stringResource(R.string.device_node_detail_default_hint), color = InkSoft) }
        }
    }
}

@Composable
private fun PdaSettingsScreen(
    viewModel: PdaSettingsViewModel = hiltViewModel(),
) {
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val audioDiagnostics by viewModel.audioDiagnostics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val diagnosticsTitle = stringResource(R.string.device_pda_diagnostics)
    val diagnosticsCopiedMessage = stringResource(R.string.device_diagnostics_copied)

    LaunchedEffect(Unit) { viewModel.refreshAudioDiagnostics() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAudioDiagnostics()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            FscmCard(modifier = Modifier.fillMaxWidth(), containerColor = BrandSoft.copy(alpha = 0.5f)) {
                SectionHeader(stringResource(R.string.feature_pda_settings), stringResource(R.string.device_global_mode))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.device_settings_description),
                    color = InkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            ScannerInputModeCard(
                scannerState = scannerState,
                onModeChange = viewModel::setInputMode,
            )
        }
        item {
            ScannerDiagnosticsCard(
                scannerState = scannerState,
                audioDiagnostics = audioDiagnostics,
                onClear = viewModel::clearScannerDiagnostics,
                onTestSuccess = viewModel::testSuccessFeedback,
                onTestFailure = viewModel::testFailureFeedback,
                onCopy = {
                    clipboardManager.setText(
                        AnnotatedString(scannerState.toDiagnosticText(diagnosticsTitle, audioDiagnostics)),
                    )
                    Toast.makeText(context, diagnosticsCopiedMessage, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun ScannerInputModeCard(
    scannerState: ScannerRuntimeState,
    onModeChange: (ScannerInputMode) -> Unit,
) {
    val configuredMode = scannerState.configuredMode
    val effectiveMode = scannerState.effectiveMode
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.device_app_scan_mode),
            trailing = when (configuredMode) {
                ScannerInputMode.Keyboard -> stringResource(R.string.device_keyboard_set)
                ScannerInputMode.IntentBroadcast -> stringResource(R.string.device_broadcast_set)
                null -> stringResource(R.string.device_automatic)
            },
        )
        Spacer(Modifier.height(10.dp))
        DiagnosticText(
            label = stringResource(R.string.device_current_mode),
            value = when (effectiveMode) {
                ScannerInputMode.Keyboard -> stringResource(R.string.device_keyboard_input_mode)
                ScannerInputMode.IntentBroadcast -> stringResource(R.string.device_intent_broadcast_mode)
            },
        )
        DiagnosticText(
            label = stringResource(R.string.device_auto_detection),
            value = stringResource(if (configuredMode == null) R.string.device_keyboard_fallback else R.string.device_manual_mode_fixed),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScannerModeButton(
                title = stringResource(R.string.device_keyboard_input),
                selected = effectiveMode == ScannerInputMode.Keyboard,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(ScannerInputMode.Keyboard) },
            )
            ScannerModeButton(
                title = stringResource(R.string.device_intent_broadcast),
                selected = effectiveMode == ScannerInputMode.IntentBroadcast,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(ScannerInputMode.IntentBroadcast) },
            )
        }
    }
}

@Composable
private fun ScannerModeButton(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandMain),
        ) {
            Text(title)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(title)
        }
    }
}

@Composable
private fun ScannerDiagnosticsCard(
    scannerState: ScannerRuntimeState,
    audioDiagnostics: ScanAudioDiagnostics,
    onClear: () -> Unit,
    onTestSuccess: () -> Unit,
    onTestFailure: () -> Unit,
    onCopy: () -> Unit,
) {
    val event = scannerState.lastEvent
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.device_pda_diagnostics), stringResource(if (scannerState.registered) R.string.device_listening else R.string.device_not_listening))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(
                stringResource(if (scannerState.registered) R.string.device_receiver_enabled else R.string.device_waiting_resume),
                if (scannerState.registered) Success else Warning,
            )
            StatusChip(stringResource(R.string.device_action_count, scannerState.profile.actions.size), BrandMain)
            StatusChip(stringResource(R.string.device_extra_count, scannerState.profile.extraKeys.size), Teal)
            StatusChip(
                when (scannerState.effectiveMode) {
                    ScannerInputMode.Keyboard -> stringResource(R.string.device_keyboard_mode)
                    ScannerInputMode.IntentBroadcast -> stringResource(R.string.device_broadcast_mode)
                },
                if (scannerState.effectiveMode == ScannerInputMode.IntentBroadcast) Success else Warning,
            )
        }
        Spacer(Modifier.height(12.dp))
        DiagnosticText(stringResource(R.string.device_recent_action), event?.action?.ifBlank { "-" } ?: stringResource(R.string.device_no_broadcast))
        DiagnosticText(stringResource(R.string.device_recent_extra), event?.extraKey ?: "-")
        DiagnosticText(stringResource(R.string.device_extraction_status), stringResource(if (event?.extracted == true) R.string.device_barcode_extracted else R.string.device_barcode_not_extracted))
        DiagnosticText(stringResource(R.string.device_recent_barcode), event?.normalizedValue?.ifBlank { "-" } ?: "-")
        DiagnosticText(stringResource(R.string.device_received_time), event?.receivedAtMillis?.let(::formatScannerTime) ?: "-")
        DiagnosticText(
            stringResource(R.string.device_back_suppressed),
            scannerState.suppressedBackCount.toString(),
        )
        DiagnosticText(
            stringResource(R.string.device_recent_suppression),
            scannerState.lastBackSuppressedAtMillis.takeIf { it > 0 }?.let(::formatScannerTime) ?: "-",
        )
        scannerState.lastError?.let { error ->
            DiagnosticText(stringResource(R.string.device_recent_error), error)
        }
        Spacer(Modifier.height(12.dp))
        DiagnosticText(
            label = stringResource(R.string.device_listen_action),
            value = scannerState.profile.actions.take(6).joinToString("\n") +
                if (scannerState.profile.actions.size > 6) "\n..." else "",
        )
        DiagnosticText(
            label = stringResource(R.string.device_extract_extra),
            value = scannerState.profile.extraKeys.take(6).joinToString("\n") +
                if (scannerState.profile.extraKeys.size > 6) "\n..." else "",
        )
        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.device_audio_feedback), "")
        DiagnosticText(
            stringResource(R.string.device_audio_media_volume),
            stringResource(R.string.device_audio_volume_value, audioDiagnostics.mediaVolume, audioDiagnostics.mediaVolumeMax),
        )
        DiagnosticText(
            stringResource(R.string.device_audio_notification_volume),
            stringResource(R.string.device_audio_volume_value, audioDiagnostics.notificationVolume, audioDiagnostics.notificationVolumeMax),
        )
        DiagnosticText(
            stringResource(R.string.device_audio_ringer_mode),
            stringResource(
                when (audioDiagnostics.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> R.string.device_audio_ringer_silent
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.device_audio_ringer_vibrate
                    else -> R.string.device_audio_ringer_normal
                },
            ),
        )
        StatusChip(
            stringResource(if (audioDiagnostics.soundsReady) R.string.device_audio_ready else R.string.device_audio_loading),
            if (audioDiagnostics.soundsReady) Success else Warning,
        )
        DiagnosticText(
            stringResource(R.string.device_audio_last_playback),
            stringResource(
                when (audioDiagnostics.lastPlayback) {
                    ScanFeedbackPlaybackResult.Loading -> R.string.device_audio_loading
                    ScanFeedbackPlaybackResult.Playing -> R.string.device_audio_playing
                    ScanFeedbackPlaybackResult.Failed -> R.string.device_audio_playback_failed
                },
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onTestSuccess, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Text(stringResource(R.string.device_audio_test_success))
            }
            OutlinedButton(onClick = onTestFailure, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Text(stringResource(R.string.device_audio_test_failure), color = Danger)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCopy,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.device_copy_diagnostics))
            }
            OutlinedButton(
                onClick = onClear,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.device_clear_results))
            }
        }
    }
}

@Composable
private fun DiagnosticText(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.width(86.dp),
            color = InkSoft,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = InkStrong,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun ScannerRuntimeState.toDiagnosticText(
    title: String,
    audioDiagnostics: ScanAudioDiagnostics,
): String {
    val event = lastEvent
    return buildString {
        appendLine(title)
        appendLine("registered=$registered")
        appendLine("configuredMode=${configuredMode?.name.orEmpty()}")
        appendLine("effectiveMode=${effectiveMode.name}")
        appendLine("lastAction=${event?.action.orEmpty()}")
        appendLine("lastExtra=${event?.extraKey.orEmpty()}")
        appendLine("extracted=${event?.extracted ?: false}")
        appendLine("barcode=${event?.normalizedValue.orEmpty()}")
        appendLine("suppressedBackCount=$suppressedBackCount")
        appendLine("lastBackSuppressedAtMillis=$lastBackSuppressedAtMillis")
        appendLine("receivedAt=${event?.receivedAtMillis?.let(::formatScannerTime).orEmpty()}")
        appendLine("lastError=${lastError.orEmpty()}")
        appendLine("actions=${profile.actions.joinToString(";")}")
        appendLine("extras=${profile.extraKeys.joinToString(";")}")
        appendLine("mediaVolume=${audioDiagnostics.mediaVolume}/${audioDiagnostics.mediaVolumeMax}")
        appendLine("notificationVolume=${audioDiagnostics.notificationVolume}/${audioDiagnostics.notificationVolumeMax}")
        appendLine("ringerMode=${audioDiagnostics.ringerMode}")
        appendLine("soundsReady=${audioDiagnostics.soundsReady}")
        appendLine("lastPlayback=${audioDiagnostics.lastPlayback.name}")
    }
}

private fun formatScannerTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

@Composable
private fun SkuDetailCard(sku: Sku, onPrint: (() -> Unit)? = null) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CatalogAsyncImage(
                media = sku.catalogMedia,
                fallbackUrl = sku.thumbnailUrl.ifBlank { sku.imageUrl }
                    .ifBlank { sku.product?.thumbnailUrl ?: "" },
                sizePx = 240,
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceMuted),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(sku.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = BrandMain)
                Text(sku.name.ifBlank { sku.product?.name ?: stringResource(R.string.product_unnamed_sku) }, style = MaterialTheme.typography.bodyMedium, color = InkStrong, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(20.dp))
        DetailItem(stringResource(R.string.device_specification), sku.spec.ifBlank { "-" })
        DetailItem(stringResource(R.string.device_color_size), "${sku.color.ifBlank { "-" }} / ${sku.size.ifBlank { "-" }}")
        DetailItem(stringResource(R.string.device_weight), if (sku.weight > 0) "${sku.weight} kg" else "-")

        sku.product?.let { product ->
            Spacer(Modifier.height(12.dp))
            Divider(color = StrokeSoft, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.device_product_info), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = InkStrong)
            Spacer(Modifier.height(8.dp))
            DetailItem(stringResource(R.string.device_product_name), product.name)
            DetailItem(stringResource(R.string.device_product_code), product.code)
            DetailItem(stringResource(R.string.device_brand), product.brand.ifBlank { "-" })
        }
        if (onPrint != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onPrint, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.product_print_label))
            }
        }
    }
}

@Composable
private fun Divider(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant, thickness: androidx.compose.ui.unit.Dp = 1.dp) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}
