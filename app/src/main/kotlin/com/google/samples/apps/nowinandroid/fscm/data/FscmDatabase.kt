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
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileInputStream
import javax.inject.Singleton

@Entity(
    tableName = "offline_cache",
    indices = [Index(value = ["userId", "namespaceId", "cacheKey"], unique = true)],
)
data class OfflineCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long = 0,
    val namespaceId: Long,
    val cacheKey: String,
    val payload: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "receiving_sessions",
    indices = [Index(value = ["userId", "namespaceId", "remoteId"], unique = true)],
)
data class ReceivingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long = 0,
    val namespaceId: Long,
    val remoteId: Long,
    val code: String,
    val payload: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "pending_sync_operations",
    indices = [
        Index(value = ["namespaceId", "module", "status", "createdAt"]),
        Index(value = ["actorUserId", "namespaceId", "status", "createdAt"]),
    ],
)
data class PendingSyncOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val namespaceId: Long,
    val actorUserId: Long = 0,
    val deviceId: String = "",
    val deviceKeyAlgorithm: String = "",
    val offlineLeaseId: String = "",
    val idempotencyKey: String = "",
    val deviceSignature: String = "",
    val module: String,
    val action: String,
    val resourceId: String,
    val payload: String,
    val status: String = PendingSyncStatus.Pending.value,
    val retryCount: Int = 0,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "local_receiving_batches",
    indices = [
        Index(value = ["userId", "namespaceId", "sessionId"], unique = true),
        Index(value = ["userId", "namespaceId", "isActive"]),
    ],
)
data class LocalReceivingBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val namespaceId: Long,
    val sessionId: Long,
    val sessionCode: String,
    val snapshotAt: Long,
    val isActive: Boolean = false,
    val lastSyncError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "local_receiving_scans",
    indices = [
        Index(value = ["userId", "namespaceId", "sessionId", "labelCode"], unique = true),
        Index(value = ["userId", "namespaceId", "sessionId", "syncStatus", "scannedAt"]),
        Index(value = ["clientScanId"], unique = true),
    ],
)
data class LocalReceivingScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val namespaceId: Long,
    val sessionId: Long,
    val clientScanId: String,
    val labelCode: String,
    val originalRawCode: String,
    val itemsPayload: String,
    val actualReceivedQty: Int,
    val scannedAt: Long = System.currentTimeMillis(),
    val syncStatus: String = LocalReceivingSyncStatus.Pending.value,
    val syncError: String = "",
)

enum class LocalReceivingSyncStatus(val value: String) {
    Pending("pending"),
    Syncing("syncing"),
    Failed("failed"),
    Synced("synced"),
}

enum class PendingSyncStatus(val value: String) {
    Pending("pending"),
    Syncing("syncing"),
    Failed("failed"),
    Conflict("conflict"),
    Rejected("rejected"),
}

@Dao
interface OfflineCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfflineCacheEntity)

    @Query("SELECT * FROM offline_cache WHERE userId = :userId AND namespaceId = :namespaceId AND cacheKey = :cacheKey LIMIT 1")
    suspend fun find(userId: Long, namespaceId: Long, cacheKey: String): OfflineCacheEntity?

    @Query("DELETE FROM offline_cache WHERE userId = :userId AND namespaceId = :namespaceId AND cacheKey LIKE :cacheKeyPrefix || '%' AND updatedAt < :cutoff")
    suspend fun deleteOlderThan(userId: Long, namespaceId: Long, cacheKeyPrefix: String, cutoff: Long)

    @Query(
        "DELETE FROM offline_cache WHERE id IN (" +
            "SELECT id FROM offline_cache WHERE userId = :userId AND namespaceId = :namespaceId AND cacheKey LIKE :cacheKeyPrefix || '%' " +
            "ORDER BY updatedAt DESC LIMIT -1 OFFSET :maxEntries)",
    )
    suspend fun trimToNewest(userId: Long, namespaceId: Long, cacheKeyPrefix: String, maxEntries: Int)

    @Query("UPDATE OR IGNORE offline_cache SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacy(userId: Long, namespaceId: Long)
}

