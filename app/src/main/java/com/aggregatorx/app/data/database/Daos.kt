package com.aggregatorx.app.data.database

import androidx.room.*
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import com.aggregatorx.app.data.model.AuditLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AggregatorDao {
    // Provider Operations
    @Query("SELECT * FROM providers")
    fun getAllProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE isEnabled = 1")
    fun getEnabledProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE name = :name LIMIT 1")
    suspend fun getProviderByName(name: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ProviderEntity)

    @Update
    suspend fun updateProvider(provider: ProviderEntity)

    // Result Operations
    @Query("SELECT * FROM results WHERE providerName = :providerName")
    fun getResultsByProvider(providerName: String): Flow<List<ResultItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResults(results: List<ResultItem>)

    @Query("UPDATE results SET isLiked = :isLiked WHERE id = :id")
    suspend fun updateItemLikeStatus(id: String, isLiked: Boolean)
}

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insertLog(log: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLogEntity>>

    @Query("DELETE FROM audit_logs WHERE timestamp < :expiry")
    suspend fun clearOldLogs(expiry: Long)
}
