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

class InboundExceptionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "inbound-exception-migration-${System.nanoTime()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration8To9_preservesInboundRowsAndAddsExceptionFields() {
        open(version = 8, createVersion8Schema = true).use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO local_inbound_sessions (userId, namespaceId, sessionId, sessionCode) VALUES (7, 9, 11, 'RS-11')",
            )
            helper.writableDatabase.execSQL(
                "INSERT INTO local_inbound_snapshots (userId, namespaceId, sessionId, boxPlanId, labelCode) VALUES (7, 9, 11, 101, 'BOX-101')",
            )
        }

        open(version = 9, createVersion8Schema = false).use { helper ->
            val database = helper.writableDatabase
            assertTrue(database.columns("local_inbound_sessions").contains("exceptionCount"))
            assertTrue(
                database.columns("local_inbound_snapshots").containsAll(
                    listOf("receiptScanStatus", "exceptionReason", "remoteScannedBy", "remoteScannedAt"),
                ),
            )
            database.query("SELECT sessionCode, exceptionCount FROM local_inbound_sessions WHERE sessionId = 11").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RS-11", cursor.getString(0))
                assertEquals(-1, cursor.getInt(1))
            }
            database.query("SELECT labelCode, exceptionReason FROM local_inbound_snapshots WHERE boxPlanId = 101").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("BOX-101", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
        }
    }

    private fun open(version: Int, createVersion8Schema: Boolean): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            if (createVersion8Schema) db.createVersion8Schema()
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            if (oldVersion == 8 && newVersion == 9) {
                                FscmDatabaseModule.MIGRATION_8_9.migrate(db)
                            }
                        }
                    },
                )
                .build(),
        )

    private fun SupportSQLiteDatabase.createVersion8Schema() {
        execSQL("CREATE TABLE local_inbound_sessions (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, sessionCode TEXT NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId))")
        execSQL("CREATE TABLE local_inbound_snapshots (userId INTEGER NOT NULL, namespaceId INTEGER NOT NULL, sessionId INTEGER NOT NULL, boxPlanId INTEGER NOT NULL, labelCode TEXT NOT NULL, PRIMARY KEY(userId, namespaceId, sessionId, boxPlanId))")
    }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
}
