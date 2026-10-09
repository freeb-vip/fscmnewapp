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

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.samples.apps.nowinandroid.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@PublishedApi
internal fun fscmRepeatedQueryPath(path: String, params: List<Pair<String, String>>): String =
    path + "?" + params.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8") }"
    }

fun operatingDailyToday(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))

@Serializable
enum class DailyDateMode { Yesterday, Last3, Last7, Last30, Month, Year, Custom }

@Serializable
data class OperatingDailyFilter(
    val dateMode: DailyDateMode = DailyDateMode.Yesterday,
    val startDate: String = "",
    val endDate: String = "",
    val calendar: String = "",
    val allPlatforms: Boolean = true,
    val platforms: Set<String> = emptySet(),
    val allShops: Boolean = true,
    val shopIds: Set<Long> = emptySet(),
) {
    fun range(today: LocalDate = operatingDailyToday()): Pair<String, String>? = runCatching {
        val yesterday = today.minusDays(1)
        val dates = when (dateMode) {
            DailyDateMode.Yesterday -> yesterday to yesterday
            DailyDateMode.Last3 -> yesterday.minusDays(2) to yesterday
            DailyDateMode.Last7 -> yesterday.minusDays(6) to yesterday
            DailyDateMode.Last30 -> yesterday.minusDays(29) to yesterday
            DailyDateMode.Month -> YearMonth.parse(calendar).let { it.atDay(1) to minOf(it.atEndOfMonth(), yesterday) }
            DailyDateMode.Year -> Year.parse(calendar).let { it.atDay(1) to minOf(it.atMonth(12).atEndOfMonth(), yesterday) }
            DailyDateMode.Custom -> LocalDate.parse(startDate) to LocalDate.parse(endDate)
        }
        require(!dates.second.isAfter(yesterday) && !dates.second.isBefore(dates.first))
        require(ChronoUnit.DAYS.between(dates.first, dates.second) <= 366)
        dates.first.toString() to dates.second.toString()
    }.getOrNull()

    fun includesPlatform(platform: String): Boolean = allPlatforms || platform in platforms

    fun visibleShops(directory: List<OperatingDailyShop>): List<OperatingDailyShop> = directory.filter { includesPlatform(it.platform) }

    fun selectionValid(directory: List<OperatingDailyShop>): Boolean =
        (allPlatforms || platforms.isNotEmpty()) &&
            (allShops || visibleShops(directory).any { it.id in shopIds }) &&
            (allPlatforms || directory.any { it.platform in platforms })

    fun overviewQuery(range: Pair<String, String>, page: Int, sort: String): List<Pair<String, String>> = buildList {
        add("startDate" to range.first)
        add("endDate" to range.second)
        add("page" to page.toString())
        add("pageSize" to "20")
        add("sort" to sort)
        add("direction" to "desc")
        if (!allPlatforms) platforms.sorted().forEach { add("platforms" to it) }
        if (!allShops) shopIds.sorted().forEach { add("shopIds" to it.toString()) }
    }
}

@Serializable
data class OperatingDailyCost(val amount: String? = null, val sales: String? = null, val ratio: String? = null, val days: Int = 0)

@Serializable
data class OperatingDailySummary(
    val totalOrders: Long? = null,
    val saleAmount: String? = null,
    val profit: String? = null,
    val profitMargin: String? = null,
    val averageOrderValue: String? = null,
    val days: Int = 0,
    val reportDays: Int = 0,
    val expectedDays: Int = 0,
    val orderDays: Int = 0,
    val profitDays: Int = 0,
    val ratioDays: Int = 0,
    val averageDays: Int = 0,
    val sourceUpdatedAt: String? = null,
    val lastCheckedAt: String? = null,
    val missingDates: Int = 0,
    val productCost: OperatingDailyCost = OperatingDailyCost(),
    val adSpend: OperatingDailyCost = OperatingDailyCost(),
    val packingCost: OperatingDailyCost = OperatingDailyCost(),
    val platformCommission: OperatingDailyCost = OperatingDailyCost(),
)

