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

package com.google.samples.apps.nowinandroid.fscm.device

import android.content.Context
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.google.samples.apps.nowinandroid.core.data.repository.UserDataRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class PrintFailureReason {
    BluetoothPermissionRequired,
    PrinterNotConfigured,
    PrinterNotPaired,
}

class PrintFailureException(val reason: PrintFailureReason) : IllegalStateException()

@Singleton
class PrintManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userDataRepository: UserDataRepository,
) {
    suspend fun getPairedPrinters(): List<BluetoothConnection> = withContext(Dispatchers.IO) {
        if (!PdaPermissions.hasBluetoothRuntimePermissions(context)) return@withContext emptyList()
        BluetoothPrintersConnections().list?.toList() ?: emptyList()
    }

    suspend fun printReceipt(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!PdaPermissions.hasBluetoothRuntimePermissions(context)) {
                return@withContext Result.failure(PrintFailureException(PrintFailureReason.BluetoothPermissionRequired))
            }
            val mac = userDataRepository.userData.first().bluetoothPrinterMac
            if (mac.isBlank()) return@withContext Result.failure(PrintFailureException(PrintFailureReason.PrinterNotConfigured))

            val connection = BluetoothPrintersConnections().list
                ?.find { it.device.address == mac }
                ?: return@withContext Result.failure(PrintFailureException(PrintFailureReason.PrinterNotPaired))

            val printer = EscPosPrinter(connection, 203, 48f, 32)
            printer.printFormattedText(content)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
