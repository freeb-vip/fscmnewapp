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

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabel
import com.google.samples.apps.nowinandroid.fscm.data.BoxLabelDocumentRef
import com.google.samples.apps.nowinandroid.fscm.data.BusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.EdgePrintNodeStatus
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchHit
import com.google.samples.apps.nowinandroid.fscm.data.GlobalSearchResponse
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.Product
import com.google.samples.apps.nowinandroid.fscm.data.ProfitOverview
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SearchDestination
import com.google.samples.apps.nowinandroid.fscm.data.SessionMode
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.ShopOverview
import com.google.samples.apps.nowinandroid.fscm.data.Sku
import com.google.samples.apps.nowinandroid.fscm.data.formatAmount
import com.google.samples.apps.nowinandroid.fscm.data.formatCount
import com.google.samples.apps.nowinandroid.fscm.data.formatPercent
import com.google.samples.apps.nowinandroid.fscm.data.shortTime
import com.google.samples.apps.nowinandroid.fscm.data.statusText
import com.google.samples.apps.nowinandroid.fscm.data.toBusinessTypeCode
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import com.google.samples.apps.nowinandroid.fscm.device.PdaPermissions
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.device.ScannerRuntimeState
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
) {
    val session by rootViewModel.session.collectAsStateWithLifecycle()
    val bootstrapping by rootViewModel.bootstrapping.collectAsStateWithLifecycle()
    val message by rootViewModel.message.collectAsStateWithLifecycle()
    val globalScanResult by rootViewModel.globalScanResult.collectAsStateWithLifecycle()
    val pendingSyncCount by rootViewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current)
        rootViewModel.clearMessage()
    }

    LaunchedEffect(session.isLoggedIn) {
        if (
            session.isLoggedIn &&
            PdaPermissions.bluetoothRuntimePermissions.isNotEmpty() &&
            !PdaPermissions.hasBluetoothRuntimePermissions(context)
        ) {
            bluetoothPermissionLauncher.launch(PdaPermissions.bluetoothRuntimePermissions)
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

    FscmTheme {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Brush.verticalGradient(listOf(PageTop, PageBottom))),
            ) {
                when {
                    bootstrapping -> LoadingScreen("正在连接 FSCM")
                    !session.isLoggedIn -> LoginScreen(onLogin = rootViewModel::login)
                    else -> MainShell(
                        session = session,
                        pendingSyncCount = pendingSyncCount,
                        onLogout = rootViewModel::logout,
                        onSwitchNamespace = rootViewModel::switchNamespace,
                    )
                }
            }
        }
    }
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
private fun LoginScreen(onLogin: (String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
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
                        Text("四", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("四海环球", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Black)
                    Text("FSCM 移动工作台", color = Color.White.copy(alpha = 0.76f), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                FscmCard(padding = 20) {
                    Text("账号登录", color = InkStrong, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("使用 FSCM 后台账号进入移动端", color = InkSoft, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("用户名") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = fscmTextFieldColors(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = fscmTextFieldColors(),
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { onLogin(username.trim(), password) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = username.isNotBlank() && password.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandMain),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("登录")
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

private sealed interface FeatureRoute : Serializable {
    data object None : FeatureRoute
    data object Profit : FeatureRoute
    data object ReceivingList : FeatureRoute
    data class ReceivingDetail(val id: Long) : FeatureRoute
    data object CameraScanner : FeatureRoute
    data object ProductManagement : FeatureRoute
    data class ProductSkuList(val productId: Long, val productName: String) : FeatureRoute
    data class SkuDetail(val idOrCode: String) : FeatureRoute
    data class BoxLabelDetail(val code: String) : FeatureRoute
    data object CartonManagement : FeatureRoute
    data object PdaMode : FeatureRoute
    data object PdaSettings : FeatureRoute
    data object EdgeNodes : FeatureRoute
    data object LabelPrint : FeatureRoute
}

@Composable
private fun MainShell(
    session: SessionState,
    pendingSyncCount: Int,
    onLogout: () -> Unit,
    onSwitchNamespace: (Long) -> Unit,
) {
    val rootViewModel: FscmRootViewModel = hiltViewModel()
    val workbenchViewModel: WorkbenchViewModel = hiltViewModel()
    val globalScanResult by rootViewModel.globalScanResult.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Workbench) }
    var featureRoute by rememberSaveable { mutableStateOf<FeatureRoute>(FeatureRoute.None) }

    LaunchedEffect(globalScanResult) {
        val barcode = globalScanResult ?: return@LaunchedEffect
        // Handle global scan: if not in any specific feature, default to Carton Management
        if (featureRoute == FeatureRoute.None) {
            selectedTab = MainTab.Workbench
            workbenchViewModel.search(barcode)
        }
        rootViewModel.consumeGlobalScan()
    }

    if (featureRoute !is FeatureRoute.None) {
        FeatureScreen(
            route = featureRoute,
            session = session,
            onBack = { featureRoute = FeatureRoute.None },
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
                )
                MainTab.Apps -> AppsScreen(
                    session = session,
                    onOpenFeature = { featureRoute = it },
                    viewModel = workbenchViewModel,
                )
                MainTab.Messages -> MessagesScreen(
                    session = session,
                    viewModel = workbenchViewModel,
                )
                MainTab.Profile -> ProfileScreen(session, onLogout, onSwitchNamespace)
            }
        }
    }
}

@Composable
private fun FeatureScreen(
    route: FeatureRoute,
    session: SessionState,
    onBack: () -> Unit,
    onNavigate: (FeatureRoute) -> Unit = {},
    onOpenReceivingDetail: (Long) -> Unit,
    onScanResult: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            if (route != FeatureRoute.CameraScanner) {
                FscmTopBar(
                    title = when (route) {
                        FeatureRoute.Profit -> stringResource(R.string.profit_dashboard)
                        FeatureRoute.ReceivingList -> stringResource(R.string.warehouse_receiving)
                        is FeatureRoute.ReceivingDetail -> stringResource(R.string.scan_receiving)
                        FeatureRoute.ProductManagement -> "产品管理"
                        is FeatureRoute.ProductSkuList -> route.productName
                        is FeatureRoute.SkuDetail -> "SKU 详情"
                        is FeatureRoute.BoxLabelDetail -> "箱唛详情"
                        FeatureRoute.CartonManagement -> "箱唛管理"
                        FeatureRoute.PdaMode -> "PDA 扫描模式"
                        FeatureRoute.PdaSettings -> "PDA 设置"
                        FeatureRoute.EdgeNodes -> "边缘节点"
                        FeatureRoute.LabelPrint -> "标签打印"
                        FeatureRoute.None -> ""
                        FeatureRoute.CameraScanner -> ""
                    },
                    onBack = onBack,
                )
            }
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (route) {
                FeatureRoute.Profit -> ProfitScreen(session)
                FeatureRoute.ReceivingList -> ReceivingListScreen(onOpenReceivingDetail)
                is FeatureRoute.ReceivingDetail -> ReceivingDetailScreen(route.id, onOpenCamera = { onScanResult("") })
                FeatureRoute.CameraScanner -> CameraScannerScreen(onBack = onBack, onResult = onScanResult)
                FeatureRoute.ProductManagement -> ProductManagementScreen(
                    onOpenSkuList = { id, name -> onNavigate(FeatureRoute.ProductSkuList(id, name)) },
                    onOpenSkuDetail = { onNavigate(FeatureRoute.SkuDetail(it)) },
                )
                is FeatureRoute.ProductSkuList -> ProductManagementScreen(
                    productId = route.productId,
                    onOpenSkuDetail = { onNavigate(FeatureRoute.SkuDetail(it)) },
                )
                is FeatureRoute.SkuDetail -> SkuSearchDetailScreen(route.idOrCode)
                is FeatureRoute.BoxLabelDetail -> BoxLabelSearchDetailScreen(route.code)
                FeatureRoute.CartonManagement -> CartonManagementScreen()
                FeatureRoute.PdaMode -> PdaModeScreen()
                FeatureRoute.PdaSettings -> PdaSettingsScreen()
                FeatureRoute.EdgeNodes -> EdgeNodesScreen()
                FeatureRoute.LabelPrint -> LabelPrintScreen()
                FeatureRoute.None -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FscmTopBar(title: String, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, color = InkStrong, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
private fun WorkbenchScreen(
    session: SessionState,
    pendingSyncCount: Int,
    onOpenFeature: (FeatureRoute) -> Unit,
    viewModel: WorkbenchViewModel,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    LaunchedEffect(session.user?.id, session.currentNamespace?.id, session.sessionMode) {
        if (session.isLoggedIn) viewModel.load(session)
    }
    LaunchedEffect(viewModel) {
        viewModel.searchNavigation.collect { destination ->
            openSearchDestination(
                destination = destination,
                onOpenFeature = onOpenFeature,
                showUnavailable = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
            )
        }
    }
    val overview = state.data
    val apps = visibleMobileApps(overview, session.permissions, session.sessionMode)
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
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                WorkbenchHero(
                    namespace = session.currentNamespace?.name ?: "当前工作空间",
                    sessionMode = session.sessionMode,
                    pendingSyncCount = pendingSyncCount,
                    offlineExpiresAt = session.offlineExpiresAt,
                    onScan = { onOpenFeature(FeatureRoute.PdaMode) },
                    searchQuery = searchQuery,
                    scannerInputMode = scannerState.effectiveMode,
                    onSearch = { query -> viewModel.search(query) },
                    onQueryChange = { query -> viewModel.updateSearchQuery(query) },
                )
            }
            if (state.loading) item { LinearLoadingCard(if (session.isOffline) "正在读取本地工作台" else "正在加载首页") }
            state.error?.let { item { FscmErrorCard(it, onRetry = { viewModel.refresh(session) }) } }
            item {
                SectionHeader("常用应用", "全部") { onOpenFeature(FeatureRoute.None) }
                AppGrid(apps.take(4), onOpenFeature)
            }
            item {
                MessagesPreview(overview)
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
    namespace: String,
    sessionMode: SessionMode,
    pendingSyncCount: Int,
    offlineExpiresAt: Long,
    onScan: () -> Unit,
    searchQuery: String,
    scannerInputMode: ScannerInputMode,
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
                FscmTopBar(title = "扫码识别", onBack = { showCamera = false })
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(BrandMain, BrandDeep)))
            .padding(18.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("四海环球", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    Text(namespace, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onScan() }) {
                    Icon(Icons.Rounded.QrCode, contentDescription = "PDA 模式", tint = Color.White)
                }
            }
        }
        if (sessionMode == SessionMode.Offline) {
            Spacer(Modifier.height(12.dp))
            Text(
                "待同步 $pendingSyncCount 条 · 离线凭证 ${formatOfflineExpiry(offlineExpiresAt)} 到期",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(18.dp))
        SearchPanel(
            query = searchQuery,
            scannerInputMode = scannerInputMode,
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
    scannerInputMode: ScannerInputMode,
    onScan: () -> Unit,
    onSearch: (String) -> Unit,
    onQueryChange: (String) -> Unit = {},
) {
    val intentMode = scannerInputMode == ScannerInputMode.IntentBroadcast
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var fieldValue by remember { mutableStateOf(TextFieldValue(query, selection = TextRange(0, query.length))) }

    LaunchedEffect(query, intentMode) {
        if (fieldValue.text != query) {
            fieldValue = TextFieldValue(
                text = query,
                selection = if (!intentMode) TextRange(0, query.length) else TextRange(query.length),
            )
        }
    }
    LaunchedEffect(intentMode) {
        if (intentMode) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    fun submitSearch(value: String) {
        val normalized = value.normalizeBarcode()
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
                if (!intentMode) {
                    val normalized = value.text.normalizeBarcode()
                    val cursor = value.selection.end.coerceIn(0, normalized.length)
                    fieldValue = value.copy(text = normalized, selection = TextRange(cursor))
                    onQueryChange(normalized)
                    if (value.text.hasBarcodeTerminator()) {
                        submitSearch(normalized)
                    }
                }
            },
            placeholder = {
                Text(
                    if (intentMode) "等待 PDA 广播扫码" else "搜索单据、SKU，支持扫码枪回车",
                    color = InkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            modifier = Modifier
                .weight(1f),
            readOnly = intentMode,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = BrandMain,
            ),
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = BrandMain, modifier = Modifier.size(20.dp))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { submitSearch(fieldValue.text) }),
        )
        Button(
            onClick = onScan,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandMain),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (intentMode) "摄像头" else "扫码")
        }
    }
}

@Composable
private fun TaskSection(
    overview: MobileWorkbenchOverview?,
    apps: List<MobileAppEntry>,
    onOpenFeature: (FeatureRoute) -> Unit,
) {
    val todoCount = overview?.todos?.count { it.status !in listOf("done", "completed", "finished") } ?: 0
    val taskApps = apps.filter { it.key in setOf("receiving", "central_receiving", "payment_bills", "purchase_approvals", "supplier_orders", "tickets", "todos") }
        .map { app -> app to if (app.key == "todos") todoCount else app.badgeCount }
        .filter { (app, count) -> count > 0 || app.key in setOf("receiving", "central_receiving") }
    if (taskApps.isEmpty()) return
    SectionHeader("今日待处理", "${taskApps.sumOf { it.second }} 项")
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.height(((taskApps.size + 1) / 2 * 126).dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(taskApps) { (app, count) ->
            BusinessCard(app, count, onOpenFeature)
        }
    }
}

@Composable
private fun BusinessCard(
    app: MobileAppEntry,
    count: Int,
    onOpenFeature: (FeatureRoute) -> Unit,
) {
    val context = LocalContext.current
    FscmCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .clickable { openAppEntry(app, onOpenFeature) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
        padding = 14,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app)
            Spacer(Modifier.weight(1f))
            if (count > 0) Badge(count.toString(), Danger)
        }
        Spacer(Modifier.height(10.dp))
        Text(app.title, color = InkStrong, fontWeight = FontWeight.Black, maxLines = 1, style = MaterialTheme.typography.titleSmall)
        Text(app.description.ifBlank { "移动工作台" }, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AppsScreen(
    session: SessionState,
    onOpenFeature: (FeatureRoute) -> Unit,
    viewModel: WorkbenchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(session.user?.id, session.currentNamespace?.id, session.sessionMode) {
        if (session.isLoggedIn) viewModel.load(session)
    }
    val apps = visibleMobileApps(state.data, session.permissions, session.sessionMode)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageTitle("应用", "${apps.size} 个应用按权限展示")
        }
        state.error?.let { item { FscmErrorCard(it) { viewModel.refresh(session) } } }
        item { AppGrid(apps, onOpenFeature) }
    }
}

@Composable
private fun AppGrid(
    apps: List<MobileAppEntry>,
    onOpenFeature: (FeatureRoute) -> Unit,
) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.height((((apps.size + 3) / 4).coerceAtLeast(1) * 112).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(apps) { app ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.88f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.94f))
                    .clickable { openAppEntry(app, onOpenFeature) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    AppIcon(app, size = 48)
                    if (app.badgeCount > 0) {
                        Box(Modifier.align(Alignment.TopEnd)) { Badge(app.badgeCount.toString(), Danger) }
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(app.title, color = InkStrong, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                if (app.description.isNotBlank()) {
                    Text(app.description, color = InkSoft, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MessagesScreen(
    session: SessionState,
    viewModel: WorkbenchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(session.user?.id, session.currentNamespace?.id, session.sessionMode) {
        if (session.isLoggedIn) viewModel.load(session)
    }
    val overview = state.data
    val todos = overview?.todos.orEmpty().filter { it.status !in listOf("done", "completed", "finished") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("消息", style = MaterialTheme.typography.headlineMedium, color = InkStrong, fontWeight = FontWeight.Black)
                    Text("${todos.size + overview?.announcements.orEmpty().size} 条消息与事项", color = InkSoft)
                }
                Icon(Icons.Rounded.FilterList, contentDescription = null, tint = BrandMain)
            }
        }
        if (state.loading) item { LinearLoadingCard("正在加载消息") }
        state.error?.let { item { FscmErrorCard(it) { viewModel.refresh(session) } } }
        items(todos) { todo -> MessageRow(todo.title, todo.description.ifBlank { todo.content }, "待办", todo.status, todo.dueAt.ifBlank { todo.updatedAt }) }
        items(overview?.announcements.orEmpty()) { item -> MessageRow(item.title, item.content, "公告", "notice", item.publishedAt) }
        if (!state.loading && todos.isEmpty() && overview?.announcements.orEmpty().isEmpty()) {
            item { EmptyCard("暂无消息") }
        }
    }
}

@Composable
private fun MessagesPreview(overview: MobileWorkbenchOverview?) {
    FscmCard {
        SectionHeader("消息预览", "")
        val todos = overview?.todos.orEmpty().take(3)
        val announcements = overview?.announcements.orEmpty().take(2)
        if (todos.isEmpty() && announcements.isEmpty()) {
            Text("暂无消息", color = InkSoft, modifier = Modifier.padding(vertical = 18.dp))
        }
        todos.forEach { MessageRow(it.title, it.description.ifBlank { it.content }, "待办", it.status, it.updatedAt, compact = true) }
        announcements.forEach { MessageRow(it.title, it.content, "公告", "notice", it.publishedAt, compact = true) }
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (compact) Color.Transparent else Color.White.copy(alpha = 0.92f))
            .padding(horizontal = if (compact) 0.dp else 14.dp, vertical = if (compact) 8.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.ifBlank { type }, color = InkStrong, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shortTime(time), color = InkSoft, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(type, BrandMain)
            StatusChip(statusText(status), if (status in listOf("done", "completed", "notice")) Success else Warning)
        }
        if (description.isNotBlank()) {
            Text(description, color = InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ProfileScreen(
    session: SessionState,
    onLogout: () -> Unit,
    onSwitchNamespace: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            FscmCard(containerColor = BrandDeep) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.user?.name?.ifBlank { session.user.username } ?: "FSCM 用户", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text(session.effectiveRole.ifBlank { "移动端" }, color = Color.White.copy(alpha = 0.72f))
                    }
                }
            }
        }
        item {
            FscmCard {
                Text("工作空间", color = InkStrong, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                session.namespaces.forEach { namespace ->
                    NamespaceRow(namespace, selected = namespace.id == session.currentNamespace?.id, onSwitchNamespace)
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                border = BorderStroke(1.dp, Danger.copy(alpha = 0.32f)),
            ) {
                Text("退出登录")
            }
        }
    }
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
            PageTitle("利润看板", "按店铺、平台和指标查看经营表现")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("profit" to "利润", "gmv" to "GMV", "orders" to "订单").forEach { (metric, label) ->
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
        if (state.loading) item { LinearLoadingCard("正在加载利润看板") }
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
        item { MetricCard("总 GMV", formatAmount(summary.totalGmv), BrandMain) }
        item { MetricCard("总利润", formatAmount(summary.totalProfit), if (summary.totalProfit >= 0) Success else Danger) }
        item { MetricCard("利润率", formatPercent(summary.avgProfitMargin), BrandDeep) }
        item { MetricCard("订单量", formatCount(summary.totalOrders), Warning) }
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
        SectionHeader("店铺排行", "${shops.size} 家店铺")
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
                    Text("${shop.platform} / GMV ${formatAmount(shop.grossSales)} / 订单 ${shop.totalOrders}", color = InkSoft, style = MaterialTheme.typography.labelMedium)
                }
                Text(formatAmount(shop.profit), color = if (shop.profit >= 0) Success else Danger, fontWeight = FontWeight.Black)
            }
        }
        if (shops.isEmpty()) Text("当前筛选下暂无数据", color = InkSoft, modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun ProductManagementScreen(
    productId: Long? = null,
    onOpenSkuList: ((Long, String) -> Unit)? = null,
    onOpenSkuDetail: ((String) -> Unit)? = null,
    viewModel: ProductManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(productId) {
        if (productId != null) {
            viewModel.initForProduct(productId)
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
                        viewModel.setQuery(barcode)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FscmTopBar(title = "扫码查找 SKU", onBack = { showCamera = false })
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
            if (productId == null) {
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
                                "产品名称、编码、SKU",
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
                                        Icon(Icons.Rounded.Close, contentDescription = "清除", tint = InkSoft, modifier = Modifier.size(18.dp))
                                    }
                                }
                                IconButton(onClick = {
                                    clipboardManager.getText()?.text?.let { viewModel.setQuery(it) }
                                }) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = "粘贴", tint = BrandMain, modifier = Modifier.size(18.dp))
                                }
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
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "扫码", tint = BrandMain)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TabButton("产品", !state.isSkuView, Modifier.weight(1f)) { viewModel.switchView(false) }
                    TabButton("SKU", state.isSkuView, Modifier.weight(1f)) { viewModel.switchView(true) }
                }
            } else {
                Text(
                    "查看产品关联 SKU",
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
            if (state.loading && (state.products.isEmpty() && !state.isSkuView || state.skus.isEmpty() && state.isSkuView)) {
                item { LinearLoadingCard("正在获取数据...") }
            }
            state.error?.let { item { FscmErrorCard(it, onRetry = { viewModel.load(reset = true) }) } }

            if (state.isSkuView) {
                items(state.skus) { sku ->
                    SkuItem(
                        sku = sku,
                        onClick = {
                            onOpenSkuDetail?.invoke(
                                sku.id.takeIf { it > 0 }?.toString() ?: sku.code,
                            )
                        },
                    )
                }
            } else {
                items(state.products) { product ->
                    ProductItem(product, onClick = { onOpenSkuList?.invoke(product.id, product.name) })
                }
            }

            if (state.hasMore && !state.loading && (state.products.isNotEmpty() || state.skus.isNotEmpty())) {
                item {
                    LaunchedEffect(Unit) { viewModel.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (!state.loading && !state.hasMore && (state.products.isEmpty() && !state.isSkuView || state.skus.isEmpty() && state.isSkuView)) {
                item { EmptyCard("未找到匹配的产品或 SKU") }
            }
        }
    }
}

@Composable
private fun TabButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrandMain else SurfaceMuted)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.White else InkSoft, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ProductItem(product: Product, onClick: () -> Unit = {}) {
    FscmCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        padding = 14,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = product.thumbnailUrl.ifBlank { product.imageUrl },
                contentDescription = null,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceMuted),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = InkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(product.code, style = MaterialTheme.typography.labelMedium, color = BrandMain, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (product.brand.isNotBlank()) StatusChip(product.brand, BrandDeep, BrandSoft)
                    if (product.material.isNotBlank()) StatusChip(product.material, InkSoft, SurfaceMuted)
                }
            }
        }
    }
}

