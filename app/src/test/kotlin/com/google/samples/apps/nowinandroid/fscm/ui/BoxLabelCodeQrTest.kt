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

import android.graphics.Color
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BoxLabelCodeQrTest {
    @Test
    fun displayedCodeRoundTripsWithCaseAndQuietZone() {
        for (code in listOf("BOX-20261009-000001", "Box-Abc-001", "箱唛-001", "B".repeat(200))) {
            val image = boxLabelCodeQrBitmap(code)
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))))
            assertEquals(code, decoded.text)
            assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
            // A white quiet zone must remain around all four sides for scanning.
            for (index in 0 until image.width) {
                assertEquals(Color.WHITE, image.getPixel(index, 0))
                assertEquals(Color.WHITE, image.getPixel(index, image.height - 1))
                assertEquals(Color.WHITE, image.getPixel(0, index))
                assertEquals(Color.WHITE, image.getPixel(image.width - 1, index))
            }
            image.recycle()
        }
    }

    @Test
    fun blankCodesCannotProduceMisleadingQr() {
        assertFailsWith<IllegalArgumentException> { boxLabelCodeQrBitmap(" ") }
    }
}