@Dao
interface ReceivingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<ReceivingSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ReceivingSessionEntity)

    @Query("SELECT * FROM receiving_sessions WHERE userId = :userId AND namespaceId = :namespaceId ORDER BY updatedAt DESC")
    suspend fun sessions(userId: Long, namespaceId: Long): List<ReceivingSessionEntity>

    @Query("SELECT * FROM receiving_sessions WHERE userId = :userId AND namespaceId = :namespaceId AND remoteId = :id LIMIT 1")
    suspend fun session(userId: Long, namespaceId: Long, id: Long): ReceivingSessionEntity?

    @Query("UPDATE OR IGNORE receiving_sessions SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacy(userId: Long, namespaceId: Long)
}

@Dao
interface PendingSyncDao {
    @Insert
    suspend fun insert(entity: PendingSyncOperationEntity): Long

    @Query("SELECT * FROM pending_sync_operations WHERE namespaceId = :namespaceId AND status IN ('pending', 'syncing', 'failed') ORDER BY createdAt ASC")
    suspend fun pending(namespaceId: Long): List<PendingSyncOperationEntity>

    @Query("SELECT * FROM pending_sync_operations WHERE status IN ('pending', 'syncing', 'failed') ORDER BY createdAt ASC")
    suspend fun pendingForSync(): List<PendingSyncOperationEntity>

    @Query("SELECT * FROM pending_sync_operations WHERE namespaceId = :namespaceId AND actorUserId = :actorUserId AND module = :module AND status IN ('pending', 'syncing', 'failed', 'conflict', 'rejected') ORDER BY createdAt DESC")
    suspend fun pendingForModule(namespaceId: Long, actorUserId: Long, module: String): List<PendingSyncOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_sync_operations WHERE namespaceId = :namespaceId AND actorUserId = :actorUserId AND module = :module AND action = :action AND resourceId = :resourceId AND payload = :payload AND status IN ('pending', 'syncing', 'failed')")
    suspend fun duplicateCount(
        namespaceId: Long,
        actorUserId: Long,
        module: String,
        action: String,
        resourceId: String,
        payload: String,
    ): Int

    @Query("SELECT COUNT(*) FROM pending_sync_operations WHERE namespaceId = :namespaceId AND actorUserId = :actorUserId AND module = :module AND action = :action AND resourceId = :resourceId AND status IN ('pending', 'syncing', 'failed')")
    suspend fun duplicateResourceCount(
        namespaceId: Long,
        actorUserId: Long,
        module: String,
        action: String,
        resourceId: String,
    ): Int

    @Query("SELECT COUNT(*) FROM pending_sync_operations WHERE status IN ('pending', 'failed', 'conflict', 'rejected')")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM pending_sync_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_sync_operations WHERE id = :id AND namespaceId = :namespaceId")
    suspend fun delete(namespaceId: Long, id: Long)

    @Query(
        "DELETE FROM pending_sync_operations WHERE namespaceId = :namespaceId AND actorUserId = :actorUserId " +
            "AND module = :module AND action = :action AND resourceId = :resourceId " +
            "AND status IN ('pending', 'failed', 'conflict', 'rejected')",
    )
    suspend fun deletePendingResource(
        namespaceId: Long,
        actorUserId: Long,
        module: String,
        action: String,
        resourceId: String,
    ): Int

    @Query("UPDATE pending_sync_operations SET status = 'pending', retryCount = 0, lastError = '', updatedAt = :updatedAt WHERE id = :id AND namespaceId = :namespaceId")
    suspend fun retry(namespaceId: Long, id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pending_sync_operations SET status = :status, retryCount = :retryCount, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: Long,
        status: String,
        retryCount: Int,
        lastError: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE pending_sync_operations SET actorUserId = :userId, deviceId = :deviceId, deviceKeyAlgorithm = :deviceKeyAlgorithm WHERE actorUserId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacy(
        userId: Long,
        namespaceId: Long,
        deviceId: String,
        deviceKeyAlgorithm: String,
    )
}

@Dao
interface LocalReceivingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(entity: LocalReceivingBatchEntity): Long