@Composable
private fun SkuItem(sku: Sku, onClick: () -> Unit) {
    val skuName = sku.name.ifBlank { sku.product?.name ?: "未命名 SKU" }

    FscmCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        padding = 14,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = sku.thumbnailUrl.ifBlank { sku.imageUrl }.ifBlank { sku.product?.thumbnailUrl ?: "" },
                contentDescription = null,
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
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = BrandMain.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
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
        if (state.loading) item { LinearLoadingCard("正在加载收货单") }
        state.error?.let { item { FscmErrorCard(it, viewModel::load) } }
        items(state.data.orEmpty()) { session ->
            ReceivingSessionCard(session, onOpenDetail)
        }
        if (!state.loading && state.data.orEmpty().isEmpty()) item { EmptyCard("暂无收货单") }
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
            StatusChip(statusText(session.status), if (session.status == "completed") Success else Warning)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("待收", session.pendingBoxCount.takeIf { it > 0 } ?: session.expectedBoxCount, modifier = Modifier.weight(1f))
            MiniStat("已收", session.talliedBoxCount.takeIf { it > 0 } ?: session.scannedBoxCount, modifier = Modifier.weight(1f))
            MiniStat("异常", session.exceptionBoxCount, color = Danger, modifier = Modifier.weight(1f))
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
    var exceptionQty by rememberSaveable { mutableStateOf("0") }
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
        scanCode = normalized
        viewModel.preview(sessionId, normalized)
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
                )
                FscmTopBar(title = "扫码收货", onBack = { showCamera = false })
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
            item {
                ReceivingSessionCard(session) {}
            }
            item {
                FscmCard {
                    SectionHeader("扫描箱唛", "扫描枪/手输")
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
                        label = { Text("箱唛码") },
                        leadingIcon = {
                            IconButton(onClick = {
                                if (PdaPermissions.hasCameraPermission(context)) {
                                    showCamera = true
                                } else {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            }) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = "打开摄像头")
                            }
                        },
                        supportingText = { Text("支持 PDA 扫描头、扫描枪回车后缀和手动输入") },
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
                        Text(if (state.submitting) "处理中..." else "识别箱唛")
                    }
                }
            }
            item {
                FscmCard {
                    SectionHeader("最近扫描", "")
                    session.scanItems.take(10).forEach { item ->
                        ScanItemRow(item, onVoid = { if (item.id > 0) viewModel.voidScan(sessionId, item.id) })
                    }
                    if (session.scanItems.isEmpty()) Text("暂无扫码记录", color = InkSoft, modifier = Modifier.padding(vertical = 12.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.complete(sessionId) },
                        enabled = session.scanItems.any { it.status in listOf("normal", "damaged") } && session.status != "completed",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("完成收货")
                    }
                }
            }
        } else if (state.loading) {
            item { LinearLoadingCard("正在加载收货详情") }
        }
    }
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::clearPreview,
            title = { Text(preview.boxNo.ifBlank { preview.labelCode.ifBlank { "箱唛识别结果" } }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text(preview.skuCode.ifBlank { "-" }, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        StatusChip(if (state.duplicate) "重复" else statusText(preview.status), if (state.duplicate) Danger else Success)
                    }
                    Text(preview.skuName.ifBlank { preview.productName.ifBlank { preview.exceptionReason } }, color = InkSoft)
                    OutlinedTextField(
                        value = exceptionQty,
                        onValueChange = { exceptionQty = it.filter(Char::isDigit) },
                        label = { Text("异常实际数量") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirm(sessionId)
                        scanCode = ""
                    },
                    enabled = !state.duplicate,
                ) { Text("确认收货") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::clearPreview) { Text("取消") }
                    TextButton(
                        onClick = {
                            viewModel.confirm(sessionId, damaged = true, actualQty = exceptionQty.toIntOrNull() ?: 0)
                            scanCode = ""
                        },
                        enabled = !state.duplicate,
                    ) { Text("异常") }
                }
            },
        )
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
            Text("${item.skuCode.ifBlank { "-" }} / ${item.boxQty.takeIf { it > 0 } ?: item.qtyPerCarton} 件/箱", color = InkSoft, style = MaterialTheme.typography.labelMedium)
        }
        StatusChip(statusText(item.status), if (item.status == "normal") Success else Warning)
        TextButton(onClick = onVoid) { Text("取消") }
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

