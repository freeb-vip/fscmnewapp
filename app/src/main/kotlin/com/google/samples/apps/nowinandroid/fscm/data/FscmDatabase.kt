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
    val remoteSyncedAt: Long = updatedAt,
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
        LocalInboundProductSummaryEntity::class,
        LocalWarehouseLocationEntity::class,
        WarehouseLocationCacheMetaEntity::class,
        LocalBoxLabelMappingEntity::class,
        CentralReceivingSnapshotMetaEntity::class,
        CentralReceivingReceiptEntity::class,
        CentralReceivingLabelEntity::class,
        CentralReceivingScanEntity::class,
        ReceivingProductProgressEntity::class,
        ReceivingProductProgressMetaEntity::class,
        ProductCountTaskEntity::class,
        ProductCountEntryEntity::class,
        SkuProductIndexEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class FscmDatabase : RoomDatabase() {
    abstract fun offlineCacheDao(): OfflineCacheDao
    abstract fun receivingDao(): ReceivingDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun localReceivingDao(): LocalReceivingDao
    abstract fun localInboundDao(): LocalInboundDao
    abstract fun warehouseLocationDao(): WarehouseLocationDao
    abstract fun boxLabelMappingDao(): BoxLabelMappingDao
    abstract fun centralReceivingDao(): CentralReceivingDao
    abstract fun receivingProductProgressDao(): ReceivingProductProgressDao
    abstract fun productCountingDao(): ProductCountingDao
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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                )
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

    @Provides
    fun warehouseLocationDao(database: FscmDatabase): WarehouseLocationDao = database.warehouseLocationDao()

    @Provides
    fun boxLabelMappingDao(database: FscmDatabase): BoxLabelMappingDao = database.boxLabelMappingDao()

    @Provides
    fun centralReceivingDao(database: FscmDatabase): CentralReceivingDao = database.centralReceivingDao()

    @Provides
    fun receivingProductProgressDao(database: FscmDatabase): ReceivingProductProgressDao =
        database.receivingProductProgressDao()

    @Provides
    fun productCountingDao(database: FscmDatabase): ProductCountingDao = database.productCountingDao()

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

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `local_box_label_mappings` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `mappingId` INTEGER NOT NULL, `supplierOrderBoxPlanId` INTEGER NOT NULL, `supplierLabelCode` TEXT NOT NULL, `supplierBoxNo` TEXT NOT NULL, `supplierBoxUid` TEXT NOT NULL, `jifengBoxNo` TEXT NOT NULL, `jifengQrCodeText` TEXT NOT NULL, `mappingStatus` TEXT NOT NULL, `productCode` TEXT NOT NULL, `productName` TEXT NOT NULL, `skuCode` TEXT NOT NULL, `manufacturerName` TEXT NOT NULL, `caseSpecName` TEXT NOT NULL, `qtyPerCarton` INTEGER NOT NULL, `boxQty` INTEGER NOT NULL, `remoteUpdatedAt` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `mappingId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_box_label_mappings_userId_namespaceId_supplierLabelCode` ON `local_box_label_mappings` (`userId`, `namespaceId`, `supplierLabelCode`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_box_label_mappings_userId_namespaceId_supplierBoxNo` ON `local_box_label_mappings` (`userId`, `namespaceId`, `supplierBoxNo`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_box_label_mappings_userId_namespaceId_supplierBoxUid` ON `local_box_label_mappings` (`userId`, `namespaceId`, `supplierBoxUid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_box_label_mappings_userId_namespaceId_jifengBoxNo` ON `local_box_label_mappings` (`userId`, `namespaceId`, `jifengBoxNo`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_box_label_mappings_userId_namespaceId_jifengQrCodeText` ON `local_box_label_mappings` (`userId`, `namespaceId`, `jifengQrCodeText`)")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `receiving_sessions` ADD COLUMN `remoteSyncedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `receiving_sessions` SET `remoteSyncedAt` = `updatedAt` WHERE `remoteSyncedAt` = 0")
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `central_receiving_snapshot_meta` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `activeGeneration` TEXT NOT NULL, `snapshotAt` INTEGER NOT NULL, `complete` INTEGER NOT NULL, `lastSyncError` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `central_receiving_receipts` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `remoteId` INTEGER NOT NULL, `status` TEXT NOT NULL, `payload` TEXT NOT NULL, `generation` TEXT NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `generation`, `remoteId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_central_receiving_receipts_userId_namespaceId_generation_status` ON `central_receiving_receipts` (`userId`, `namespaceId`, `generation`, `status`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `central_receiving_labels` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `boxPlanId` INTEGER NOT NULL, `centralReceiptId` INTEGER NOT NULL, `centralReceiptItemId` INTEGER NOT NULL, `labelCode` TEXT NOT NULL, `boxUid` TEXT NOT NULL, `boxNo` TEXT NOT NULL, `remoteStatus` TEXT NOT NULL, `signStatus` TEXT NOT NULL, `localStatus` TEXT NOT NULL, `payload` TEXT NOT NULL, `generation` TEXT NOT NULL, `lastLocalScannedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `generation`, `boxPlanId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_central_receiving_labels_userId_namespaceId_generation_centralReceiptId` ON `central_receiving_labels` (`userId`, `namespaceId`, `generation`, `centralReceiptId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_central_receiving_labels_userId_namespaceId_generation_labelCode` ON `central_receiving_labels` (`userId`, `namespaceId`, `generation`, `labelCode`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_central_receiving_labels_userId_namespaceId_generation_boxUid` ON `central_receiving_labels` (`userId`, `namespaceId`, `generation`, `boxUid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_central_receiving_labels_userId_namespaceId_generation_boxNo` ON `central_receiving_labels` (`userId`, `namespaceId`, `generation`, `boxNo`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `central_receiving_scans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clientScanId` TEXT NOT NULL, `userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `boxPlanId` INTEGER NOT NULL, `centralReceiptId` INTEGER NOT NULL, `labelCode` TEXT NOT NULL, `rawCode` TEXT NOT NULL, `scannedAt` INTEGER NOT NULL, `snapshotAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `resultCode` TEXT NOT NULL, `resultMessage` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `syncedAt` INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_central_receiving_scans_clientScanId` ON `central_receiving_scans` (`clientScanId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_central_receiving_scans_userId_namespaceId_status_scannedAt` ON `central_receiving_scans` (`userId`, `namespaceId`, `status`, `scannedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_central_receiving_scans_userId_namespaceId_boxPlanId_scannedAt` ON `central_receiving_scans` (`userId`, `namespaceId`, `boxPlanId`, `scannedAt`)")
        }
    }

    internal val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `local_warehouse_locations` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `locationId` INTEGER NOT NULL, `warehouseId` INTEGER NOT NULL, `warehouseCode` TEXT NOT NULL, `warehouseName` TEXT NOT NULL, `zoneId` INTEGER NOT NULL, `zoneCode` TEXT NOT NULL, `zoneName` TEXT NOT NULL, `code` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `remoteUpdatedAt` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `locationId`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_warehouse_locations_userId_namespaceId_warehouseCode_code` ON `local_warehouse_locations` (`userId`, `namespaceId`, `warehouseCode`, `code`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_warehouse_locations_userId_namespaceId_code` ON `local_warehouse_locations` (`userId`, `namespaceId`, `code`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `warehouse_location_cache_meta` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `refreshedAt` INTEGER NOT NULL, `complete` INTEGER NOT NULL, `totalCount` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`))")
            db.execSQL("ALTER TABLE `local_inbound_sessions` ADD COLUMN `warehouseCode` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `local_inbound_sessions` ADD COLUMN `warehouseName` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `local_inbound_snapshots` ADD COLUMN `warehouseLocationId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `local_inbound_batches` ADD COLUMN `warehouseLocationId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `local_inbound_batches` ADD COLUMN `locationName` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `local_inbound_batches` ADD COLUMN `zoneName` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `local_inbound_items` ADD COLUMN `localWarehouseLocationId` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `local_inbound_items` ADD COLUMN `remoteWarehouseLocationId` INTEGER NOT NULL DEFAULT 0")
        }
    }

    internal val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `local_inbound_sessions` ADD COLUMN `exceptionCount` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `local_inbound_snapshots` ADD COLUMN `receiptScanStatus` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `local_inbound_snapshots` ADD COLUMN `exceptionReason` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `local_inbound_snapshots` ADD COLUMN `remoteScannedBy` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `local_inbound_snapshots` ADD COLUMN `remoteScannedAt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    internal val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `local_inbound_sessions` ADD COLUMN `productCacheComplete` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `local_inbound_sessions` ADD COLUMN `productCacheAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `local_inbound_sessions` ADD COLUMN `lastOpenedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS `local_inbound_product_summaries` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `productId` INTEGER NOT NULL, `productCode` TEXT NOT NULL, `productName` TEXT NOT NULL, `productImage` TEXT NOT NULL, `skuCount` INTEGER NOT NULL, `totalBoxCount` INTEGER NOT NULL, `inboundedBoxCount` INTEGER NOT NULL, `pendingBoxCount` INTEGER NOT NULL, `exceptionBoxCount` INTEGER NOT NULL, `snapshotAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `sessionId`, `productId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_inbound_product_summaries_userId_namespaceId_sessionId_pendingBoxCount` ON `local_inbound_product_summaries` (`userId`, `namespaceId`, `sessionId`, `pendingBoxCount`)")
        }
    }

    internal val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `local_inbound_product_summaries` ADD COLUMN `skuCodesJson` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE `local_inbound_product_summaries` ADD COLUMN `boxPlanIdsJson` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("CREATE TABLE IF NOT EXISTS `receiving_product_progress_cache` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `businessType` TEXT NOT NULL, `documentId` INTEGER NOT NULL, `productKey` TEXT NOT NULL, `productId` INTEGER NOT NULL, `productCode` TEXT NOT NULL, `productName` TEXT NOT NULL, `productImage` TEXT NOT NULL, `skuCount` INTEGER NOT NULL, `skuCodesJson` TEXT NOT NULL, `sourceItemIdsJson` TEXT NOT NULL, `boxPlanIdsJson` TEXT NOT NULL, `totalBoxCount` INTEGER NOT NULL, `completedBoxCount` INTEGER NOT NULL, `pendingBoxCount` INTEGER NOT NULL, `exceptionBoxCount` INTEGER NOT NULL, `workStatus` TEXT NOT NULL, `lastOperatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `businessType`, `documentId`, `productKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receiving_product_progress_cache_userId_namespaceId_businessType_documentId_pendingBoxCount` ON `receiving_product_progress_cache` (`userId`, `namespaceId`, `businessType`, `documentId`, `pendingBoxCount`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `receiving_product_progress_meta` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `businessType` TEXT NOT NULL, `documentId` INTEGER NOT NULL, `complete` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `businessType`, `documentId`))")
        }
    }

    internal val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `product_count_tasks` (`clientTaskId` TEXT NOT NULL, `userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `remoteId` INTEGER NOT NULL, `status` TEXT NOT NULL, `productCode` TEXT NOT NULL, `productName` TEXT NOT NULL, `resetGeneration` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `editorDeviceId` TEXT NOT NULL, `serverCount` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `lastSyncError` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`clientTaskId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_count_tasks_userId_namespaceId_status_updatedAt` ON `product_count_tasks` (`userId`, `namespaceId`, `status`, `updatedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_count_tasks_remoteId` ON `product_count_tasks` (`remoteId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `product_count_entries` (`clientScanId` TEXT NOT NULL, `userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `taskClientId` TEXT NOT NULL, `resetGeneration` INTEGER NOT NULL, `labelCode` TEXT NOT NULL, `originalRawCode` TEXT NOT NULL, `skuCodesJson` TEXT NOT NULL, `productCode` TEXT NOT NULL, `productName` TEXT NOT NULL, `resolutionSource` TEXT NOT NULL, `duplicateConfirmed` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `syncError` TEXT NOT NULL, `scannedAt` INTEGER NOT NULL, PRIMARY KEY(`clientScanId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_count_entries_userId_namespaceId_taskClientId_resetGeneration_scannedAt` ON `product_count_entries` (`userId`, `namespaceId`, `taskClientId`, `resetGeneration`, `scannedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_count_entries_taskClientId_resetGeneration_labelCode` ON `product_count_entries` (`taskClientId`, `resetGeneration`, `labelCode`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sku_product_index` (`userId` INTEGER NOT NULL, `namespaceId` INTEGER NOT NULL, `skuCode` TEXT NOT NULL, `productId` INTEGER NOT NULL, `productCode` TEXT NOT NULL, `productName` TEXT NOT NULL, `remoteUpdatedAt` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `namespaceId`, `skuCode`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sku_product_index_userId_namespaceId_productCode` ON `sku_product_index` (`userId`, `namespaceId`, `productCode`)")
        }
    }

    internal val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `product_count_tasks` ADD COLUMN `uploadStatus` TEXT NOT NULL DEFAULT 'local'")
            db.execSQL("ALTER TABLE `product_count_tasks` ADD COLUMN `serverRevision` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `product_count_tasks` ADD COLUMN `lastUploadId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `product_count_tasks` ADD COLUMN `uploadedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `product_count_entries` ADD COLUMN `skuItemsJson` TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE `product_count_entries` ADD COLUMN `sequenceNo` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `product_count_entries` ADD COLUMN `validationStatus` TEXT NOT NULL DEFAULT 'pending'")
            db.execSQL("ALTER TABLE `product_count_entries` ADD COLUMN `validationMessage` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `product_count_entries` SET `skuItemsJson` = COALESCE((SELECT json_group_array(json_object('sku_code', value, 'quantity', NULL, 'quantity_source', 'unknown')) FROM json_each(`skuCodesJson`)), '[]')")
            db.execSQL("UPDATE `product_count_entries` SET `sequenceNo` = rowid WHERE `sequenceNo` = 0")
            db.execSQL("DELETE FROM `pending_sync_operations` WHERE `module` = 'product_counting'")
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
