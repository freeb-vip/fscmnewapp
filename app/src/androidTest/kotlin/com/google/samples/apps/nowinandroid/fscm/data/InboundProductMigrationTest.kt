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

class InboundProductMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "inbound-product-migration-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration9To10_preservesInboundWorkAndAddsProductCache() {
        open(version = 9, createVersion9Schema = true).use { helper ->
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO local_inbound_sessions (userId, namespaceId, sessionId, sessionCode) VALUES (7, 9, 11, 'RS-11')",
            )
            database.execSQL("INSERT INTO local_inbound_snapshots (userId, namespaceId, sessionId, boxPlanId) VALUES (7, 9, 11, 101)")
            database.execSQL("INSERT INTO local_inbound_batches (id, sessionId) VALUES ('batch-1', 11)")
            database.execSQL("INSERT INTO local_inbound_items (requestId, batchId, status) VALUES ('request-1', 'batch-1', 'pending')")
            database.execSQL("INSERT INTO local_warehouse_locations (userId, namespaceId, locationId, code) VALUES (7, 9, 201, 'A-01')")
        }

        open(version = 10, createVersion9Schema = false).use { helper ->
            val database = helper.writableDatabase
            assertTrue(
                database.columns("local_inbound_sessions").containsAll(
                    listOf("productCacheComplete", "productCacheAt", "lastOpenedAt"),
                ),
            )
            database.query(
                "SELECT sessionCode, productCacheComplete, productCacheAt, lastOpenedAt FROM local_inbound_sessions WHERE sessionId = 11",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RS-11", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getLong(2))
                assertEquals(0, cursor.getLong(3))
            }
            assertEquals(1, database.count("local_inbound_snapshots"))
            assertEquals(1, database.count("local_inbound_batches"))
            assertEquals(1, database.count("local_inbound_items"))
            assertEquals(1, database.count("local_warehouse_locations"))
            assertTrue(database.columns("local_inbound_product_summaries").contains("pendingBoxCount"))
        }
    }

    private fun open(version: Int, createVersion9Schema: Boolean): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            if (createVersion9Schema) db.createVersion9Schema()
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            if (oldVersion == 9 && newVersion == 10) {
                                FscmDatabaseModule.MIGRATION_9_10.migrate(db)
                            }
                        }
                    },
                )
                .build(),
        )

    private fun SupportSQLiteDatabase.createVersion9Schema() {
        execSQL(
            "CREATE TABLE local_inbound_sessions (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, sessionCode TEXT NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId))",
        )
        execSQL("CREATE TABLE local_inbound_snapshots (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, boxPlanId INTEGER NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId, boxPlanId))")
        execSQL("CREATE TABLE local_inbound_batches (id TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL)")
        execSQL("CREATE TABLE local_inbound_items (requestId TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, status TEXT NOT NULL)")
        execSQL("CREATE TABLE local_warehouse_locations (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, locationId INTEGER NOT NULL, code TEXT NOT NULL, PRIMARY KEY(userId, namespaceId, locationId))")
    }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