    @Query("UPDATE local_receiving_batches SET isActive = 0, updatedAt = :updatedAt WHERE userId = :userId AND namespaceId = :namespaceId")
    suspend fun deactivateAll(userId: Long, namespaceId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE local_receiving_batches SET isActive = 1, updatedAt = :updatedAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId")
    suspend fun activate(userId: Long, namespaceId: Long, sessionId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM local_receiving_batches WHERE userId = :userId AND namespaceId = :namespaceId AND isActive = 1 LIMIT 1")
    suspend fun activeBatch(userId: Long, namespaceId: Long): LocalReceivingBatchEntity?

    @Query("SELECT * FROM local_receiving_batches WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId LIMIT 1")
    suspend fun batch(userId: Long, namespaceId: Long, sessionId: Long): LocalReceivingBatchEntity?

    @Query("UPDATE local_receiving_batches SET lastSyncError = :error, updatedAt = :updatedAt WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId")
    suspend fun updateBatchError(userId: Long, namespaceId: Long, sessionId: Long, error: String, updatedAt: Long = System.currentTimeMillis())

    @Insert
    suspend fun insertScan(entity: LocalReceivingScanEntity): Long

    @Query("SELECT * FROM local_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId ORDER BY scannedAt DESC")
    suspend fun scans(userId: Long, namespaceId: Long, sessionId: Long): List<LocalReceivingScanEntity>

    @Query("SELECT * FROM local_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND syncStatus IN ('pending', 'failed') ORDER BY scannedAt ASC")
    suspend fun pendingScans(userId: Long, namespaceId: Long, sessionId: Long): List<LocalReceivingScanEntity>

    @Query("SELECT sessionId FROM local_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND syncStatus IN ('pending', 'failed') GROUP BY sessionId ORDER BY MIN(scannedAt)")
    suspend fun pendingSessionIds(userId: Long, namespaceId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM local_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND syncStatus IN ('pending', 'failed', 'syncing')")
    suspend fun pendingCount(userId: Long, namespaceId: Long, sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM local_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId")
    suspend fun totalCount(userId: Long, namespaceId: Long, sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM local_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND sessionId = :sessionId AND labelCode = :labelCode")
    suspend fun duplicateLabelCount(userId: Long, namespaceId: Long, sessionId: Long, labelCode: String): Int

    @Query("UPDATE local_receiving_scans SET syncStatus = :status, syncError = :error WHERE userId = :userId AND namespaceId = :namespaceId AND clientScanId IN (:clientScanIds)")
    suspend fun updateScanStatuses(userId: Long, namespaceId: Long, clientScanIds: List<String>, status: String, error: String)

    @Query("DELETE FROM local_receiving_scans WHERE userId = :userId AND namespaceId = :namespaceId AND id = :id AND syncStatus <> 'synced'")
    suspend fun deleteUnsyncedScan(userId: Long, namespaceId: Long, id: Long)

    @Query("UPDATE OR IGNORE local_receiving_batches SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacyBatches(userId: Long, namespaceId: Long)

    @Query("UPDATE OR IGNORE local_receiving_scans SET userId = :userId WHERE userId = 0 AND namespaceId = :namespaceId")
    suspend fun adoptLegacyScans(userId: Long, namespaceId: Long)
}

@Database(
    entities = [
        OfflineCacheEntity::class,
        ReceivingSessionEntity::class,
        PendingSyncOperationEntity::class,
        LocalReceivingBatchEntity::class,
        LocalReceivingScanEntity::class,
        LocalInboundSessionEntity::class,
        LocalInboundSnapshotEntity::class,
        LocalInboundBatchEntity::class,
        LocalInboundItemEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class FscmDatabase : RoomDatabase() {
    abstract fun offlineCacheDao(): OfflineCacheDao
    abstract fun receivingDao(): ReceivingDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun localReceivingDao(): LocalReceivingDao
    abstract fun localInboundDao(): LocalInboundDao
}

@Module
@InstallIn(SingletonComponent::class)
object FscmDatabaseModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
        sessionCipher: SessionCipher,
        databaseRecoveryNoticeStore: DatabaseRecoveryNoticeStore,
    ): FscmDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = sessionCipher.databasePassphrase()
        val passphraseBytes = passphrase.concatToString().encodeToByteArray()
        try {
            val migrationResult = SqlCipherDatabaseMigration.migrateIfNeeded(
                context = context,
                databaseName = DATABASE_NAME,
                passphrase = passphraseBytes,
            )
            if (migrationResult == SqlCipherMigrationResult.ResetAfterFailure) {
                databaseRecoveryNoticeStore.markLocalDataReset()
            }
            val factory = SupportOpenHelperFactory(passphraseBytes.copyOf())
            return Room.databaseBuilder(context, FscmDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        } finally {
            passphrase.fill('\u0000')
            passphraseBytes.fill(0)
        }
    }

    @Provides
    fun offlineCacheDao(database: FscmDatabase): OfflineCacheDao = database.offlineCacheDao()

    @Provides
    fun receivingDao(database: FscmDatabase): ReceivingDao = database.receivingDao()

    @Provides
    fun pendingSyncDao(database: FscmDatabase): PendingSyncDao = database.pendingSyncDao()

    @Provides
    fun localReceivingDao(database: FscmDatabase): LocalReceivingDao = database.localReceivingDao()

    @Provides
    fun localInboundDao(database: FscmDatabase): LocalInboundDao = database.localInboundDao()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `local_receiving_batches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `sessionCode` TEXT NOT NULL, `snapshotAt` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `lastSyncError` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_receiving_batches_namespaceId_sessionId` ON `local_receiving_batches` (`namespaceId`, `sessionId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_local_receiving_batches_namespaceId_isActive` ON `local_receiving_batches` (`namespaceId`, `isActive`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `local_receiving_scans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `clientScanId` TEXT NOT NULL, `labelCode` TEXT NOT NULL, `originalRawCode` TEXT NOT NULL, `itemsPayload` TEXT NOT NULL, `actualReceivedQty` INTEGER NOT NULL, `scannedAt` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `syncError` TEXT NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_receiving_scans_namespaceId_sessionId_labelCode` ON `local_receiving_scans` (`namespaceId`, `sessionId`, `labelCode`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_local_receiving_scans_namespaceId_sessionId_syncStatus_scannedAt` ON `local_receiving_scans` (`namespaceId`, `sessionId`, `syncStatus`, `scannedAt`)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_receiving_scans_clientScanId` ON `local_receiving_scans` (`clientScanId`)")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `local_inbound_sessions` (`namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `sessionCode` TEXT NOT NULL, `status` TEXT NOT NULL, `snapshotComplete` INTEGER NOT NULL, `snapshotAt` INTEGER NOT NULL, `totalCount` INTEGER NOT NULL, `inboundedCount` INTEGER NOT NULL, `unassignedCount` INTEGER NOT NULL, `lastSyncAttemptAt` INTEGER NOT NULL, `lastSyncSuccessAt` INTEGER NOT NULL, `lastSyncError` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`, `sessionId`))")
            database.execSQL("CREATE TABLE IF NOT EXISTS `local_inbound_snapshots` (`namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `boxPlanId` INTEGER NOT NULL, `labelCode` TEXT NOT NULL, `boxNo` TEXT NOT NULL, `boxUid` TEXT NOT NULL, `status` TEXT NOT NULL, `inboundStatus` TEXT NOT NULL, `locationCode` TEXT NOT NULL, `eligible` INTEGER NOT NULL, `snapshotAt` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`, `sessionId`, `boxPlanId`))")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_snapshots_namespaceId_sessionId_labelCode` ON `local_inbound_snapshots` (`namespaceId`, `sessionId`, `labelCode`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `local_inbound_batches` (`id` TEXT NOT NULL, `namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `locationCode` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_batches_namespaceId_sessionId_status_createdAt` ON `local_inbound_batches` (`namespaceId`, `sessionId`, `status`, `createdAt`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `local_inbound_items` (`requestId` TEXT NOT NULL, `batchId` TEXT NOT NULL, `namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `boxPlanId` INTEGER NOT NULL, `labelCode` TEXT NOT NULL, `rawCode` TEXT NOT NULL, `localLocationCode` TEXT NOT NULL, `remoteLocationCode` TEXT NOT NULL, `status` TEXT NOT NULL, `errorCode` INTEGER NOT NULL, `errorMessage` TEXT NOT NULL, `correctionRequestId` TEXT NOT NULL, `scannedAt` INTEGER NOT NULL, `syncedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`requestId`))")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_items_batchId` ON `local_inbound_items` (`batchId`)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_inbound_items_namespaceId_labelCode` ON `local_inbound_items` (`namespaceId`, `labelCode`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_items_namespaceId_status_scannedAt` ON `local_inbound_items` (`namespaceId`, `status`, `scannedAt`)")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `offline_cache` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS `index_offline_cache_namespaceId_cacheKey`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_cache_userId_namespaceId_cacheKey` ON `offline_cache` (`userId`, `namespaceId`, `cacheKey`)")
            db.execSQL("ALTER TABLE `receiving_sessions` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS `index_receiving_sessions_namespaceId_remoteId`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receiving_sessions_userId_namespaceId_remoteId` ON `receiving_sessions` (`userId`, `namespaceId`, `remoteId`)")
            db.execSQL("ALTER TABLE `local_receiving_batches` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS `index_local_receiving_batches_namespaceId_sessionId`")
            db.execSQL("DROP INDEX IF EXISTS `index_local_receiving_batches_namespaceId_isActive`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_receiving_batches_userId_namespaceId_sessionId` ON `local_receiving_batches` (`userId`, `namespaceId`, `sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_receiving_batches_userId_namespaceId_isActive` ON `local_receiving_batches` (`userId`, `namespaceId`, `isActive`)")
            db.execSQL("ALTER TABLE `local_receiving_scans` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS `index_local_receiving_scans_namespaceId_sessionId_labelCode`")
            db.execSQL("DROP INDEX IF EXISTS `index_local_receiving_scans_namespaceId_sessionId_syncStatus_scannedAt`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_receiving_scans_userId_namespaceId_sessionId_labelCode` ON `local_receiving_scans` (`userId`, `namespaceId`, `sessionId`, `labelCode`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_receiving_scans_userId_namespaceId_sessionId_syncStatus_scannedAt` ON `local_receiving_scans` (`userId`, `namespaceId`, `sessionId`, `syncStatus`, `scannedAt`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `local_inbound_sessions_v4` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `sessionCode` TEXT NOT NULL, `status` TEXT NOT NULL, `snapshotComplete` INTEGER NOT NULL, `snapshotAt` INTEGER NOT NULL, `totalCount` INTEGER NOT NULL, `inboundedCount` INTEGER NOT NULL, `unassignedCount` INTEGER NOT NULL, `lastSyncAttemptAt` INTEGER NOT NULL, `lastSyncSuccessAt` INTEGER NOT NULL, `lastSyncError` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `sessionId`))")
            db.execSQL("INSERT INTO `local_inbound_sessions_v4` SELECT 0, `namespaceId`, `sessionId`, `sessionCode`, `status`, `snapshotComplete`, `snapshotAt`, `totalCount`, `inboundedCount`, `unassignedCount`, `lastSyncAttemptAt`, `lastSyncSuccessAt`, `lastSyncError`, `updatedAt` FROM `local_inbound_sessions`")
            db.execSQL("DROP TABLE `local_inbound_sessions`")
            db.execSQL("ALTER TABLE `local_inbound_sessions_v4` RENAME TO `local_inbound_sessions`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `local_inbound_snapshots_v4` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `boxPlanId` INTEGER NOT NULL, `labelCode` TEXT NOT NULL, `boxNo` TEXT NOT NULL, `boxUid` TEXT NOT NULL, `status` TEXT NOT NULL, `inboundStatus` TEXT NOT NULL, `locationCode` TEXT NOT NULL, `eligible` INTEGER NOT NULL, `snapshotAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `sessionId`, `boxPlanId`))")
            db.execSQL("INSERT INTO `local_inbound_snapshots_v4` SELECT 0, `namespaceId`, `sessionId`, `boxPlanId`, `labelCode`, `boxNo`, `boxUid`, `status`, `inboundStatus`, `locationCode`, `eligible`, `snapshotAt` FROM `local_inbound_snapshots`")
            db.execSQL("DROP TABLE `local_inbound_snapshots`")
            db.execSQL("ALTER TABLE `local_inbound_snapshots_v4` RENAME TO `local_inbound_snapshots`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_snapshots_userId_namespaceId_sessionId_labelCode` ON `local_inbound_snapshots` (`userId`, `namespaceId`, `sessionId`, `labelCode`)")
            db.execSQL("ALTER TABLE `local_inbound_batches` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS `index_local_inbound_batches_namespaceId_sessionId_status_createdAt`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_batches_userId_namespaceId_sessionId_status_createdAt` ON `local_inbound_batches` (`userId`, `namespaceId`, `sessionId`, `status`, `createdAt`)")
            db.execSQL("ALTER TABLE `local_inbound_items` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS `index_local_inbound_items_namespaceId_labelCode`")
            db.execSQL("DROP INDEX IF EXISTS `index_local_inbound_items_namespaceId_status_scannedAt`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_inbound_items_userId_namespaceId_labelCode` ON `local_inbound_items` (`userId`, `namespaceId`, `labelCode`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_items_userId_namespaceId_status_scannedAt` ON `local_inbound_items` (`userId`, `namespaceId`, `status`, `scannedAt`)")
            db.execSQL("ALTER TABLE `pending_sync_operations` ADD COLUMN `actorUserId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `pending_sync_operations` ADD COLUMN `deviceId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `pending_sync_operations` ADD COLUMN `deviceKeyAlgorithm` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `pending_sync_operations` ADD COLUMN `offlineLeaseId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `pending_sync_operations` ADD COLUMN `idempotencyKey` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `pending_sync_operations` ADD COLUMN `deviceSignature` TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_sync_operations_actorUserId_namespaceId_status_createdAt` ON `pending_sync_operations` (`actorUserId`, `namespaceId`, `status`, `createdAt`)")
        }
    }

    private const val DATABASE_NAME = "fscm.db"
}

internal enum class SqlCipherMigrationResult {
    NotNeeded,
    Migrated,
    ResetAfterFailure,
}

internal object SqlCipherDatabaseMigration {
    @Synchronized
    fun migrateIfNeeded(
        context: Context,
        databaseName: String,
        passphrase: ByteArray,
        beforeExport: () -> Unit = {},
    ): SqlCipherMigrationResult {
        val source = context.getDatabasePath(databaseName).canonicalFile
        if (!source.exists() || !source.hasPlaintextSqliteHeader()) {
            return SqlCipherMigrationResult.NotNeeded
        }

        val parent = checkNotNull(source.parentFile) { "Database directory is unavailable" }
        parent.mkdirs()

        val encrypted = File(parent, "$databaseName.encrypted-migration")
        val backup = File(parent, "$databaseName.plaintext-backup")
        var stage = "cleanup"
        try {
            deleteDatabaseFamily(encrypted)
            deleteDatabaseFamily(backup)

            stage = "checkpoint"
            checkpointPlaintextDatabase(source)
            deleteSidecars(source)

            stage = "create encrypted target"
            SQLiteDatabase.openOrCreateDatabase(
                encrypted,
                passphrase,
                null,
                null,
                null,
            ).use { database ->
                check(database.isDatabaseIntegrityOk) { "Temporary encrypted database is invalid" }
            }

            stage = "open plaintext source"
            val legacy = SQLiteDatabase.openDatabase(
                source.absolutePath,
                ByteArray(0),
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
            )
            val userVersion: Int
            try {
                userVersion = legacy.rawQuery("PRAGMA user_version", emptyArray()).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                beforeExport()
                stage = "attach encrypted target"
                val encryptedPath = encrypted.absolutePath.sqlLiteral()
                val key = passphrase.decodeToString().sqlLiteral()
                legacy.execSQL("ATTACH DATABASE '$encryptedPath' AS encrypted KEY '$key'")

                stage = "export plaintext data"
                legacy.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { cursor ->
                    cursor.moveToFirst()
                }
                legacy.execSQL("PRAGMA encrypted.user_version = $userVersion")
                legacy.execSQL("DETACH DATABASE encrypted")
            } finally {
                legacy.close()
            }

            stage = "verify encrypted target"
            verifyEncryptedDatabase(encrypted, passphrase, userVersion)

            stage = "activate encrypted database"
            check(source.renameTo(backup)) { "Unable to backup plaintext database" }
            if (!encrypted.renameTo(source)) {
                backup.renameTo(source)
                error("Unable to activate encrypted database")
            }

            stage = "verify activated database"
            runCatching { verifyEncryptedDatabase(source, passphrase, userVersion) }
                .onFailure {
                    deleteDatabaseFamily(source)
                    backup.renameTo(source)
                    throw it
                }

            deleteDatabaseFamily(backup)
            deleteSidecars(source)
            Log.i(TAG, "Plaintext database migrated to SQLCipher")
            return SqlCipherMigrationResult.Migrated
        } catch (error: Exception) {
            Log.e(TAG, "Plaintext database migration failed during $stage; resetting local data", error)
            deleteDatabaseFamily(source)
            deleteDatabaseFamily(encrypted)
            deleteDatabaseFamily(backup)
            check(!source.exists()) { "Unable to reset plaintext database after migration failure" }
            return SqlCipherMigrationResult.ResetAfterFailure
        }
    }

    private fun verifyEncryptedDatabase(file: File, passphrase: ByteArray, expectedVersion: Int) {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        ).use { database ->
            check(database.isDatabaseIntegrityOk) { "Encrypted database integrity check failed" }
            val cipherErrors = database.rawQuery("PRAGMA cipher_integrity_check", emptyArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            check(cipherErrors.isEmpty()) { "Encrypted database cipher integrity check failed" }
            val userVersion = database.rawQuery("PRAGMA user_version", emptyArray()).use { cursor ->
                check(cursor.moveToFirst()) { "Encrypted database version is unavailable" }
                cursor.getInt(0)
            }
            check(userVersion == expectedVersion) { "Encrypted database version changed during migration" }
            val requiredTables = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('offline_cache', 'receiving_sessions', 'pending_sync_operations')",
                emptyArray(),
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            check(requiredTables.size == REQUIRED_TABLES) { "Encrypted database schema verification failed" }
        }
    }

    private fun deleteDatabaseFamily(file: File) {
        file.delete()
        deleteSidecars(file)
    }

    private fun deleteSidecars(file: File) {
        SIDECAR_SUFFIXES.forEach { suffix -> File("${file.absolutePath}$suffix").delete() }
    }

    private fun checkpointPlaintextDatabase(file: File) {
        runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray()).use { it.moveToFirst() }
            }
        }
    }

    private fun File.hasPlaintextSqliteHeader(): Boolean = runCatching {
        if (length() < SQLITE_HEADER.size) return@runCatching false
        FileInputStream(this).use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }.getOrDefault(false)

    private fun String.sqlLiteral(): String = replace("'", "''")

    private const val TAG = "FscmDatabaseMigration"
    private const val REQUIRED_TABLES = 3
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
    private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
}
