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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private val BrandMain = Color(0xFF1677FF)
private val InkStrong = Color(0xFF172033)
private val InkSoft = Color(0xFF66758A)
private val SurfaceRaised = Color(0xFFFFFFFF)

data class DetectedBarcode(
    val value: String,
    val boundingBox: Rect?,
    val imageWidth: Int,
    val imageHeight: Int
)

@Composable
fun BarcodeScanner(
    onBarcodeDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var detectedBarcodes by remember { mutableStateOf<List<DetectedBarcode>>(emptyList()) }
    var isPaused by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier.onGloballyPositioned { containerSize = it.size }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                            .build()
                    )

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isPaused) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        processImageProxy(scanner, imageProxy) { results ->
                            if (results.size > 1) {
                                detectedBarcodes = results
                                isPaused = true
                            } else if (results.size == 1) {
                                onBarcodeDetected(results[0].value)
                            }
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("BarcodeScanner", "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        if (isPaused && detectedBarcodes.isNotEmpty()) {
            BarcodeVisualOverlay(
                barcodes = detectedBarcodes,
                containerSize = containerSize,
                onSelected = {
                    isPaused = false
                    detectedBarcodes = emptyList()
                    onBarcodeDetected(it)
                },
                onDismiss = {
                    isPaused = false
                    detectedBarcodes = emptyList()
                }
            )
        }
    }
}

@Composable
private fun BarcodeVisualOverlay(
    barcodes: List<DetectedBarcode>,
    containerSize: IntSize,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
    ) {
        // Visual markers for each barcode
        barcodes.forEach { barcode ->
            barcode.boundingBox?.let { box ->
                // Basic coordinate mapping (Assuming FillCenter scale type)
                val scaleX = containerSize.width.toFloat() / barcode.imageWidth
                val scaleY = containerSize.height.toFloat() / barcode.imageHeight
                val scale = maxOf(scaleX, scaleY)

                val offsetX = (containerSize.width - barcode.imageWidth * scale) / 2
                val offsetY = (containerSize.height - barcode.imageHeight * scale) / 2

                val centerX = box.centerX() * scale + offsetX
                val centerY = box.centerY() * scale + offsetY

                BarcodeMarker(
                    modifier = Modifier.offset(
                        x = (centerX / (LocalContext.current.resources.displayMetrics.density)).dp - 20.dp,
                        y = (centerY / (LocalContext.current.resources.displayMetrics.density)).dp - 20.dp
                    ),
                    onClick = { onSelected(barcode.value) }
                )
            }
        }

        // Bottom selection card (similar to previous version but more integrated)
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "识别到 ${barcodes.size} 个条码",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = InkStrong
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = InkSoft)
                        }
                    }
                    Text(
                        "请点击画面中的标记或下方列表选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(barcodes) { item ->
                            BarcodeItem(item.value, onClick = { onSelected(item.value) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandMain)
                    ) {
                        Text("继续扫描")
                    }
                }
            }
        }
    }
}

@Composable
private fun BarcodeMarker(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "marker")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = BrandMain.copy(alpha = 0.3f * alpha),
                radius = size.minDimension / 2 * scale
            )
            drawCircle(
                color = BrandMain,
                radius = 12f,
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 6f,
                center = center
            )
        }
    }
}

@Composable
private fun BarcodeItem(code: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F8FF))
            .border(1.dp, Color(0xFFDCE7F5), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.QrCode, contentDescription = null, tint = BrandMain, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(
                code,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = InkStrong,
                maxLines = 1
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
        // ML Kit handles rotation, but bounding box is relative to the image
        // If rotation is 90 or 270, width and height are swapped for mapping
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
                                imageHeight = height
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
