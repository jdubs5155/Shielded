package com.aggregatorx.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aggregatorx.app.data.model.AuditLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditLogDao {

    @Insert
    suspend fun insertLog(log: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE actionType = :type ORDER BY timestamp DESC")
    fun getLogsByType(type: String): Flow<List<AuditLogEntity>>

    @Query("DELETE FROM audit_logs WHERE timestamp < :expiry")
    suspend fun clearOldLogs(expiry: Long)
}
