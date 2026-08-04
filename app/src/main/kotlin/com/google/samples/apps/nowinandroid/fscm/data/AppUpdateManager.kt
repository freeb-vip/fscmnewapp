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

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.samples.apps.nowinandroid.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AppUpdateDownloadStatus {
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : AppUpdateDownloadStatus
    data object Successful : AppUpdateDownloadStatus
    data class Failed(val reason: Int) : AppUpdateDownloadStatus
    data object Missing : AppUpdateDownloadStatus
}

class AppUpdateVerificationException(message: String) : IllegalStateException(message)

@Singleton
class AppUpdateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: AppUpdateStore,
) {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    suspend fun enqueue(release: AppUpdateRelease, forceUpdate: Boolean): Long = withContext(Dispatchers.IO) {
        val destination = updateFile(release)
        destination.parentFile?.mkdirs()
        destination.delete()
        cleanupExcept(release.versionCode)
        val request = DownloadManager.Request(Uri.parse(resolveUpdateDownloadUrl(BuildConfig.BACKEND_URL, release.downloadPath)))
            .setTitle("FSCM ${release.versionName}")
            .setDescription("Downloading application update")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
        val downloadId = downloadManager.enqueue(request)
        store.savePending(PendingAppUpdateDownload(downloadId, release, forceUpdate))
        downloadId
    }

    suspend fun pendingDownload(): PendingAppUpdateDownload? = store.pendingDownload()

    fun query(downloadId: Long): AppUpdateDownloadStatus {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return AppUpdateDownloadStatus.Missing
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> AppUpdateDownloadStatus.Successful
                DownloadManager.STATUS_FAILED -> AppUpdateDownloadStatus.Failed(
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                )
                else -> AppUpdateDownloadStatus.Running(
                    downloadedBytes = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    ),
                    totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                )
            }
        }
        return AppUpdateDownloadStatus.Missing
    }

    suspend fun verify(release: AppUpdateRelease): File = withContext(Dispatchers.IO) {
        val file = updateFile(release)
        if (!file.isFile || file.length() != release.sizeBytes) {
            reject(file, "Downloaded APK size does not match the release")
        }
        val checksum = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!checksum.equals(release.checksumSha256, ignoreCase = true)) {
            reject(file, "Downloaded APK checksum is invalid")
        }

        val archive = archivePackageInfo(file)
            ?: reject(file, "Downloaded file is not a valid APK")
        if (archive.packageName != BuildConfig.APPLICATION_ID) {
            reject(file, "Downloaded APK package name does not match this application")
        }
        if (PackageInfoCompat.getLongVersionCode(archive) != release.versionCode) {
            reject(file, "Downloaded APK versionCode does not match the release")
        }
        val installed = installedPackageInfo()
        if (archive.signerDigests().isEmpty() || archive.signerDigests() != installed.signerDigests()) {
            reject(file, "Downloaded APK signature does not match this application")
        }
        file
    }

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(release: AppUpdateRelease): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            updateFile(release),
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun cancel(downloadId: Long, release: AppUpdateRelease) = withContext(Dispatchers.IO) {
        downloadManager.remove(downloadId)
        updateFile(release).delete()
        store.clearPending()
    }

    private fun updateFile(release: AppUpdateRelease): File =
        File(updateDirectory(), "fscm-update-${release.versionCode}.apk")

    private fun updateDirectory(): File {
        val downloads = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "External files directory is unavailable"
        }
        return File(downloads, UPDATE_DIRECTORY)
    }

    private fun cleanupExcept(versionCode: Long) {
        updateDirectory().listFiles()?.forEach { file ->
            if (file.name != "fscm-update-$versionCode.apk") file.delete()
        }
    }

    private fun reject(file: File, message: String): Nothing {
        file.delete()
        throw AppUpdateVerificationException(message)
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? {
        val flags = packageInfoSigningFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        val flags = packageInfoSigningFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                BuildConfig.APPLICATION_ID,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfoSigningFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val UPDATE_DIRECTORY = "updates"
    }
}
