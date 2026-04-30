package com.aggregatorx.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.data.model.AuthTokenEntity
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem

/**
 * Main database for AggregatorX.
 * Stores search providers, scraped results, security audit logs and
 * captured authentication tokens.
 *
 * Version history:
 *  - 1: initial schema
 *  - 2: ProviderEntity gained `currentPage` (Int, default 1) and `pageSize`
 *       (Int, default 20) for per-provider pagination wiring; also adds
 *       `nextPageUrl` (TEXT NULL) for URL_TOKEN-style providers.
 *  - 3: new `auth_tokens` table for the auto-injection token lifecycle.
 */
@Database(
    entities = [
        ProviderEntity::class,
        ResultItem::class,
        AuditLogEntity::class,
        AuthTokenEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AggregatorDatabase : RoomDatabase() {
    abstract fun aggregatorDao(): AggregatorDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun authTokenDao(): AuthTokenDao

    companion object {
        /**
         * 1 -> 2: add `currentPage`, `pageSize` and `nextPageUrl` columns
         * to the `providers` table. Existing rows default to page 1 / size 20
         * with no next-page URL.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE providers ADD COLUMN currentPage INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE providers ADD COLUMN pageSize INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE providers ADD COLUMN nextPageUrl TEXT")
            }
        }

        /**
         * 2 -> 3: create the `auth_tokens` table backing the token
         * auto-injection / lifecycle system.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS auth_tokens (
                        id           TEXT NOT NULL PRIMARY KEY,
                        host         TEXT NOT NULL,
                        value        TEXT NOT NULL,
                        headerName   TEXT NOT NULL DEFAULT 'Authorization',
                        isBearer     INTEGER NOT NULL DEFAULT 1,
                        status       TEXT NOT NULL DEFAULT 'UNTESTED',
                        expiresAtSec INTEGER,
                        firstSeenAt  INTEGER NOT NULL,
                        lastUsedAt   INTEGER,
                        successCount INTEGER NOT NULL DEFAULT 0,
                        failureCount INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
