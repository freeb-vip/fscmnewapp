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

package com.google.samples.apps.nowinandroid.fscm.ui.workbench

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandDeep
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmBrandMain
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmDanger
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkSoft
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmInkStrong
import com.google.samples.apps.nowinandroid.core.ui.BarcodeScanner
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.SessionMode
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.visibleMobileApps
import com.google.samples.apps.nowinandroid.fscm.device.PdaPermissions
import com.google.samples.apps.nowinandroid.fscm.device.ScannerInputMode
import com.google.samples.apps.nowinandroid.fscm.ui.FscmAppIcon
import com.google.samples.apps.nowinandroid.fscm.ui.WorkbenchViewModel
import com.google.samples.apps.nowinandroid.fscm.ui.components.FscmBadge
import com.google.samples.apps.nowinandroid.fscm.ui.components.FscmCard
import com.google.samples.apps.nowinandroid.fscm.ui.localizedAppTitle
import com.google.samples.apps.nowinandroid.fscm.ui.ScanAwareDialog as Dialog

@Composable
fun WorkbenchScreen(
    session: SessionState,
    pendingSyncCount: Int,
    onOpenFeature: (Any) -> Unit, // FeatureRoute is sealed, will fix in FscmApp
    viewModel: WorkbenchViewModel,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    LaunchedEffect(session.user?.id, session.currentNamespace?.id, session.sessionMode) {
        if (session.isLoggedIn) viewModel.load(session)
    }
    LaunchedEffect(viewModel) {
        viewModel.searchNavigation.collect { destination ->
            onOpenFeature(destination)
        }
    }
    val overview = state.data
    val apps = visibleMobileApps(overview, session.permissions, session.sessionMode)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                WorkbenchHero(
                    namespace = session.currentNamespace?.name ?: "当前工作空间",
                    sessionMode = session.sessionMode,
                    pendingSyncCount = pendingSyncCount,
                    offlineExpiresAt = session.offlineExpiresAt,
                    onScan = { onOpenFeature(Any()) }, // Placeholder
                    searchQuery = searchQuery,
                    scannerInputMode = scannerState.effectiveMode,
                    onSearch = { query -> viewModel.search(query) },
                    onQueryChange = { query -> viewModel.updateSearchQuery(query) },
                )
            }
            if (state.loading) item { LinearLoadingCard(if (session.isOffline) "正在读取本地工作台" else "正在加载首页") }
            // state.error handler placeholder
            item {
                SectionHeader("常用应用", "全部") { onOpenFeature(Any()) }
                AppGrid(apps.take(8), onOpenFeature)
            }
            // MessagesPreview placeholder
        }

        // GlobalSearchResultsOverlay placeholder
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
                        onSearch(barcode.trim())
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // TopBar placeholder
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(FscmBrandMain, FscmBrandDeep)))
            .padding(24.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("四海环球", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(namespace, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(
                    onClick = { onScan() },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.15f)),
                ) {
                    Icon(Icons.Rounded.QrCode, contentDescription = "PDA 模式", tint = Color.White)
                }
            }
        }
        if (sessionMode == SessionMode.Offline) {
            Spacer(Modifier.height(16.dp))
            Text(
                "待同步 $pendingSyncCount 条 · 离线凭证 到期",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(24.dp))
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
    val focusRequester = remember { FocusRequester() }
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

    fun submitSearch(value: String) {
        onQueryChange(value)
        onSearch(value)
        fieldValue = TextFieldValue(
            text = value,
            selection = TextRange(0, value.length),
        )
        keyboardController?.hide()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.98f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { value ->
                if (!intentMode) {
                    fieldValue = value
                    onQueryChange(value.text)
                }
            },
            placeholder = {
                Text(
                    if (intentMode) "等待 PDA 广播扫码" else "搜索单据、SKU",
                    color = FscmInkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            readOnly = intentMode,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = FscmBrandMain,
            ),
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = FscmBrandMain, modifier = Modifier.size(24.dp))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitSearch(fieldValue.text) }),
        )
        Button(
            onClick = onScan,
            contentPadding = PaddingValues(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FscmBrandMain),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(44.dp),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (intentMode) "摄像头" else "扫码", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AppGrid(
    apps: List<MobileAppEntry>,
    onOpenFeature: (Any) -> Unit,
) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.height((((apps.size + 3) / 4).coerceAtLeast(1) * 104).dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(apps) { app ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.9f))
                    .clickable { onOpenFeature(app) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    FscmAppIcon(app, size = 48)
                    if (app.badgeCount > 0) {
                        Box(Modifier.align(Alignment.TopEnd)) { FscmBadge(app.badgeCount.toString(), FscmDanger) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    localizedAppTitle(app),
                    color = FscmInkStrong,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun LinearLoadingCard(text: String) {
    FscmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FscmBrandMain)
            Spacer(Modifier.width(12.dp))
            Text(text, color = FscmInkSoft)
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String, onTrailing: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = FscmInkStrong, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(
                trailing,
                color = FscmBrandMain,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier,
            )
        }
    }
}
