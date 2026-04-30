package com.aggregatorx.app.data.database

import androidx.room.*
import com.aggregatorx.app.data.model.ProviderEntity
import com.aggregatorx.app.data.model.ResultItem
import kotlinx.coroutines.flow.Flow

@Dao
interface AggregatorDao {

    // Provider Management
    @Query("SELECT * FROM providers WHERE isEnabled = 1")
    fun getEnabledProviders(): Flow<List<ProviderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ProviderEntity)

    // Result Persistence
    @Query("SELECT * FROM results WHERE providerName = :providerName")
    fun getResultsByProvider(providerName: String): Flow<List<ResultItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResults(results: List<ResultItem>)

    @Update
    suspend fun updateResult(item: ResultItem)

    @Query("DELETE FROM results WHERE providerName = :providerName")
    suspend fun clearResultsByProvider(providerName: String)
}
