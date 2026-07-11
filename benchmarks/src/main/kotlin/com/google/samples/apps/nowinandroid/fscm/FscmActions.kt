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

package com.google.samples.apps.nowinandroid.fscm

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import com.google.samples.apps.nowinandroid.dumpWindowHierarchy

fun MacrobenchmarkScope.fscmWaitForLogin() {
    device.wait(Until.hasObject(By.text("登录")), 5_000)
}

fun MacrobenchmarkScope.fscmOpenAppsTabIfLoggedIn() {
    device.findObject(By.text("应用"))?.click()
    device.waitForIdle()
}

fun MacrobenchmarkScope.fscmOpenReceivingIfAvailable() {
    device.findObject(By.text("仓库收货"))?.click()
    device.waitForIdle()
    device.findObject(By.text("扫码收货"))?.click()
    device.waitForIdle()
}

fun MacrobenchmarkScope.fscmScrollPrimaryScreen() {
    val scrollable = device.findObject(By.scrollable(true)) ?: return
    scrollable.setGestureMargin(device.displayWidth / 5)
    scrollable.fling(Direction.DOWN)
    device.waitForIdle()
    scrollable.fling(Direction.UP)
    device.waitForIdle()
}

fun MacrobenchmarkScope.fscmAssertStarted() {
    if (!device.wait(Until.hasObject(By.textContains("FSCM")), 5_000) &&
        !device.wait(Until.hasObject(By.text("登录")), 1_000)
    ) {
        throw AssertionError("FSCM app did not reach a known screen:\n${device.dumpWindowHierarchy()}")
    }
}
