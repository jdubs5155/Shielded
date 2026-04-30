package com.aggregatorx.app.data.database

import androidx.room.*
import com.aggregatorx.app.data.model.AuditLogEntity
import com.aggregatorx.app.data.model.AuthTokenEntity
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import kotlinx.coroutines.flow.Flow

@Dao
interface AggregatorDao {

    // ── Provider Operations ──────────────────────────────────────────────

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

    /**
     * Update only the pagination state of a provider. Avoids the read-modify-write
     * round-trip done by `updateProvider` when only paging fields change.
     */
    @Query(
        """
        UPDATE providers
        SET currentPage = :currentPage,
            nextPageUrl = :nextPageUrl
        WHERE name = :name
        """
    )
    suspend fun updateProviderPagination(
        name: String,
        currentPage: Int,
        nextPageUrl: String?
    )

    // ── Result Operations ────────────────────────────────────────────────

    @Query("SELECT * FROM results WHERE providerName = :providerName ORDER BY timestamp DESC")
    fun getResultsByProvider(providerName: String): Flow<List<ResultItem>>

    @Query("SELECT * FROM results WHERE providerName = :providerName ORDER BY timestamp DESC")
    suspend fun getResultsByProviderOnce(providerName: String): List<ResultItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResults(results: List<ResultItem>)

    @Update
    suspend fun updateResult(item: ResultItem)

    @Query("UPDATE results SET isLiked = :isLiked WHERE id = :id")
    suspend fun updateItemLikeStatus(id: String, isLiked: Boolean)

    /**
     * Replaces only the slice of results owned by a specific provider — used
     * by per-provider pagination so other providers' results are untouched.
     */
    @Query("DELETE FROM results WHERE providerName = :providerName")
    suspend fun clearResultsByProvider(providerName: String)
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

@Dao
interface AuthTokenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(token: AuthTokenEntity)

    @Query("SELECT * FROM auth_tokens WHERE host = :host AND status IN ('UNTESTED','ACTIVE') ORDER BY successCount DESC, firstSeenAt DESC")
    suspend fun getUsableForHost(host: String): List<AuthTokenEntity>

    @Query("SELECT * FROM auth_tokens ORDER BY firstSeenAt DESC")
    fun observeAll(): Flow<List<AuthTokenEntity>>

    @Query("UPDATE auth_tokens SET status = :status, successCount = successCount + :successDelta, failureCount = failureCount + :failureDelta, lastUsedAt = :lastUsedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, successDelta: Int, failureDelta: Int, lastUsedAt: Long)

    @Query("DELETE FROM auth_tokens WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM auth_tokens WHERE status IN ('FAILED','EXPIRED')")
    suspend fun purgeUnusable()
}
