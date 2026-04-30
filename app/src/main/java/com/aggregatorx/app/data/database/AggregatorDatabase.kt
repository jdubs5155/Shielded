package com.aggregatorx.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem

/**
 * Main database for AggregatorX.
 * Stores search providers, scraped results, and security audit logs.
 *
 * Version history:
 *  - 1: initial schema
 *  - 2: ProviderEntity gained `currentPage` (Int, default 1) and `pageSize`
 *       (Int, default 20) for per-provider pagination wiring.
 */
@Database(
    entities = [ProviderEntity::class, ResultItem::class, AuditLogEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AggregatorDatabase : RoomDatabase() {
    abstract fun aggregatorDao(): AggregatorDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        /**
         * 1 -> 2: add `currentPage` and `pageSize` columns to the `providers`
         * table. Existing rows default to (page 1, size 20).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE providers ADD COLUMN currentPage INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE providers ADD COLUMN pageSize INTEGER NOT NULL DEFAULT 20")
            }
        }
    }
}