@Composable
private fun AppIcon(app: MobileAppEntry, size: Int = 44) {
    val color = parseColor(app.color) ?: BrandMain
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(app.icon.ifBlank { app.title.take(1) }, color = Color.White, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
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
internal fun FscmErrorCard(message: String, onRetry: () -> Unit) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Text("出错了", color = Danger, fontWeight = FontWeight.Bold)
        Text(message, color = InkSoft, modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重试")
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
        FscmTopBar(title = "扫码识别", onBack = onBack)
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
                Text("搜索结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandMain)
                }
            } else if (state.error != null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(state.error, color = Danger)
                }
            } else {
                val hits = state.data?.hits.orEmpty()
                if (hits.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("未找到相关结果", color = InkSoft)
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
            Text(hit.title, color = InkStrong, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit.subtitle.isNotBlank()) {
                Text(hit.subtitle, color = InkSoft, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (hit.status.isNotBlank()) {
            StatusChip(hit.status, BrandMain)
        }
    }
}

private fun openAppEntry(
    app: MobileAppEntry,
    onOpenFeature: (FeatureRoute) -> Unit,
    showUnavailable: (String) -> Unit,
) {
    when (app.key) {
        "profit" -> onOpenFeature(FeatureRoute.Profit)
        "receiving", "central_receiving" -> onOpenFeature(FeatureRoute.ReceivingList)
        "product_management" -> onOpenFeature(FeatureRoute.ProductManagement)
        "box_labels", "carton_management" -> onOpenFeature(FeatureRoute.CartonManagement)
        "pda_settings", "pda_scanner_settings", "scanner_settings" -> onOpenFeature(FeatureRoute.PdaSettings)
        "edge_nodes", "edge_node", "print_settings", "printer_settings", "label_print_settings", "edge_print_settings" -> onOpenFeature(FeatureRoute.EdgeNodes)
        "label_print", "label_printing", "print_label", "custom_label_print" -> onOpenFeature(FeatureRoute.LabelPrint)
        else -> showUnavailable("${app.title} 暂未开放原生页面")
    }
}

private fun openSearchDestination(
    destination: SearchDestination,
    onOpenFeature: (FeatureRoute) -> Unit,
    showUnavailable: (String) -> Unit,
) {
    when (destination) {
        is SearchDestination.LocalApp -> openAppEntry(destination.entry, onOpenFeature, showUnavailable)
        is SearchDestination.Business -> {
            val hit = destination.hit
            when (hit.businessType.toBusinessTypeCode()) {
                BusinessTypeCode.SkuDetail -> {
                    val target = hit.targetId.ifBlank { hit.targetCode }
                    if (target.isNotBlank()) {
                        onOpenFeature(FeatureRoute.SkuDetail(target))
                    } else {
                        showUnavailable("搜索结果缺少 SKU 标识")
                    }
                }
                BusinessTypeCode.BoxLabelDetail -> {
                    if (hit.targetCode.isNotBlank()) {
                        onOpenFeature(FeatureRoute.BoxLabelDetail(hit.targetCode))
                    } else {
                        showUnavailable("搜索结果缺少箱唛编码")
                    }
                }
                BusinessTypeCode.LocalApp,
                BusinessTypeCode.Unknown,
                -> showUnavailable("当前版本暂不支持该业务类型")
            }
        }
    }
}

private fun parseColor(value: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()

private fun formatOfflineExpiry(expiresAt: Long): String {
    val remainingMs = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
    val remainingDays = (remainingMs / (24L * 60L * 60L * 1000L)).coerceAtLeast(1)
    return "${remainingDays}天后"
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
        if (state.loading) item { LinearLoadingCard("正在获取 SKU 详情") }
        state.error?.let { error -> item { FscmErrorCard(error, onRetry = { viewModel.load(idOrCode) }) } }
        state.data?.let { sku -> item { SkuDetailCard(sku, onPrint = viewModel::openPrintDialog) } }
    }
    if (printState.visible) {
        LabelPrintDialog(
            state = printState,
            onDismiss = viewModel::closePrintDialog,
            onSelectNode = viewModel::selectPrintNode,
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
    onSelectNode: (Long) -> Unit,
    onChangeCopies: (Int) -> Unit,
    onPrefixChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打印 SKU 标签", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.loadingNodes -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在查询边缘节点", color = InkSoft)
                        }
                    }
                    state.nodes.isNotEmpty() -> {
                        Text("打印节点", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        state.nodes.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.submitting) { onSelectNode(option.node.id) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.selectedNodeId == option.node.id,
                                    onClick = { onSelectNode(option.node.id) },
                                    enabled = !state.submitting,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(option.node.nodeName, fontWeight = FontWeight.Bold, color = InkStrong)
                                    Text(
                                        "${option.template.name} · ${option.template.printerName}",
                                        color = InkSoft,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                StatusChip(
                                    if (option.localReachable) "本地可达" else "远程队列",
                                    if (option.localReachable) Success else BrandMain,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("打印份数", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { onChangeCopies(-1) }, enabled = state.copies > 1 && !state.submitting) {
                                Icon(Icons.Rounded.Remove, contentDescription = "减少份数")
                            }
                            Text(state.copies.toString(), modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            IconButton(onClick = { onChangeCopies(1) }, enabled = state.copies < 100 && !state.submitting) {
                                Icon(Icons.Rounded.Add, contentDescription = "增加份数")
                            }
                        }
                        OutlinedTextField(
                            value = state.labelPrefix,
                            onValueChange = onPrefixChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("标签前缀") },
                            supportingText = { Text("打印内容：${state.labelPrefix}${state.skuCode}") },
                            singleLine = true,
                        )
                    }
                }
                state.message?.let { Text(it, color = Success, fontWeight = FontWeight.Bold) }
                state.error?.let { Text(it, color = Danger) }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = state.selectedNodeId != null && !state.loadingNodes && !state.submitting,
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打印")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.submitting) { Text("关闭") } },
    )
}

@Composable
private fun BoxLabelSearchDetailScreen(
    code: String,
    viewModel: BoxLabelDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(code) { viewModel.load(code) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.loading) item { LinearLoadingCard("正在获取箱唛详情") }
        state.error?.let { error -> item { FscmErrorCard(error, onRetry = { viewModel.load(code) }) } }
        state.data?.let { label -> item { BoxLabelDetailCard(label) } }
    }
}

@Composable
private fun CartonManagementScreen(
    viewModel: CartonManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusRequester = remember { FocusRequester() }
    var scanCode by rememberSaveable { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) showCamera = true
    }

    fun doResolve(code: String = scanCode) {
        val normalized = code.normalizeBarcode()
        if (normalized.isBlank()) return
        viewModel.resolve(normalized)
        scanCode = "" // Clear after trigger
    }

    DisposableEffect(lifecycleOwner) {
        viewModel.registerScanner(lifecycleOwner) { rawCode ->
            val normalized = rawCode.normalizeBarcode()
            doResolve(normalized)
        }
        onDispose { viewModel.unregisterScanner() }
    }

    LaunchedEffect(scanCode) {
        if (scanCode.hasBarcodeTerminator()) {
            doResolve()
        }
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
                        scanCode = barcode.normalizeBarcode()
                        doResolve(scanCode)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FscmTopBar(title = "扫码识别箱唛", onBack = { showCamera = false })
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            FscmCard {
                SectionHeader("识别箱唛", "扫描枪/手输")
                OutlinedTextField(
                    value = scanCode,
                    onValueChange = { value ->
                        scanCode = value.normalizeBarcode()
                        if (value.hasBarcodeTerminator()) {
                            doResolve(value.normalizeBarcode())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                doResolve()
                                true
                            } else {
                                false
                            }
                        },
                    label = { Text("箱唛码 / 唯一码") },
                    leadingIcon = {
                        IconButton(onClick = {
                            if (PdaPermissions.hasCameraPermission(context)) {
                                showCamera = true
                            } else {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        }) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = "打开摄像头")
                        }
                    },
                    trailingIcon = {
                        if (scanCode.isNotEmpty()) {
                            IconButton(onClick = {
                                scanCode = ""
                                viewModel.clear()
                            }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清除")
                            }
                        }
                    },
                    colors = fscmTextFieldColors(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doResolve() }),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { doResolve() },
                    enabled = scanCode.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(if (state.loading) "查询中..." else "立即查询")
                }
            }
        }

        if (state.loading) {
            item { LinearLoadingCard("正在获取箱唛详情") }
        }

        state.error?.let { item { FscmErrorCard(it, onRetry = { doResolve() }) } }

        state.boxLabel?.let { label ->
            item { BoxLabelDetailCard(label) }
        }

        if (state.boxLabel == null && !state.loading && state.error == null) {
            item { EmptyCard("请扫描或输入箱唛编码进行查询") }
        }
    }
}

