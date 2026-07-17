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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlCipherDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val passphrase = "migration-test-password".encodeToByteArray()
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        databaseName = "fscm-migration-${System.nanoTime()}.db"
        deleteDatabaseFamily(databaseName)
    }

    @After
    fun tearDown() {
        deleteDatabaseFamily(databaseName)
        passphrase.fill(0)
    }

    @Test
    fun plaintextDatabaseIsExportedWithDataAndVersion() {
        createPlaintextDatabase(databaseName, userVersion = 3)

        val result = SqlCipherDatabaseMigration.migrateIfNeeded(context, databaseName, passphrase)

        assertEquals(SqlCipherMigrationResult.Migrated, result)
        assertFalse(context.getDatabasePath(databaseName).hasPlaintextHeader())
        openEncryptedDatabase(databaseName, passphrase).use { database ->
            val value = database.rawQuery("SELECT value FROM migration_sentinel", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            val version = database.rawQuery("PRAGMA user_version", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals("preserved", value)
            assertEquals(3, version)
        }
        assertNoMigrationFiles(databaseName)
    }

    @Test
    fun migrationFailureResetsPlaintextAndAllowsFreshEncryptedRoomDatabase() {
        createPlaintextDatabase(databaseName, userVersion = 2)
        createSidecars(context.getDatabasePath(databaseName))

        val result = SqlCipherDatabaseMigration.migrateIfNeeded(
            context = context,
            databaseName = databaseName,
            passphrase = passphrase,
            beforeExport = { error("forced migration failure") },
        )

        assertEquals(SqlCipherMigrationResult.ResetAfterFailure, result)
        assertFalse(context.getDatabasePath(databaseName).exists())
        assertNoMigrationFiles(databaseName)

        val roomPassphrase = passphrase.copyOf()
        val database = Room.databaseBuilder(context, FscmDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(roomPassphrase))
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
        assertFalse(context.getDatabasePath(databaseName).hasPlaintextHeader())
    }

    @Test
    fun encryptedDatabaseIsNotMigratedAgain() {
        SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(databaseName),
            passphrase,
            null,
            null,
            null,
        ).use { database ->
            database.execSQL("CREATE TABLE encrypted_sentinel (value TEXT NOT NULL)")
            database.execSQL("INSERT INTO encrypted_sentinel VALUES ('preserved')")
        }

        val result = SqlCipherDatabaseMigration.migrateIfNeeded(context, databaseName, passphrase)

        assertEquals(SqlCipherMigrationResult.NotNeeded, result)
        openEncryptedDatabase(databaseName, passphrase).use { database ->
            val value = database.rawQuery("SELECT value FROM encrypted_sentinel", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("preserved", value)
        }
    }

    @Test
    fun encryptedDatabaseWithWrongKeyIsNotDeleted() {
        SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(databaseName),
            passphrase,
            null,
            null,
            null,
        ).close()

        val result = SqlCipherDatabaseMigration.migrateIfNeeded(
            context,
            databaseName,
            "wrong-password".encodeToByteArray(),
        )

        assertEquals(SqlCipherMigrationResult.NotNeeded, result)
        assertTrue(context.getDatabasePath(databaseName).exists())
    }

    @Test
    fun nonPlaintextCorruptDatabaseIsNotDeleted() {
        context.getDatabasePath(databaseName).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64) { 0x5a })
        }

        val result = SqlCipherDatabaseMigration.migrateIfNeeded(context, databaseName, passphrase)

        assertEquals(SqlCipherMigrationResult.NotNeeded, result)
        assertTrue(context.getDatabasePath(databaseName).exists())
    }

    private fun createPlaintextDatabase(name: String, userVersion: Int) {
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { database ->
            database.execSQL("CREATE TABLE offline_cache (id INTEGER PRIMARY KEY)")
            database.execSQL("CREATE TABLE receiving_sessions (id INTEGER PRIMARY KEY)")
            database.execSQL("CREATE TABLE pending_sync_operations (id INTEGER PRIMARY KEY)")
            database.execSQL("CREATE TABLE migration_sentinel (value TEXT NOT NULL)")
            database.execSQL("INSERT INTO migration_sentinel VALUES ('preserved')")
            database.execSQL("PRAGMA user_version = $userVersion")
        }
    }

    private fun openEncryptedDatabase(name: String, password: ByteArray): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).absolutePath,
            password,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        )

    private fun createSidecars(database: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File("${database.absolutePath}$suffix").writeBytes(byteArrayOf(1))
        }
    }

    private fun assertNoMigrationFiles(name: String) {
        val source = context.getDatabasePath(name)
        val files = listOf(
            File(source.parentFile, "$name.encrypted-migration"),
            File(source.parentFile, "$name.plaintext-backup"),
        )
        files.forEach { file ->
            assertFalse(file.exists())
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                assertFalse(File("${file.absolutePath}$suffix").exists())
            }
        }
    }

    private fun deleteDatabaseFamily(name: String) {
        val source = context.getDatabasePath(name)
        listOf(
            source,
            File(source.parentFile, "$name.encrypted-migration"),
            File(source.parentFile, "$name.plaintext-backup"),
        ).forEach { file ->
            file.delete()
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File("${file.absolutePath}$suffix").delete()
            }
        }
    }

    private fun File.hasPlaintextHeader(): Boolean =
        exists() && inputStream().use { input ->
            val expected = "SQLite format 3\u0000".encodeToByteArray()
            val actual = ByteArray(expected.size)
            input.read(actual) == actual.size && actual.contentEquals(expected)
        }
}
