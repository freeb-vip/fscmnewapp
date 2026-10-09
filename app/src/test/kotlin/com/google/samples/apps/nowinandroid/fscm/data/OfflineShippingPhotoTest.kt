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
    fun corruptCameraFileDoesNotBecomeAnUpload() {
        val photo = folder.newFile("broken.jpg").apply { writeText("not an image") }
        assertFailsWith<IllegalArgumentException> { normalizeShippingPhoto(photo) }
        assertFalse(java.io.File(photo.path + ".upload").exists())
    }
}
