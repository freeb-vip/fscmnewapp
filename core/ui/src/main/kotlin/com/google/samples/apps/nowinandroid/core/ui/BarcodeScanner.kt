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

package com.google.samples.apps.nowinandroid.core.ui

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

data class DetectedBarcode(
    val value: String,
    val boundingBox: Rect?,
    val imageWidth: Int,
    val imageHeight: Int,
)

private const val MULTI_BARCODE_COLLECTION_WINDOW_MS = 450L

@Composable
fun BarcodeScanner(
    onBarcodeDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSelectionDialogForMultiple: Boolean = false,
    resumeToken: Long = 0,
    selectionTitle: String = "选择箱唛码",
    selectionPrompt: String = "识别到多个码，请选择本次要使用的箱唛码。",
    preferredBarcodeMatcher: ((String) -> Boolean)? = null,
    preferredBarcodeWaitMillis: Long = 800L,
    noPreferredBarcodeTitle: String = "未识别到有效二维码",
    noPreferredBarcodePrompt: String = "请对准正确的二维码重新扫描。",
    retryScanLabel: String = "重新扫描",
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcodeDetected by rememberUpdatedState(onBarcodeDetected)
    val scannerResult = remember {
        runCatching {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                    .build(),
            )
        }
    }
    val scanner = scannerResult.getOrNull()
    if (scanner == null) {
        val initializationError = remember(scannerResult) {
            scannerResult.exceptionOrNull()
                ?: IllegalStateException("BarcodeScanning.getClient returned null")
        }
        DisposableEffect(initializationError) {
            Log.e("BarcodeScanner", "ML Kit barcode scanner initialization failed", initializationError)
            onDispose { }
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "扫码组件初始化失败，请返回重试",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var detectedBarcodes by remember { mutableStateOf<List<DetectedBarcode>>(emptyList()) }
    var isPaused by remember { mutableStateOf(false) }
    var collectingCandidates by remember { mutableStateOf(false) }
    var noPreferredBarcodeDetected by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val pendingCandidates = remember { linkedMapOf<String, DetectedBarcode>() }
    val candidateTimeout = remember { arrayOfNulls<Runnable>(1) }

    fun cancelCandidateTimeout() {
        candidateTimeout[0]?.let(mainHandler::removeCallbacks)
        candidateTimeout[0] = null
        collectingCandidates = false
    }

    fun resetCandidateState() {
        mainHandler.removeCallbacksAndMessages(null)
        candidateTimeout[0] = null
        collectingCandidates = false
        pendingCandidates.clear()
        detectedBarcodes = emptyList()
        noPreferredBarcodeDetected = false
        isPaused = false
    }

    fun acceptPreferredCandidates(candidates: List<DetectedBarcode>) {
        cancelCandidateTimeout()
        pendingCandidates.clear()
        when (candidates.size) {
            0 -> Unit
            1 -> {
                isPaused = true
                currentOnBarcodeDetected(candidates.first().value)
            }
            else -> {
                detectedBarcodes = candidates
                isPaused = true
            }
        }
    }

    LaunchedEffect(resumeToken) {
        resetCandidateState()
    }

    fun handleDetectedBarcodes(results: List<DetectedBarcode>) {
        if (preferredBarcodeMatcher != null) {
            mainHandler.post {
                if (isPaused) return@post
                val preferredCandidates = results.filter { preferredBarcodeMatcher(it.value) }
                if (preferredCandidates.isNotEmpty()) {
                    // Only candidates in this frame are selectable. Earlier SKU codes never become choices.
                    acceptPreferredCandidates(preferredCandidates)
                } else if (!collectingCandidates) {
                    collectingCandidates = true
                    val timeout = Runnable {
                        candidateTimeout[0] = null
                        collectingCandidates = false
                        noPreferredBarcodeDetected = true
                        isPaused = true
                    }
                    candidateTimeout[0] = timeout
                    mainHandler.postDelayed(timeout, preferredBarcodeWaitMillis.coerceAtLeast(0L))
                }
            }
            return
        }
        if (!showSelectionDialogForMultiple) {
            if (results.size > 1) {
                detectedBarcodes = results
                isPaused = true
            } else if (results.size == 1) {
                currentOnBarcodeDetected(results.first().value)
            }
            return
        }
        mainHandler.post {
            if (isPaused) return@post
            results.forEach { pendingCandidates[it.value] = it }
            if (collectingCandidates) return@post
            collectingCandidates = true
            mainHandler.postDelayed({
                val candidates = pendingCandidates.values.toList()
                pendingCandidates.clear()
                collectingCandidates = false
                when (candidates.size) {
                    0 -> Unit
                    1 -> {
                        isPaused = true
                        currentOnBarcodeDetected(candidates.first().value)
                    }
                    else -> {
                        detectedBarcodes = candidates
                        isPaused = true
                    }
                }
            }, MULTI_BARCODE_COLLECTION_WINDOW_MS)
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val view = previewView
        if (view == null) {
            onDispose { }
        } else {
            var isDisposed = false
            var cameraProvider: ProcessCameraProvider? = null
            var preview: Preview? = null
            var imageAnalysis: ImageAnalysis? = null
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                if (isDisposed) return@addListener
                try {
                    val provider = cameraProviderFuture.get()
                    val previewUseCase = Preview.Builder().build().apply {
                        setSurfaceProvider(view.surfaceProvider)
                    }
                    val analysisUseCase = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isPaused) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        processImageProxy(scanner, imageProxy, ::handleDetectedBarcodes)
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        analysisUseCase,
                    )
                    cameraProvider = provider
                    preview = previewUseCase
                    imageAnalysis = analysisUseCase
                } catch (e: Exception) {
                    Log.e("BarcodeScanner", "Camera setup failed", e)
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                isDisposed = true
                imageAnalysis?.clearAnalyzer()
                cameraProvider?.let { provider ->
                    preview?.let { provider.unbind(it) }
                    imageAnalysis?.let { provider.unbind(it) }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            cameraExecutor.shutdown()
            scanner.close()
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { containerSize = it.size },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            update = { view ->
                if (previewView !== view) {
                    previewView = view
                }
            },
        )

        if (isPaused && detectedBarcodes.isNotEmpty()) {
            if (showSelectionDialogForMultiple) {
                BarcodeSelectionDialog(
                    barcodes = detectedBarcodes,
                    title = selectionTitle,
                    prompt = selectionPrompt,
                    onSelected = currentOnBarcodeDetected,
                    onDismiss = ::resetCandidateState,
                )
            } else {
                BarcodeVisualOverlay(
                    barcodes = detectedBarcodes,
                    containerSize = containerSize,
                    onSelected = {
                        isPaused = false
                        detectedBarcodes = emptyList()
                        currentOnBarcodeDetected(it)
                    },
                    onDismiss = ::resetCandidateState,
                )
            }
        }
        if (noPreferredBarcodeDetected) {
            AlertDialog(
                onDismissRequest = ::resetCandidateState,
                title = { Text(noPreferredBarcodeTitle) },
                text = { Text(noPreferredBarcodePrompt) },
                confirmButton = {
                    Button(onClick = ::resetCandidateState) { Text(retryScanLabel) }
                },
            )
        }
    }
}

@Composable
private fun BarcodeSelectionDialog(
    barcodes: List<DetectedBarcode>,
    title: String,
    prompt: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt)
                LazyColumn(modifier = Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(barcodes, key = { it.value }) { barcode ->
                        BarcodeItem(
                            code = barcode.value,
                            brandMain = MaterialTheme.colorScheme.primary,
                            inkStrong = MaterialTheme.colorScheme.onSurface,
                            onClick = { onSelected(barcode.value) },
                        )
                    }
                }
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("继续扫描") }
        },
        confirmButton = {},
    )
}