@Serializable
data class OperatingDailyShop(
    val id: Long,
    val name: String = "",
    val platform: String = "",
    val site: String = "",
    val platformShopId: String = "",
    val sourceShopId: String = "",
    val totalOrders: Long? = null,
    val saleAmount: String? = null,
    val profit: String? = null,
    val profitMargin: String? = null,
    val days: Int = 0,
    val orderDays: Int = 0,
    val profitDays: Int = 0,
    val status: String = "",
) {
    fun metric(sort: String): String? = when (sort) {
        "totalOrders" -> totalOrders?.toString()
        "saleAmount" -> saleAmount
        else -> profit
    }
}

@Serializable
data class OperatingDailyOverview(
    val summary: OperatingDailySummary = OperatingDailySummary(),
    val items: List<OperatingDailyShop> = emptyList(),
    val shops: List<OperatingDailyShop> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class OperatingDailyDay(
    val id: Long = 0,
    val shopId: Long,
    val reportDate: String,
    val totalOrders: Long? = null,
    val saleAmount: String? = null,
    val profit: String? = null,
    val profitMargin: String? = null,
    val averageOrderValue: String? = null,
    val productCost: OperatingDailyCost = OperatingDailyCost(),
    val adSpend: OperatingDailyCost = OperatingDailyCost(),
    val packingCost: OperatingDailyCost = OperatingDailyCost(),
    val platformCommission: OperatingDailyCost = OperatingDailyCost(),
    val status: String = "",
    val settlementStatus: String = "pending",
)

@Serializable
data class OperatingDailyPage(
    val summary: OperatingDailySummary = OperatingDailySummary(),
    val items: List<OperatingDailyDay> = emptyList(),
    val total: Int = 0,
)

interface OperatingDailySource {
    suspend fun overview(filter: OperatingDailyFilter, range: Pair<String, String>, page: Int, sort: String): OperatingDailyOverview
    suspend fun days(shopId: Long, range: Pair<String, String>, page: Int): OperatingDailyPage
}

@Singleton
class OperatingDailyRepository @Inject constructor(private val api: FscmApiClient) : OperatingDailySource {
    override suspend fun overview(filter: OperatingDailyFilter, range: Pair<String, String>, page: Int, sort: String): OperatingDailyOverview =
        api.getWithQuery("operating-profit-daily/overview", filter.overviewQuery(range, page, sort))

    override suspend fun days(shopId: Long, range: Pair<String, String>, page: Int): OperatingDailyPage = api.get(
        "operating-profit-daily/days",
        mapOf("shopId" to shopId.toString(), "startDate" to range.first, "endDate" to range.second, "page" to page.toString(), "pageSize" to "30"),
    )
}

interface OperatingDailyPreferences {
    suspend fun read(scope: String): OperatingDailyFilter
    suspend fun save(scope: String, filter: OperatingDailyFilter)
}

private val Context.operatingDailyDataStore by preferencesDataStore(name = "fscm_operating_daily")

internal fun operatingDailyScope(session: SessionState, environment: String = BuildConfig.BACKEND_URL): String =
    "$environment|${session.user?.id ?: 0}|${session.currentNamespace?.id ?: 0}"

@Singleton
class OperatingDailyFilterStore @Inject constructor(
    @ApplicationContext context: Context,
    @param:FscmJson private val json: Json,
) : OperatingDailyPreferences {
    private val store = context.operatingDailyDataStore

    override suspend fun read(scope: String): OperatingDailyFilter = store.data.first()[stringPreferencesKey(scope)]
        ?.let { json.decodeFromString<OperatingDailyFilter>(it) } ?: OperatingDailyFilter()

    override suspend fun save(scope: String, filter: OperatingDailyFilter) {
        store.edit { it[stringPreferencesKey(scope)] = json.encodeToString(filter) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OperatingDailyModule {
    @Binds abstract fun source(repository: OperatingDailyRepository): OperatingDailySource

    @Binds abstract fun preferences(store: OperatingDailyFilterStore): OperatingDailyPreferences
}
