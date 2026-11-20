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

package com.google.samples.apps.nowinandroid.fscm.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineShippingPhotoTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun cameraOrientationIsAppliedAndRetriesReuseIdenticalBytes() {
        val photo = folder.newFile("packing_photo.jpg")
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val prepared = normalizeShippingPhoto(photo)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(prepared.path, options)
        assertEquals(60, options.outWidth)
        assertEquals(120, options.outHeight)
        val bytes = prepared.readBytes()
        // The source may be changed by camera apps; a retry must keep the exact
        // already-submitted content associated with the photo idempotency ID.
        photo.writeText("changed source")
        assertContentEquals(bytes, normalizeShippingPhoto(photo).readBytes())
    }

    @Test
    fun highDetailCameraPhotoFitsByteBudget() {
        val photo = folder.newFile("shipping_large.jpg")
        val bitmap = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or -0x1000000 }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        bitmap.recycle()
        assertTrue(photo.length() > SHIPPING_PHOTO_TARGET_BYTES)
        val result = normalizeShippingPhoto(photo)
        assertTrue(result.length() in 1..SHIPPING_PHOTO_TARGET_BYTES.toLong())
        assertTrue(BitmapFactory.decodeFile(result.path).width >= 512)
    }

    @Test
    fun resumeStartsAtAcknowledgedOffsetAndCompletedResponseSkipsTransfer() = runTest {
        val file = folder.newFile("prepared").apply { writeBytes(ByteArray(11) { it.toByte() }) }
        val completed = ShippingPhoto("uuid", "shipping")
        val offsets = mutableListOf<Long>()
        val result = resumeShippingPhoto(file, 4, ShippingPhotoUpload(4)) { offset, bytes ->
            offsets.add(offset)
            val end = offset + bytes.size
            assertContentEquals(file.readBytes().copyOfRange(offset.toInt(), end.toInt()), bytes)
            ShippingPhotoUpload(end, completed.takeIf { end == file.length() })
        }
        assertEquals(completed, result)
        assertEquals(listOf(4L, 8L), offsets)
        assertEquals(completed, resumeShippingPhoto(file, 4, ShippingPhotoUpload(file.length(), completed)) { _, _ -> error("Already uploaded") })
    }

    @Test
    fun corruptCameraFileDoesNotBecomeAnUpload() {
        val photo = folder.newFile("broken.jpg").apply { writeText("not an image") }
        assertFailsWith<IllegalArgumentException> { normalizeShippingPhoto(photo) }
        assertFalse(java.io.File(photo.path + ".upload").exists())
    }
}