@Composable
private fun BoxLabelDetailCard(label: BoxLabel) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.labelCode.ifBlank { "未分配编码" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = InkStrong)
                Text("唯一码: ${label.boxUid}", style = MaterialTheme.typography.labelMedium, color = InkSoft)
            }
            StatusChip(statusText(label.status), if (label.status == "received") Success else BrandMain)
        }

        Spacer(Modifier.height(16.dp))

        DetailItem("箱号", label.boxNo.ifBlank { "-" })
        DetailItem("产品", "[${label.productCode}] ${label.productName}")
        DetailItem("SKU", label.skuCode)
        DetailItem("箱规/数量", "${label.caseSpecName} / 每箱 ${label.qtyPerCarton} 件")
        DetailItem("计划/实际", "计划 ${label.plannedBoxQty} / 实际 ${label.actualBoxQty}")

        if (label.warehouseCode.isNotBlank() || label.locationCode.isNotBlank()) {
            DetailItem("库位", "${label.warehouseCode} / ${label.locationCode}")
        }

        if (label.warnings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            label.warnings.forEach { warning ->
                Text("⚠️ $warning", color = Danger, style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("关联单据", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = InkStrong)
        Spacer(Modifier.height(8.dp))

        DocumentRefRow("厂家订单", label.supplierOrder)
        DocumentRefRow("采购订单", label.purchaseOrder)
        DocumentRefRow("收货会话", label.receivingSession)
        DocumentRefRow("收货单", label.receivingRecord)
        DocumentRefRow("集运单", label.centralReceipt)
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(80.dp), color = InkSoft, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = InkStrong, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DocumentRefRow(label: String, ref: BoxLabelDocumentRef?) {
    if (ref == null || ref.code.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(80.dp), color = InkSoft, style = MaterialTheme.typography.labelSmall)
        Text(ref.code, color = BrandMain, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusChip(statusText(ref.status), InkSoft)
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
                FscmTopBar(title = "扫码识别", onBack = { showCamera = false })
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
                    text = if (state.loading) "正在识别..." else "PDA 扫描模式",
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
                    Text("使用摄像头")
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
                        SectionHeader("最近扫描", "")
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
                item { LinearLoadingCard("正在获取详情") }
            }

            state.error?.let { item { FscmErrorCard(it, onRetry = { state.query.takeIf { q -> q.isNotBlank() }?.let(::doResolve) }) } }

            state.result?.let { result ->
                item {
                    Column {
                        Text(
                            "识别结果: ${state.query}",
                            style = MaterialTheme.typography.labelMedium,
                            color = InkSoft,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                        )
                        when (result) {
                            is Sku -> SkuDetailCard(result)
                            is BoxLabel -> BoxLabelDetailCard(result)
                            else -> Text("未知结果类型", color = Danger)
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
                            Text("等待扫码输入", color = InkSoft)
                            Text(
                                "按下 PDA 侧边键，或使用摄像头扫码",
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
    LaunchedEffect(intentMode) {
        if (intentMode) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
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
                FscmTopBar(title = "扫描标签内容", onBack = { showCamera = false })
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FscmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("标签内容", modifier = Modifier.weight(1f), color = InkStrong, fontWeight = FontWeight.Black)
                    StatusChip(if (intentMode) "广播模式" else "键盘模式", if (intentMode) Success else Warning)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { value ->
                        if (!intentMode) {
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
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(),
                    readOnly = intentMode,
                    placeholder = { Text(if (intentMode) "等待 PDA 广播扫码" else "输入或扫描字符") },
                    leadingIcon = { Icon(Icons.Rounded.QrCode, contentDescription = null, tint = BrandMain) },
                    trailingIcon = {
                        Row {
                            if (state.content.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateContent("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空内容")
                                }
                            }
                            IconButton(onClick = {
                                if (PdaPermissions.hasCameraPermission(context)) {
                                    showCamera = true
                                } else {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            }) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = "摄像头扫码")
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
                Text("可用标签模板", modifier = Modifier.weight(1f), color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = viewModel::loadOptions, enabled = !state.loadingOptions) {
                    if (state.loadingOptions) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新标签模板")
                    }
                }
            }
        }
        items(state.options, key = { it.selectionKey }) { option ->
            val selected = state.selectedOptionKey == option.selectionKey
            FscmCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.submitting) { viewModel.selectOption(option.selectionKey) },
                padding = 14,
                containerColor = if (selected) BrandSoft else SurfaceRaised,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selected,
                        onClick = { viewModel.selectOption(option.selectionKey) },
                        enabled = !state.submitting,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(option.template.name.ifBlank { option.template.code }, color = InkStrong, fontWeight = FontWeight.Bold)
                        Text(
                            "${option.node.nodeName} · ${option.template.printerName}",
                            color = InkSoft,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${option.template.widthMM} × ${option.template.heightMM} mm",
                            color = InkSoft,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    StatusChip(if (option.localReachable) "本地直打" else "远程队列", if (option.localReachable) Success else BrandMain)
                }
            }
        }

        item {
            FscmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("打印份数", modifier = Modifier.weight(1f), color = InkStrong, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { viewModel.changeCopies(-1) }, enabled = state.copies > 1 && !state.submitting) {
                        Icon(Icons.Rounded.Remove, contentDescription = "减少份数")
                    }
                    Text(state.copies.toString(), modifier = Modifier.width(36.dp), color = InkStrong, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Black)
                    IconButton(onClick = { viewModel.changeCopies(1) }, enabled = state.copies < 100 && !state.submitting) {
                        Icon(Icons.Rounded.Add, contentDescription = "增加份数")
                    }
                }
                state.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Success, fontWeight = FontWeight.Bold)
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Danger)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::submit,
                    enabled = state.content.isNotBlank() && state.selectedOptionKey != null && !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("下发打印任务")
                    }
                }
            }
        }
    }
}

