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

class VirtualInboundRebindMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "virtual-inbound-rebind-migration-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration13To14_preservesInboundDataAndInvalidatesLegacySnapshot() {
        open(version = 13, createVersion13Schema = true).use { helper ->
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO local_inbound_sessions (userId, namespaceId, sessionId, sessionCode, snapshotComplete, snapshotAt) VALUES (7, 9, 11, 'RS-11', 1, 123456)",
            )
            database.execSQL(
                "INSERT INTO local_inbound_snapshots (userId, namespaceId, sessionId, boxPlanId, locationCode) VALUES (7, 9, 11, 101, 'FSCM-VIRTUAL-INBOUND')",
            )
        }

        open(version = 14, createVersion13Schema = false).use { helper ->
            val database = helper.writableDatabase
            assertTrue(
                database.columns("local_inbound_sessions").containsAll(
                    listOf("consolidationOrderCode", "consolidationOrderName", "hasVirtualInbound"),
                ),
            )
            assertTrue(database.columns("local_inbound_snapshots").contains("canScanRebind"))
            database.query(
                "SELECT sessionCode, snapshotComplete, snapshotAt, hasVirtualInbound FROM local_inbound_sessions WHERE sessionId = 11",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RS-11", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(0, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
            }
            database.query(
                "SELECT locationCode, canScanRebind FROM local_inbound_snapshots WHERE boxPlanId = 101",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("FSCM-VIRTUAL-INBOUND", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    private fun open(version: Int, createVersion13Schema: Boolean): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            if (createVersion13Schema) db.createVersion13Schema()
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            if (oldVersion == 13 && newVersion == 14) {
                                FscmDatabaseModule.MIGRATION_13_14.migrate(db)
                            }
                        }
                    },
                )
                .build(),
        )

    private fun SupportSQLiteDatabase.createVersion13Schema() {
        execSQL(
            "CREATE TABLE local_inbound_sessions (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, sessionCode TEXT NOT NULL, snapshotComplete INTEGER NOT NULL, snapshotAt INTEGER NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId))",
        )
        execSQL(
            "CREATE TABLE local_inbound_snapshots (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, boxPlanId INTEGER NOT NULL, locationCode TEXT NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId, boxPlanId))",
        )
    }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
}
