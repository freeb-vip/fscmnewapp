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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(
    tableName = "offline_cache",
    indices = [Index(value = ["namespaceId", "cacheKey"], unique = true)],
)
data class OfflineCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val namespaceId: Long,
    val cacheKey: String,
    val payload: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "receiving_sessions",
    indices = [Index(value = ["namespaceId", "remoteId"], unique = true)],
)
data class ReceivingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val namespaceId: Long,
    val remoteId: Long,
    val code: String,
    val payload: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "pending_sync_operations",
    indices = [Index(value = ["namespaceId", "module", "status", "createdAt"])],
)
data class PendingSyncOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val namespaceId: Long,
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

enum class PendingSyncStatus(val value: String) {
    Pending("pending"),
    Syncing("syncing"),
    Failed("failed"),
}

@Dao
interface OfflineCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfflineCacheEntity)

    @Query("SELECT * FROM offline_cache WHERE namespaceId = :namespaceId AND cacheKey = :cacheKey LIMIT 1")
    suspend fun find(namespaceId: Long, cacheKey: String): OfflineCacheEntity?

    @Query("DELETE FROM offline_cache WHERE namespaceId = :namespaceId AND cacheKey LIKE :cacheKeyPrefix || '%' AND updatedAt < :cutoff")
    suspend fun deleteOlderThan(namespaceId: Long, cacheKeyPrefix: String, cutoff: Long)

    @Query(
        "DELETE FROM offline_cache WHERE id IN (" +
            "SELECT id FROM offline_cache WHERE namespaceId = :namespaceId AND cacheKey LIKE :cacheKeyPrefix || '%' " +
            "ORDER BY updatedAt DESC LIMIT -1 OFFSET :maxEntries)",
    )
    suspend fun trimToNewest(namespaceId: Long, cacheKeyPrefix: String, maxEntries: Int)
}

@Dao
interface ReceivingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<ReceivingSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ReceivingSessionEntity)

    @Query("SELECT * FROM receiving_sessions WHERE namespaceId = :namespaceId ORDER BY updatedAt DESC")
    suspend fun sessions(namespaceId: Long): List<ReceivingSessionEntity>

    @Query("SELECT * FROM receiving_sessions WHERE namespaceId = :namespaceId AND remoteId = :id LIMIT 1")
    suspend fun session(namespaceId: Long, id: Long): ReceivingSessionEntity?
}

@Dao
interface PendingSyncDao {
    @Insert
    suspend fun insert(entity: PendingSyncOperationEntity): Long

    @Query("SELECT * FROM pending_sync_operations WHERE status IN ('pending', 'failed') ORDER BY namespaceId ASC, createdAt ASC")
    suspend fun pending(): List<PendingSyncOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_sync_operations WHERE status IN ('pending', 'failed')")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM pending_sync_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_sync_operations SET status = :status, retryCount = :retryCount, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: Long,
        status: String,
        retryCount: Int,
        lastError: String,
        updatedAt: Long = System.currentTimeMillis(),
    )
}

@Database(
    entities = [
        OfflineCacheEntity::class,
        ReceivingSessionEntity::class,
        PendingSyncOperationEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class FscmDatabase : RoomDatabase() {
    abstract fun offlineCacheDao(): OfflineCacheDao
    abstract fun receivingDao(): ReceivingDao
    abstract fun pendingSyncDao(): PendingSyncDao
}

@Module
@InstallIn(SingletonComponent::class)
object FscmDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FscmDatabase =
        Room.databaseBuilder(context, FscmDatabase::class.java, "fscm.db")
            .build()

    @Provides
    fun offlineCacheDao(database: FscmDatabase): OfflineCacheDao = database.offlineCacheDao()

    @Provides
    fun receivingDao(database: FscmDatabase): ReceivingDao = database.receivingDao()

    @Provides
    fun pendingSyncDao(database: FscmDatabase): PendingSyncDao = database.pendingSyncDao()
}
