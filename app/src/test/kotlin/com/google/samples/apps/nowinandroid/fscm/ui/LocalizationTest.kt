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

import com.google.samples.apps.nowinandroid.R
import com.google.samples.apps.nowinandroid.fscm.data.MobileAppEntry
import org.junit.Test
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalizationTest {
    @Test
    fun supportedLanguagesUseCanonicalTags() {
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTag(""))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromLanguageTag("zh"))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromLanguageTag("zh-Hans-CN"))
        assertEquals(AppLanguage.English, AppLanguage.fromLanguageTag("en-US"))
        assertEquals(AppLanguage.Thai, AppLanguage.fromLanguageTag("th-TH"))
        assertEquals(AppLanguage.Burmese, AppLanguage.fromLanguageTag("my-MM"))
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTag("ms"))
    }

    @Test
    fun dynamicApiTextIsPreserved() {
        val message: UiText = UiText.Dynamic("服务端原始消息")
        assertIs<UiText.Dynamic>(message)
        assertEquals("服务端原始消息", message.value)
    }

    @Test
    fun serverConfiguredAppsUseLocalizedTitlesByStableKey() {
        val expected = mapOf(
            "receiving" to R.string.local_app_receiving_title,
            "inbound_management" to R.string.local_app_inbound_management_title,
            "central_receiving" to R.string.local_app_central_receiving_title,
            "box_labels" to R.string.local_app_box_labels_title,
            "profit" to R.string.local_app_profit_title,
        )

        expected.forEach { (key, titleResource) ->
            val serverEntry = MobileAppEntry(key = key, title = "服务端中文名称")
            assertTrue(!serverEntry.localFallback)
            assertEquals(titleResource, localAppTitleResource(serverEntry.key))
        }
    }

    @Test
    fun everySupportedLocaleHasMatchingKeysAndPlaceholders() {
        val resources = resourceDirectory()
        val localeDirectories = listOf("values", "values-b+zh+Hans", "values-th", "values-my")
        val values = localeDirectories.associateWith { readValues(resources.resolve(it)) }
        val base = values.getValue("values")

        values.forEach { (locale, localized) ->
            assertEquals(base.keys, localized.keys, "$locale resource keys differ from the English base")
            base.forEach { (key, text) ->
                assertEquals(placeholders(text), placeholders(localized.getValue(key)), "$locale placeholders differ for $key")
            }
        }

        assertEquals("FSCM", base.getValue("app_name"))
        assertEquals("四海环球", values.getValue("values-b+zh+Hans").getValue("app_name"))
        assertEquals("FSCM", values.getValue("values-th").getValue("app_name"))
        assertEquals("FSCM", values.getValue("values-my").getValue("app_name"))
        assertTrue(base.keys.none { it.startsWith("local_app_") && it.endsWith("_icon") })

        val localizedAppTitles = listOf(
            "local_app_receiving_title",
            "local_app_inbound_management_title",
            "local_app_central_receiving_title",
            "local_app_box_labels_title",
            "local_app_profit_title",
            "local_app_product_management_title",
            "local_app_label_print_title",
            "local_app_pda_settings_title",
        )
        listOf("values-b+zh+Hans", "values-th", "values-my").forEach { locale ->
            localizedAppTitles.forEach { key ->
                assertTrue(values.getValue(locale).getValue(key) != base.getValue(key), "$locale must localize $key")
            }
        }

        val localReceivingKeys = base.keys.filter { key ->
            key.startsWith("business_local_") || key == "business_select_local_receiving_first"
        }
        listOf("values-th", "values-my").forEach { locale ->
            localReceivingKeys.forEach { key ->
                assertTrue(values.getValue(locale).getValue(key) != base.getValue(key), "$locale must localize $key")
            }
        }
    }

    private fun resourceDirectory(): Path {
        val current = Path.of("").toAbsolutePath()
        return sequenceOf(current.resolve("src/main/res"), current.resolve("app/src/main/res"))
            .firstOrNull { it.isDirectory() }
            ?: error("Unable to locate app/src/main/res from $current")
    }

    private fun readValues(directory: Path): Map<String, String> = buildMap {
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".xml") }.forEach { file ->
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
                val children = document.documentElement.childNodes
                for (index in 0 until children.length) {
                    val element = children.item(index) as? Element ?: continue
                    if (element.tagName !in setOf("string", "plurals")) continue
                    val name = element.getAttribute("name")
                    assertTrue(name.isNotBlank(), "Missing resource name in $file")
                    put(name, element.textContent.trim())
                }
            }
        }
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toList()

    private companion object {
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
    }
}
