package com.aggregatorx.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem

/**
 * Main database for AggregatorX.
 * Stores search providers, scraped results, and security audit logs.
 */
@Database(
    entities = [ProviderEntity::class, ResultItem::class, AuditLogEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class) // You'll need this for the PaginationType enum
abstract class AggregatorDatabase : RoomDatabase() {
    abstract fun aggregatorDao(): AggregatorDao
    abstract fun auditLogDao(): AuditLogDao
}
