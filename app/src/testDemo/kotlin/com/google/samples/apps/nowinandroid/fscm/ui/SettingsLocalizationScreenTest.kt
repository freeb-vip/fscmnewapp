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

import android.app.Application
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.core.designsystem.theme.FscmTheme
import com.google.samples.apps.nowinandroid.core.model.data.DisplayMode
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import com.google.samples.apps.nowinandroid.fscm.data.NamespaceProfile
import com.google.samples.apps.nowinandroid.fscm.data.OperationsWorkbench
import com.google.samples.apps.nowinandroid.fscm.data.SessionState
import com.google.samples.apps.nowinandroid.fscm.data.WorkbenchPresentation
import com.google.samples.apps.nowinandroid.fscm.device.TerminalFindState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals

/** Screen fixtures verify translations without a network connection or a signed-in account. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLocalizationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val languageTag = mutableStateOf("en")
    private val appTitles = listOf(
        "receiving" to R.string.local_app_receiving_title,
        "inbound_management" to R.string.local_app_inbound_management_title,
        "central_receiving" to R.string.local_app_central_receiving_title,
        "product_counting" to R.string.local_app_product_counting_title,
    )
    private val presentation = WorkbenchPresentation(
        homeAppKeys = appTitles.map { it.first },
        apps = appTitles.map { (key, _) -> MobileAppEntry(key = key, title = "服务端中文名称") },
    )
    private val displayPolicy = fscmDisplayPolicy(DisplayMode.COMPACT, 320, 1.3f)

    @Test
    fun warehouseHomeTitlesFollowLocaleChangesForCachedServerApps() {
        var opened = ""
        compose.setContent {
            LocalizedContent {
                WarehouseStyleScreen(SessionState(), presentation, LoadState(), {}, {}, { opened = it.key })
            }
        }
        assertAppTitlesAcrossLocales()
        scrollTo(text(R.string.local_app_product_counting_title))
        compose.onNodeWithText(text(R.string.local_app_product_counting_title)).performClick()
        compose.runOnIdle { assertEquals("product_counting", opened) }
    }

    @Test
    fun operationsHomeDoesNotShowAppTilesAcrossLocales() {
        compose.setContent {
            LocalizedContent {
                OperationsStyleScreen(
                    session = SessionState(),
                    state = LoadState(data = OperationsWorkbench()), realtimeState = RealtimeUiState(),
                    onRealtime = {}, onRealtimeSelect = {}, onSearch = {}, onDaily = {}, onTodos = {},
                    onTodo = {}, onRefresh = {},
                )
            }
        }
        listOf("en", "zh-Hans", "th", "my").forEach { tag ->
            compose.runOnIdle { languageTag.value = tag }
            appTitles.forEach { (_, title) -> compose.onNodeWithText(text(title)).assertDoesNotExist() }
            compose.onNodeWithText(text(R.string.style_common_work)).assertDoesNotExist()
        }
    }

    @Test
    fun unknownAppKeepsItsConfiguredName() {
        compose.setContent {
            LocalizedContent { Text(localizedAppTitle(MobileAppEntry(key = "custom_app", title = "Custom team app"))) }
        }
        listOf("en", "zh-Hans", "th", "my").forEach { tag ->
            compose.runOnIdle { languageTag.value = tag }
            compose.onNodeWithText("Custom team app").assertIsDisplayed()
        }
    }

    @Test
    fun primarySettingsAreDirectlyUsableAndAdvancedSettingsOpenSeparately() {
        val language = mutableStateOf(AppLanguage.System)
        val displayMode = mutableStateOf(DisplayMode.FULL)
        val showMore = mutableStateOf(false)
        var updateChecks = 0
        var switchedWorkspace: Long? = null
        val warehouses = listOf(NamespaceProfile(id = 7, name = "Demo warehouse A"), NamespaceProfile(id = 8, name = "Demo warehouse B"))
        var opened: FeatureRoute? = null
        compose.setContent {
            LocalizedContent {
                if (showMore.value) {
                    MoreSettingsScreen(TerminalFindState(), displayPolicy, { opened = it })
                } else {
                    ProfileScreen(
                        onOpenFeature = {
                            opened = it
                            showMore.value = true
                        },
                        session = SessionState(namespaces = warehouses, currentNamespace = warehouses.first()),
                        onLogout = {}, onSwitchNamespace = { switchedWorkspace = it },
                        language = language.value, onUpdateLanguage = { language.value = it },
                        displayMode = displayMode.value, onUpdateDisplayMode = { displayMode.value = it },
                        onCheckForUpdate = { updateChecks++ }, displayPolicy = displayPolicy,
                    )
                }
            }
        }
        compose.onNodeWithText(text(R.string.profile_workspace)).assertIsDisplayed()
        compose.onNodeWithText("Demo warehouse B").performClick()
        compose.runOnIdle { assertEquals(8L, switchedWorkspace) }
        scrollTo(text(R.string.settings_display_mode))
        compose.onNodeWithText(text(R.string.settings_display_mode)).performClick()
        compose.onNodeWithText(text(R.string.display_mode_compact)).performClick()
        compose.runOnIdle { assertEquals(DisplayMode.COMPACT, displayMode.value) }
        scrollTo(text(R.string.app_update))
        compose.onNodeWithText(text(R.string.app_update)).performClick()
        compose.runOnIdle { assertEquals(1, updateChecks) }
        scrollTo(text(R.string.settings_language))
        compose.onNodeWithText(text(R.string.settings_language)).performClick()
        compose.onNodeWithText("ไทย").performClick()
        compose.runOnIdle {
            assertEquals(AppLanguage.Thai, language.value)
            languageTag.value = "th"
        }
        compose.onNodeWithText(text(R.string.feature_pda_settings)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.terminal_find_title)).assertDoesNotExist()
        saveImage("primary-settings-th-large-font")
        scrollTo(text(R.string.settings_more))
        compose.onNodeWithText(text(R.string.settings_more)).performClick()
        compose.runOnIdle {
            assertEquals(FeatureRoute.AppSettings, opened)
            languageTag.value = "zh-Hans"
        }
        compose.onNodeWithText(text(R.string.settings_language)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_display_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.app_update)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.feature_pda_settings)).performClick()
        compose.runOnIdle { assertEquals(FeatureRoute.PdaSettings, opened) }
        compose.onNodeWithText(text(R.string.feature_edge_nodes)).performClick()
        compose.runOnIdle { assertEquals(FeatureRoute.EdgeNodes, opened) }
        scrollTo(text(R.string.terminal_find_title))
        compose.onNodeWithText(text(R.string.terminal_find_title)).assertIsDisplayed()
        saveImage("more-settings-zh-large-font")
    }

    private fun assertAppTitlesAcrossLocales() {
        listOf("en", "zh-Hans", "th", "my").forEach { tag ->
            compose.runOnIdle { languageTag.value = tag }
            appTitles.forEach { (_, resource) ->
                scrollTo(text(resource))
                compose.onNodeWithText(text(resource)).assertIsDisplayed()
            }
            compose.onNodeWithText("服务端中文名称").assertDoesNotExist()
        }
    }

    private fun scrollTo(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
    }

    private fun configuration() = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(languageTag.value))
        screenWidthDp = 320
        fontScale = 1.3f
    }

    private fun text(resource: Int): String = context.createConfigurationContext(configuration()).getString(resource)

    @Composable
    private fun LocalizedContent(content: @Composable () -> Unit) {
        val configuration = configuration()
        val localized = context.createConfigurationContext(configuration)
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides configuration,
            LocalDensity provides Density(density.density, configuration.fontScale),
        ) { FscmTheme { content() } }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun saveImage(name: String) {
        val directory = File("build/outputs/settings-localization").apply { mkdirs() }
        val file = File(directory, "$name.png")
        // Review previews stay in build outputs; they are not screenshot baselines.
        compose.onRoot().captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
