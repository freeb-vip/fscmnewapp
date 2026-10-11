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

import android.view.KeyEvent
import android.view.Window
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.google.samples.apps.nowinandroid.fscm.device.ScannerManager

val LocalScannerManager = staticCompositionLocalOf<ScannerManager?> { null }

/** Track editing without inspecting the input request or recording any text. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScannerInputProvider(scanner: ScannerManager, content: @Composable () -> Unit) {
    val interceptor = remember(scanner) {
        PlatformTextInputInterceptor { request, next ->
            val session = Any()
            scanner.setTextInputSession(session, true)
            try {
                next.startInputMethod(request)
            } finally {
                scanner.setTextInputSession(session, false)
            }
        }
    }
    CompositionLocalProvider(LocalScannerManager provides scanner) {
        InterceptPlatformTextInput(interceptor, content)
    }
}

@Composable
fun ScanAwareBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val scanner = LocalScannerManager.current
    BackHandler(enabled) {
        if (scanner?.consumeBackEventAfterScan() != true) {
            scanner?.recordNavigation("system_back")
            onBack()
        }
    }
}

/** Override scanner availability for pages whose listener remains installed during editing. */
@Composable
fun ScannerInputScope(enabled: Boolean) {
    val scanner = LocalScannerManager.current
    val owner = remember { Any() }
    SideEffect { scanner?.setScanScope(owner, enabled) }
    DisposableEffect(scanner, owner) {
        onDispose { scanner?.setScanScope(owner, null) }
    }
}

/** Dialogs own a separate Window and do not dispatch keys through MainActivity. */
@Composable
private fun ScannerDialogProtection(onDismissRequest: () -> Unit, dismissOnBackPress: Boolean) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val canDismiss by rememberUpdatedState(dismissOnBackPress)
    val scanner = LocalScannerManager.current
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(scanner, window) {
        val previous = window?.callback
        val callback = if (previous != null && scanner != null) {
            object : Window.Callback by previous {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (scanner.handleKeyEvent(event, window.currentFocus?.onCheckIsTextEditor() == true)) return true
                    return previous.dispatchKeyEvent(event)
                }
            }
        } else {
            null
        }
        // ComponentDialog also receives back callbacks without a KeyEvent on newer Android.
        val backCallback = if (scanner != null && previous is OnBackPressedDispatcherOwner) {
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!scanner.consumeBackEventAfterScan() && canDismiss) {
                        scanner.recordNavigation("dialog_system_back")
                        dismiss()
                    }
                }
            }.also { previous.onBackPressedDispatcher.addCallback(it) }
        } else {
            null
        }
        if (callback != null && window != null) window.callback = callback
        onDispose {
            backCallback?.remove()
            if (callback != null && window != null && window.callback === callback) window.callback = previous
        }
    }
}

@Composable
fun ScanAwareDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties) {
        ScannerDialogProtection(onDismissRequest, properties.dismissOnBackPress)
        content()
    }
}

@Composable
fun ScanAwareAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ScannerDialogProtection(onDismissRequest, properties.dismissOnBackPress)
            confirmButton()
        },
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}
