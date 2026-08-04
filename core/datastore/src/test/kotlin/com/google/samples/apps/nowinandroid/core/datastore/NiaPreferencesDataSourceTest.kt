/*
 * Copyright 2022 The Android Open Source Project
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

package com.google.samples.apps.nowinandroid.core.datastore

import com.google.samples.apps.nowinandroid.core.datastore.test.InMemoryDataStore
import com.google.samples.apps.nowinandroid.core.model.data.DisplayMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NiaPreferencesDataSourceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var subject: NiaPreferencesDataSource

    @Before
    fun setup() {
        subject = NiaPreferencesDataSource(InMemoryDataStore(UserPreferences.getDefaultInstance()))
    }

    @Test
    fun shouldHideOnboardingIsFalseByDefault() = testScope.runTest {
        assertFalse(subject.userData.first().shouldHideOnboarding)
    }

    @Test
    fun userShouldHideOnboardingIsTrueWhenSet() = testScope.runTest {
        subject.setShouldHideOnboarding(true)
        assertTrue(subject.userData.first().shouldHideOnboarding)
    }

    @Test
    fun userShouldHideOnboarding_unfollowsLastTopic_shouldHideOnboardingIsFalse() =
        testScope.runTest {
            // Given: user completes onboarding by selecting a single topic.
            subject.setTopicIdFollowed("1", true)
            subject.setShouldHideOnboarding(true)

            // When: they unfollow that topic.
            subject.setTopicIdFollowed("1", false)

            // Then: onboarding should be shown again
            assertFalse(subject.userData.first().shouldHideOnboarding)
        }

    @Test
    fun userShouldHideOnboarding_unfollowsAllTopics_shouldHideOnboardingIsFalse() =
        testScope.runTest {
            // Given: user completes onboarding by selecting several topics.
            subject.setFollowedTopicIds(setOf("1", "2"))
            subject.setShouldHideOnboarding(true)

            // When: they unfollow those topics.
            subject.setFollowedTopicIds(emptySet())

            // Then: onboarding should be shown again
            assertFalse(subject.userData.first().shouldHideOnboarding)
        }

    @Test
    fun shouldUseDynamicColorFalseByDefault() = testScope.runTest {
        assertFalse(subject.userData.first().useDynamicColor)
    }

    @Test
    fun userShouldUseDynamicColorIsTrueWhenSet() = testScope.runTest {
        subject.setDynamicColorPreference(true)
        assertTrue(subject.userData.first().useDynamicColor)
    }

    @Test
    fun displayModeDefaultsToFull() = testScope.runTest {
        assertEquals(DisplayMode.FULL, subject.userData.first().displayMode)
    }

    @Test
    fun displayModePersistsCompactPreference() = testScope.runTest {
        subject.setDisplayMode(DisplayMode.COMPACT)

        assertEquals(DisplayMode.COMPACT, subject.userData.first().displayMode)
    }

    @Test
    fun printDefaultsPersistNodeAndTemplateIndependently() = testScope.runTest {
        subject.setEdgePrintPreferences(7, "sku_60x40", "box_100x150", "T")

        val userData = subject.userData.first()
        assertEquals(7, userData.defaultEdgePrintNodeId)
        assertEquals("sku_60x40", userData.defaultSkuPrintTemplateCode)
        assertEquals(7, userData.defaultEdgePrintNodeId)
        assertEquals("box_100x150", userData.defaultBoxMarkPrintTemplateCode)
    }

    @Test
    fun legacyNodeOnlyDefaultsKeepNodeAndUseBlankTemplateCode() = testScope.runTest {
        val legacySubject = NiaPreferencesDataSource(
            InMemoryDataStore(
                userPreferences {
                    defaultSkuPrintNodeId = 7
                    defaultBoxMarkPrintNodeId = 42
                },
            ),
        )

        val userData = legacySubject.userData.first()
        assertEquals(7, userData.defaultEdgePrintNodeId)
        assertEquals("", userData.defaultSkuPrintTemplateCode)
        assertEquals("", userData.defaultBoxMarkPrintTemplateCode)
    }
}
