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

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.fscm.data.ManufacturerPaymentBill
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManufacturerPaymentActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun paymentActionIsVisibleAndOpensReviewInAllFourLanguages() {
        val language = mutableStateOf("zh-Hans")
        val bill = ManufacturerPaymentBill(id = 1, status = "submitted", approvalProvider = "local")
        fun resources(tag: String) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
        ).resources
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalResources provides resources(language.value),
                LocalDensity provides Density(LocalDensity.current.density, 1.5f),
            ) {
                FscmTheme {
                    Box(Modifier.width(320.dp)) {
                        ManufacturerBillPaymentActions(bill, true, false, { opened++ }, {}, {})
                    }
                }
            }
        }
        for (tag in listOf("zh-Hans", "en", "th", "my")) {
            compose.runOnIdle { language.value = tag }
            compose.onNodeWithText(resources(tag).getString(R.string.manufacturer_bill_pay_approve)).assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(4, opened) }
    }

    @Test
    @SdkSuppress(maxSdkVersion = 28)
    fun saveFromPreviewWritesFullSizeLosslessImage() {
        val directory = File(context.cacheDir, "payment-attachments").apply { mkdirs() }
        val original = File.createTempFile("qr-source", ".png", directory)
        val destination = File.createTempFile("qr-result", ".png", directory)
        fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(1023, 1023, Color.BLACK)
        }
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("image/png")
            },
            ActivityResult(Activity.RESULT_OK, Intent().setData(uri(destination))),
            true,
        )
        try {
            original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.setContent { FscmTheme { ManufacturerPaymentQrLink(uri(original).toString()) } }
            compose.onNodeWithText(context.getString(R.string.manufacturer_bill_view_qr)).performClick()
            val save = compose.onNodeWithText(context.getString(R.string.manufacturer_bill_qr_save))
            compose.waitUntil(10_000) { runCatching { save.assertIsEnabled() }.isSuccess }
            save.performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithText(context.getString(R.string.manufacturer_bill_qr_saved)).assertIsDisplayed() }.isSuccess
            }
            assertTrue(bitmap.sameAs(BitmapFactory.decodeFile(destination.path)))
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            original.delete()
            destination.delete()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun gallerySavePublishesReadablePng() = runBlocking {
        val name = "FSCM-test-${UUID.randomUUID()}.png"
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        saveManufacturerPaymentQr(context, bitmap, name, null)
        val resolver = context.contentResolver
        val gallery = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val row = requireNotNull(resolver.query(gallery, arrayOf("_id", "relative_path", "is_pending"), "_display_name = ?", arrayOf(name), null))
        row.use {
            assertTrue(it.moveToFirst())
            val uri = android.content.ContentUris.withAppendedId(gallery, it.getLong(0))
            try {
                assertEquals("Pictures/FSCM/", it.getString(1))
                assertEquals(0, it.getInt(2))
                assertTrue(bitmap.sameAs(resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream) }))
            } finally {
                resolver.delete(uri, null, null)
            }
        }
        Unit
    }
}
