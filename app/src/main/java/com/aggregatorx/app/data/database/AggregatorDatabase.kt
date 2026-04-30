package com.aggregatorx.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.data.model.AuditLogEntity

@Database(
    entities = [
        ProviderEntity::class, 
        ResultItem::class, 
        AuditLogEntity::class
    ], 
    version = 2, 
    exportSchema = false
)
abstract class AggregatorDatabase : RoomDatabase() {
    abstract fun aggregatorDao(): AggregatorDao
    abstract fun auditLogDao(): AuditLogDao
}
