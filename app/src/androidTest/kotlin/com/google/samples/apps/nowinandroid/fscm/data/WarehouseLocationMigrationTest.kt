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
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarehouseLocationMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "warehouse-location-migration-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration7To8_preservesInboundRowsAndAddsLocationCache() {
        open(version = 7, createVersion7Schema = true).use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO local_inbound_sessions (userId, namespaceId, sessionId, sessionCode) VALUES (7, 9, 11, 'RS-11')",
            )
        }

        open(version = 8, createVersion7Schema = false).use { helper ->
            val database = helper.writableDatabase
            assertTrue(database.hasTable("local_warehouse_locations"))
            assertTrue(database.hasTable("warehouse_location_cache_meta"))
            assertTrue(database.columns("local_inbound_sessions").containsAll(listOf("warehouseCode", "warehouseName")))
            assertTrue(database.columns("local_inbound_batches").containsAll(listOf("warehouseLocationId", "locationName", "zoneName")))
            assertTrue(database.columns("local_inbound_items").containsAll(listOf("localWarehouseLocationId", "remoteWarehouseLocationId")))
            database.query("SELECT sessionCode, warehouseCode FROM local_inbound_sessions WHERE sessionId = 11").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RS-11", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
        }
    }

    private fun open(version: Int, createVersion7Schema: Boolean): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            if (createVersion7Schema) db.createVersion7Schema()
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            if (oldVersion == 7 && newVersion == 8) {
                                FscmDatabaseModule.MIGRATION_7_8.migrate(db)
                            }
                        }
                    },
                )
                .build(),
        )

    private fun SupportSQLiteDatabase.createVersion7Schema() {
        execSQL("CREATE TABLE local_inbound_sessions (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, sessionCode TEXT NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId))")
        execSQL("CREATE TABLE local_inbound_snapshots (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, boxPlanId INTEGER NOT NULL, locationCode TEXT NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId, boxPlanId))")
        execSQL("CREATE TABLE local_inbound_batches (id TEXT NOT NULL PRIMARY KEY, locationCode TEXT NOT NULL)")
        execSQL("CREATE TABLE local_inbound_items (requestId TEXT NOT NULL PRIMARY KEY, localLocationCode TEXT NOT NULL, remoteLocationCode TEXT NOT NULL)")
    }

    private fun SupportSQLiteDatabase.hasTable(name: String): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
}