@Composable
private fun EdgeNodesScreen(
    viewModel: EdgeNodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedNode = state.nodes.firstOrNull { it.identityKey == state.selectedNodeKey }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("边缘服务节点", color = InkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "中心已注册 ${state.nodes.size} 个，本机可直连 ${state.nodes.count { it.localReachable }} 个",
                        color = InkSoft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = viewModel::load, enabled = !state.loading) {
                    if (state.loading && state.nodes.isNotEmpty()) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新边缘节点")
                    }
                }
            }
        }
        if (state.loading && state.nodes.isEmpty()) {
            item { LinearLoadingCard("正在读取中心节点并检测局域网连接") }
        }
        state.error?.let { error ->
            item { FscmErrorCard(error, onRetry = viewModel::load) }
        }
        items(state.nodes, key = { it.identityKey }) { status ->
            PrintNodeStatusCard(status, onClick = { viewModel.selectNode(status.identityKey) })
        }
        if (!state.loading && state.error == null && state.nodes.isEmpty()) {
            item { EmptyCard("服务中心未查询到已注册的边缘节点") }
        }
    }

    selectedNode?.let { status ->
        PrintNodeDetailsDialog(status, onDismiss = viewModel::closeNodeDetails)
    }
}

@Composable
private fun PrintNodeStatusCard(
    status: EdgePrintNodeStatus,
    onClick: () -> Unit,
) {
    val node = status.node
    val readyPrinters = node.printInventory.printers.count { it.status == "ready" }
    val availableTemplates = node.printInventory.templates.count { it.available }
    val stateLabel = when {
        status.localReachable -> "本地可达"
        node.status == "online" -> "远程可用"
        else -> "离线"
    }
    val stateColor = when {
        status.localReachable -> Success
        node.status == "online" -> BrandMain
        else -> InkSoft
    }

    FscmCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(node.nodeName.ifBlank { node.nodeId }, color = InkStrong, fontWeight = FontWeight.Black)
                Text(node.nodeId, color = InkSoft, style = MaterialTheme.typography.labelMedium)
            }
            StatusChip(stateLabel, stateColor)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = InkSoft)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "打印机 $readyPrinters/${node.printInventory.printers.size}  ·  模板 $availableTemplates/${node.printInventory.templates.size}",
            color = InkStrong,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            node.lanBaseUrl.ifBlank { "未配置局域网地址" },
            color = InkSoft,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PrintNodeDetailsDialog(
    status: EdgePrintNodeStatus,
    onDismiss: () -> Unit,
) {
    val node = status.node
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.nodeName.ifBlank { node.nodeId }, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DiagnosticText("节点标识", node.nodeId)
                    DiagnosticText("局域网地址", node.lanBaseUrl.ifBlank { "未配置" })
                    DiagnosticText("连接方式", if (status.localReachable) "本地直连" else "后端远程队列")
                    DiagnosticText("最近在线", shortTime(node.lastSeenAt).ifBlank { "-" })
                }
                item {
                    Text("打印机", color = InkStrong, fontWeight = FontWeight.Black)
                }
                if (node.printInventory.printers.isEmpty()) {
                    item { Text("未发现打印机", color = InkSoft) }
                } else {
                    items(node.printInventory.printers, key = { it.name }) { printer ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Print, contentDescription = null, tint = BrandMain, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(printer.name, color = InkStrong, fontWeight = FontWeight.Bold)
                                Text(if (printer.isDefault) "默认打印机" else "可用打印机", color = InkSoft, style = MaterialTheme.typography.labelSmall)
                            }
                            StatusChip(if (printer.status == "ready") "就绪" else "不可用", if (printer.status == "ready") Success else Danger)
                        }
                    }
                }
                item {
                    Text("标签模板", color = InkStrong, fontWeight = FontWeight.Black)
                }
                if (node.printInventory.templates.isEmpty()) {
                    item { Text("未配置标签模板", color = InkSoft) }
                } else {
                    items(node.printInventory.templates, key = { it.code }) { template ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(template.name.ifBlank { template.code }, modifier = Modifier.weight(1f), color = InkStrong, fontWeight = FontWeight.Bold)
                                StatusChip(if (template.available) "可打印" else "不可用", if (template.available) Success else Danger)
                            }
                            Text(template.code, color = InkSoft, style = MaterialTheme.typography.labelSmall)
                            Text(
                                "${template.widthMM} × ${template.heightMM} mm  ·  ${template.orientation.ifBlank { "默认方向" }}",
                                color = InkSoft,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text("打印机：${template.printerName.ifBlank { "未绑定" }}", color = InkSoft, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun PdaSettingsScreen(
    viewModel: PdaSettingsViewModel = hiltViewModel(),
) {
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            FscmCard(modifier = Modifier.fillMaxWidth(), containerColor = BrandSoft.copy(alpha = 0.5f)) {
                SectionHeader("PDA 设置", "全局模式")
                Spacer(Modifier.height(8.dp))
                Text(
                    "设置 FSCM 在首页和扫码页优先使用键盘输入还是 Intent 广播接收。未手动选择时默认键盘输入，检测到有效广播扫码后自动切到 Intent 广播。",
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
                onClear = viewModel::clearScannerDiagnostics,
                onCopy = {
                    clipboardManager.setText(AnnotatedString(scannerState.toDiagnosticText()))
                    Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
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
            title = "应用扫码模式",
            trailing = when (configuredMode) {
                ScannerInputMode.Keyboard -> "已设置键盘"
                ScannerInputMode.IntentBroadcast -> "已设置广播"
                null -> "自动"
            },
        )
        Spacer(Modifier.height(10.dp))
        DiagnosticText(
            label = "当前模式",
            value = when (effectiveMode) {
                ScannerInputMode.Keyboard -> "键盘输入模式"
                ScannerInputMode.IntentBroadcast -> "Intent 广播模式"
            },
        )
        DiagnosticText(
            label = "自动检测",
            value = if (configuredMode == null) "未检测到广播时使用键盘输入" else "已按手动设置固定",
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScannerModeButton(
                title = "键盘输入",
                selected = effectiveMode == ScannerInputMode.Keyboard,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(ScannerInputMode.Keyboard) },
            )
            ScannerModeButton(
                title = "Intent 广播",
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
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    val event = scannerState.lastEvent
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("PDA 诊断", if (scannerState.registered) "监听中" else "未监听")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(
                if (scannerState.registered) "广播接收已启用" else "等待页面恢复",
                if (scannerState.registered) Success else Warning,
            )
            StatusChip("${scannerState.profile.actions.size} 个 Action", BrandMain)
            StatusChip("${scannerState.profile.extraKeys.size} 个 Extra", Teal)
            StatusChip(
                when (scannerState.effectiveMode) {
                    ScannerInputMode.Keyboard -> "键盘模式"
                    ScannerInputMode.IntentBroadcast -> "广播模式"
                },
                if (scannerState.effectiveMode == ScannerInputMode.IntentBroadcast) Success else Warning,
            )
        }
        Spacer(Modifier.height(12.dp))
        DiagnosticText("最近 Action", event?.action?.ifBlank { "-" } ?: "暂无广播")
        DiagnosticText("最近 Extra", event?.extraKey ?: "-")
        DiagnosticText("提取状态", if (event?.extracted == true) "已提取条码" else "未提取条码")
        DiagnosticText("最近条码", event?.normalizedValue?.ifBlank { "-" } ?: "-")
        DiagnosticText("接收时间", event?.receivedAtMillis?.let(::formatScannerTime) ?: "-")
        scannerState.lastError?.let { error ->
            DiagnosticText("最近异常", error)
        }
        Spacer(Modifier.height(12.dp))
        DiagnosticText(
            label = "监听 Action",
            value = scannerState.profile.actions.take(6).joinToString("\n") +
                if (scannerState.profile.actions.size > 6) "\n..." else "",
        )
        DiagnosticText(
            label = "提取 Extra",
            value = scannerState.profile.extraKeys.take(6).joinToString("\n") +
                if (scannerState.profile.extraKeys.size > 6) "\n..." else "",
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCopy,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("复制诊断")
            }
            OutlinedButton(
                onClick = onClear,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("清空结果")
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

private fun ScannerRuntimeState.toDiagnosticText(): String {
    val event = lastEvent
    return buildString {
        appendLine("PDA 扫码诊断")
        appendLine("registered=$registered")
        appendLine("configuredMode=${configuredMode?.name.orEmpty()}")
        appendLine("effectiveMode=${effectiveMode.name}")
        appendLine("lastAction=${event?.action.orEmpty()}")
        appendLine("lastExtra=${event?.extraKey.orEmpty()}")
        appendLine("extracted=${event?.extracted ?: false}")
        appendLine("barcode=${event?.normalizedValue.orEmpty()}")
        appendLine("receivedAt=${event?.receivedAtMillis?.let(::formatScannerTime).orEmpty()}")
        appendLine("lastError=${lastError.orEmpty()}")
        appendLine("actions=${profile.actions.joinToString(";")}")
        appendLine("extras=${profile.extraKeys.joinToString(";")}")
    }
}

private fun formatScannerTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

@Composable
private fun SkuDetailCard(sku: Sku, onPrint: (() -> Unit)? = null) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = sku.thumbnailUrl.ifBlank { sku.imageUrl }.ifBlank { sku.product?.thumbnailUrl ?: "" },
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceMuted),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(sku.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = BrandMain)
                Text(sku.name.ifBlank { sku.product?.name ?: "未命名 SKU" }, style = MaterialTheme.typography.bodyMedium, color = InkStrong, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(20.dp))
        DetailItem("规格", sku.spec.ifBlank { "-" })
        DetailItem("颜色/尺寸", "${sku.color.ifBlank { "-" }} / ${sku.size.ifBlank { "-" }}")
        DetailItem("重量", if (sku.weight > 0) "${sku.weight} kg" else "-")

        sku.product?.let { product ->
            Spacer(Modifier.height(12.dp))
            Divider(color = StrokeSoft, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))
            Text("所属产品信息", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = InkStrong)
            Spacer(Modifier.height(8.dp))
            DetailItem("产品名", product.name)
            DetailItem("产品码", product.code)
            DetailItem("品牌", product.brand.ifBlank { "-" })
        }
        if (onPrint != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onPrint, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("打印标签")
            }
        }
    }
}

@Composable
private fun Divider(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant, thickness: androidx.compose.ui.unit.Dp = 1.dp) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}
