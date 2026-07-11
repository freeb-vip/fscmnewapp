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
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.MobileWorkbenchOverview
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.ProfitOverview
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingScanItem
import com.google.samples.apps.nowinandroid.fscm.data.ReceivingSession
import com.google.samples.apps.nowinandroid.fscm.data.SessionMode
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.ShopOverview
import com.google.samples.apps.nowinandroid.fscm.data.formatAmount
import com.google.samples.apps.nowinandroid.fscm.data.formatCount
import com.google.samples.apps.nowinandroid.fscm.data.formatPercent
import com.google.samples.apps.nowinandroid.fscm.data.shortTime
import com.google.samples.apps.nowinandroid.fscm.data.statusText
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import com.google.samples.apps.nowinandroid.fscm.device.PdaPermissions
import java.io.Serializable

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
    val pendingSyncCount by rootViewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
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
}

@Composable
private fun MainShell(
    session: SessionState,
    pendingSyncCount: Int,
    onLogout: () -> Unit,
    onSwitchNamespace: (Long) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Workbench) }
    var featureRoute by rememberSaveable { mutableStateOf<FeatureRoute>(FeatureRoute.None) }

    if (featureRoute !is FeatureRoute.None) {
        FeatureScreen(
            route = featureRoute,
            session = session,
            onBack = { featureRoute = FeatureRoute.None },
            onOpenReceivingDetail = { featureRoute = FeatureRoute.ReceivingDetail(it) },
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
                MainTab.Workbench -> WorkbenchScreen(session, pendingSyncCount, onOpenFeature = { featureRoute = it })
                MainTab.Apps -> AppsScreen(session, onOpenFeature = { featureRoute = it })
                MainTab.Messages -> MessagesScreen(session)
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
    onOpenReceivingDetail: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            FscmTopBar(
                title = when (route) {
                    FeatureRoute.Profit -> stringResource(R.string.profit_dashboard)
                    FeatureRoute.ReceivingList -> stringResource(R.string.warehouse_receiving)
                    is FeatureRoute.ReceivingDetail -> stringResource(R.string.scan_receiving)
                    FeatureRoute.None -> ""
                },
                onBack = onBack,
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (route) {
                FeatureRoute.Profit -> ProfitScreen(session)
                FeatureRoute.ReceivingList -> ReceivingListScreen(onOpenReceivingDetail)
                is FeatureRoute.ReceivingDetail -> ReceivingDetailScreen(route.id)
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
    viewModel: WorkbenchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    val overview = state.data
    val apps = visibleMobileApps(overview, session.permissions, session.sessionMode)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            WorkbenchHero(
                namespace = session.currentNamespace?.name ?: "当前工作空间",
                todoCount = overview?.todos?.count { it.status !in listOf("done", "completed", "finished") } ?: 0,
                appCount = apps.size,
                sessionMode = session.sessionMode,
                pendingSyncCount = pendingSyncCount,
                offlineExpiresAt = session.offlineExpiresAt,
                onScan = { onOpenFeature(FeatureRoute.ReceivingList) },
            )
        }
        if (state.loading) item { LinearLoadingCard(if (session.isOffline) "正在读取本地工作台" else "正在加载首页") }
        state.error?.let { item { ErrorCard(it, onRetry = viewModel::load) } }
        item {
            TaskSection(overview, apps, onOpenFeature)
        }
        item {
            SectionHeader("常用应用", "全部") { onOpenFeature(FeatureRoute.None) }
            AppGrid(apps.take(5), onOpenFeature)
        }
        item {
            MessagesPreview(overview)
        }
    }
}

@Composable
private fun WorkbenchHero(
    namespace: String,
    todoCount: Int,
    appCount: Int,
    sessionMode: SessionMode,
    pendingSyncCount: Int,
    offlineExpiresAt: Long,
    onScan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(BrandMain, BrandDeep)))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("四海环球", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(namespace, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
            }
            StatusChip(if (sessionMode == SessionMode.Offline) "离线模式" else "在线工作台", Color.White, containerColor = Color.White.copy(alpha = 0.14f))
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
        SearchPanel(onScan)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroMetric("待处理", todoCount.toString(), Modifier.weight(1f))
            HeroMetric("可用应用", appCount.toString(), Modifier.weight(1f))
            HeroMetric("扫码入口", "收货", Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        Text(label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SearchPanel(onScan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.96f))
            .padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(BrandSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = BrandMain, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("搜索单据、SKU、箱唛", color = InkStrong, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("扫码收货优先", color = InkSoft, style = MaterialTheme.typography.labelSmall)
        }
        Button(
            onClick = onScan,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandMain),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("扫码")
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
    LaunchedEffect(Unit) { viewModel.load() }
    val apps = visibleMobileApps(state.data, session.permissions, session.sessionMode)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageTitle("应用", "${apps.size} 个应用按权限展示")
        }
        state.error?.let { item { ErrorCard(it, viewModel::load) } }
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
    LaunchedEffect(Unit) { viewModel.load() }
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
        state.error?.let { item { ErrorCard(it, viewModel::load) } }
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
        state.error?.let { item { ErrorCard(it) { viewModel.load(session.permissions) } } }
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
        state.error?.let { item { ErrorCard(it, viewModel::load) } }
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
    viewModel: ReceivingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scanCode by rememberSaveable(sessionId) { mutableStateOf("") }
    var exceptionQty by rememberSaveable { mutableStateOf("0") }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun submitBarcode(rawCode: String = scanCode) {
        val normalized = rawCode.normalizeBarcode()
        if (normalized.isBlank() || state.submitting) return
        scanCode = normalized
        viewModel.preview(sessionId, normalized)
    }
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
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
        state.error?.let { item { ErrorCard(it) { viewModel.load(sessionId) } } }
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
                        leadingIcon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
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
private fun ErrorCard(text: String, onRetry: () -> Unit) {
    FscmCard {
        Text(text, color = Danger, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重试")
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    FscmCard(modifier = Modifier.fillMaxWidth()) {
        Text(text, color = InkSoft, modifier = Modifier.padding(vertical = 20.dp).align(Alignment.CenterHorizontally))
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
        else -> showUnavailable("${app.title} 暂未开放原生页面")
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
