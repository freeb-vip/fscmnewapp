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

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import kotlinx.serialization.json.JsonObject
import java.util.Locale

@Entity(
    tableName = "cross_order_catalog_boxes",
    primaryKeys = ["userId", "namespaceId", "warehouseId", "boxPlanId"],
    indices = [
        Index(value = ["userId", "namespaceId", "warehouseId", "labelCode"]),
        Index(value = ["userId", "namespaceId", "warehouseId", "boxUid"]),
        Index(value = ["userId", "namespaceId", "warehouseId", "boxNo"]),
    ],
)
data class CrossOrderCatalogBox(
    val userId: Long,
    val namespaceId: Long,
    val warehouseId: Long,
    val boxPlanId: Long,
    val labelCode: String,
    val boxUid: String,
    val boxNo: String,
    val payload: String,
)

internal fun JsonObject.toCatalogBox(user: Long, namespace: Long, warehouse: Long) = CrossOrderCatalogBox(
    user,
    namespace,
    warehouse,
    crossID("box_plan_id"),
    crossText("label_code").uppercase(Locale.ROOT),
    crossText("box_uid").uppercase(Locale.ROOT),
    crossText("box_no").uppercase(Locale.ROOT),
    toString(),
)

@Dao
interface CrossOrderCatalogDao {
    @Insert
    suspend fun insert(boxes: List<CrossOrderCatalogBox>)

    @Query("DELETE FROM cross_order_catalog_boxes WHERE userId=:user AND namespaceId=:namespace AND warehouseId=:warehouse")
    suspend fun clear(user: Long, namespace: Long, warehouse: Long)

    @Query(
        """
        SELECT * FROM cross_order_catalog_boxes WHERE userId=:user AND namespaceId=:namespace AND warehouseId=:warehouse AND labelCode IN (:codes)
        UNION
        SELECT * FROM cross_order_catalog_boxes WHERE userId=:user AND namespaceId=:namespace AND warehouseId=:warehouse AND boxUid IN (:codes)
        UNION
        SELECT * FROM cross_order_catalog_boxes WHERE userId=:user AND namespaceId=:namespace AND warehouseId=:warehouse AND boxNo IN (:codes)
        LIMIT 2
        """,
    )
    suspend fun matches(user: Long, namespace: Long, warehouse: Long, codes: List<String>): List<CrossOrderCatalogBox>
}