@Composable
private fun BarcodeVisualOverlay(
    barcodes: List<DetectedBarcode>,
    containerSize: IntSize,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val brandMain = MaterialTheme.colorScheme.primary
    val surfaceRaised = MaterialTheme.colorScheme.surface
    val inkStrong = MaterialTheme.colorScheme.onSurface
    val inkSoft = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        // Visual markers for each barcode
        barcodes.forEach { barcode ->
            barcode.boundingBox?.let { box ->
                val scaleX = containerSize.width.toFloat() / barcode.imageWidth
                val scaleY = containerSize.height.toFloat() / barcode.imageHeight
                val scale = maxOf(scaleX, scaleY)

                val offsetX = (containerSize.width - barcode.imageWidth * scale) / 2
                val offsetY = (containerSize.height - barcode.imageHeight * scale) / 2

                val centerX = box.centerX() * scale + offsetX
                val centerY = box.centerY() * scale + offsetY

                BarcodeMarker(
                    brandMain = brandMain,
                    modifier = Modifier.offset(
                        x = (centerX / (LocalContext.current.resources.displayMetrics.density)).dp - 20.dp,
                        y = (centerY / (LocalContext.current.resources.displayMetrics.density)).dp - 20.dp,
                    ),
                    onClick = { onSelected(barcode.value) },
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceRaised),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "识别到 ${barcodes.size} 个条码",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = inkStrong,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = inkSoft)
                        }
                    }
                    Text(
                        "请点击画面中的标记或下方列表选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = inkSoft,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(barcodes) { item ->
                            BarcodeItem(
                                code = item.value,
                                brandMain = brandMain,
                                inkStrong = inkStrong,
                                onClick = { onSelected(item.value) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = brandMain),
                    ) {
                        Text("继续扫描")
                    }
                }
            }
        }
    }
}

@Composable
private fun BarcodeMarker(
    brandMain: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "marker")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = brandMain.copy(alpha = 0.3f * alpha),
                radius = size.minDimension / 2 * scale,
            )
            drawCircle(
                color = brandMain,
                radius = 12f,
                center = center,
            )
            drawCircle(
                color = Color.White,
                radius = 6f,
                center = center,
            )
        }
    }
}

@Composable
private fun BarcodeItem(
    code: String,
    brandMain: Color,
    inkStrong: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(brandMain.copy(alpha = 0.05f))
            .border(1.dp, brandMain.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.QrCode, contentDescription = null, tint = brandMain, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(
                code,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = inkStrong,
                maxLines = 1,
            )
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onBarcodesDetected: (List<DetectedBarcode>) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val rotation = imageProxy.imageInfo.rotationDegrees
        val width = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val height = if (rotation % 180 == 0) imageProxy.height else imageProxy.width

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val results = barcodes.mapNotNull { barcode ->
                        barcode.rawValue?.let { value ->
                            DetectedBarcode(
                                value = value,
                                boundingBox = barcode.boundingBox,
                                imageWidth = width,
                                imageHeight = height,
                            )
                        }
                    }.distinctBy { it.value }

                    if (results.isNotEmpty()) {
                        onBarcodesDetected(results)
                    }
                }
            }
            .addOnFailureListener {
                Log.e("BarcodeScanner", "Barcode scanning failed", it)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
