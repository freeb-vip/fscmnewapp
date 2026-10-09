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

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

internal fun removeCollectionMetadataColumns(db: SupportSQLiteDatabase) {
    removeSkuFoldedCode(db)
    db.execSQL("ALTER TABLE local_sku_catalog DROP COLUMN syncedAt")
    listOf("firstSkuCode", "firstSkuName", "firstProductCode", "firstProductName", "firstProductId", "isMixed").forEach {
        db.execSQL("ALTER TABLE local_inbound_snapshots DROP COLUMN $it")
    }
}

internal fun removeSkuFoldedCode(db: SupportSQLiteDatabase) {
    db.execSQL("DROP INDEX index_local_sku_catalog_userId_namespaceId_foldedCode")
    db.execSQL("ALTER TABLE local_sku_catalog DROP COLUMN foldedCode")
}

@RunWith(RobolectricTestRunner::class)
class InboundCollectionMigrationTest {
    @Test
    fun version18PreservesDraftsPendingRowsAndOldSnapshots() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "collection-migration-${java.util.UUID.randomUUID()}"
        val old = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val dao = old.localInboundDao()
            dao.insertBatch(LocalInboundBatchEntity("draft", 7, 9, 11, "A-01", 101))
            dao.insertItem(LocalInboundItemEntity("one", "draft", 7, 9, 11, 1, "BOX-1", "BOX-1", "A-01"))
            dao.insertItem(LocalInboundItemEntity("two", "done", 7, 9, 11, 2, "BOX-2", "BOX-2", "B-01", status = "pending"))
            dao.insertSnapshot(LocalInboundSnapshotEntity(7, 9, 11, 1, "BOX-1", "1", "1", "normal", "pending", "", eligible = true, snapshotAt = 100))
            val before = dao.itemsForSession(7, 9, 11)
            removeCollectionMetadataColumns(old.openHelper.writableDatabase)
            old.openHelper.writableDatabase.version = 17
            old.close()
            val migrated = Room.databaseBuilder(context, FscmDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(FscmDatabaseModule.MIGRATION_17_18, FscmDatabaseModule.MIGRATION_18_19, FscmDatabaseModule.MIGRATION_19_20, FscmDatabaseModule.MIGRATION_20_21).build()
            try {
                assertEquals(before, migrated.localInboundDao().itemsForSession(7, 9, 11))
                val snapshot = migrated.localInboundDao().snapshots(7, 9, 11).single()
                assertEquals("BOX-1", snapshot.labelCode)
                assertEquals("", snapshot.firstProductCode)
                assertEquals("draft", migrated.localInboundDao().batch("draft")?.status)
            } finally {
                migrated.close()
            }
        } finally {
            old.close()
            context.deleteDatabase(name)
        }
    }
}
