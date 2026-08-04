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

class ProductCountingMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "product-counting-migration-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration11To12_createsScopedCountingTablesAndPreservesExistingData() {
        open(version = 11, createVersion11Schema = true).use { helper ->
            helper.writableDatabase.execSQL("INSERT INTO legacy_marker (id, value) VALUES (1, 'kept')")
        }

        open(version = 12, createVersion11Schema = false).use { helper ->
            val database = helper.writableDatabase
            assertTrue(
                database.tables().containsAll(
                    listOf("product_count_tasks", "product_count_entries", "sku_product_index"),
                ),
            )
            assertTrue(
                database.columns("product_count_tasks").containsAll(
                    listOf(
                        "clientTaskId",
                        "userId",
                        "namespaceId",
                        "resetGeneration",
                        "editorDeviceId",
                        "syncStatus",
                    ),
                ),
            )
            assertTrue(
                database.columns("product_count_entries").containsAll(
                    listOf(
                        "clientScanId",
                        "taskClientId",
                        "labelCode",
                        "duplicateConfirmed",
                        "syncStatus",
                    ),
                ),
            )
            assertTrue(
                database.columns("sku_product_index").containsAll(
                    listOf("skuCode", "productCode", "productName"),
                ),
            )
            database.query("SELECT value FROM legacy_marker WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("kept", cursor.getString(0))
            }
        }
    }

    private fun open(version: Int, createVersion11Schema: Boolean): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            if (createVersion11Schema) {
                                db.execSQL(
                                    "CREATE TABLE legacy_marker " +
                                        "(id INTEGER NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                                )
                            }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            if (oldVersion == 11 && newVersion == 12) {
                                FscmDatabaseModule.MIGRATION_11_12.migrate(db)
                            }
                        }
                    },
                )
                .build(),
        )

    private fun SupportSQLiteDatabase.tables(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }
}
