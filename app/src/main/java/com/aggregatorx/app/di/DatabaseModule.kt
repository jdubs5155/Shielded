package com.aggregatorx.app.di

import android.content.Context
import androidx.room.Room
import com.aggregatorx.app.data.database.AggregatorDao
import com.aggregatorx.app.data.database.AggregatorDatabase
import com.aggregatorx.app.data.database.AuditLogDao
import com.aggregatorx.app.data.database.AuthTokenDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AggregatorDatabase {
        return Room.databaseBuilder(
            context,
            AggregatorDatabase::class.java,
            "aggregator_db"
        )
        .addMigrations(
            AggregatorDatabase.MIGRATION_1_2,
            AggregatorDatabase.MIGRATION_2_3
        )
        // Safety net: if a future schema bump lacks an explicit migration we
        // prefer dropping local data over crashing the app.
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideAggregatorDao(db: AggregatorDatabase): AggregatorDao = db.aggregatorDao()

    @Provides
    fun provideAuditLogDao(db: AggregatorDatabase): AuditLogDao = db.auditLogDao()

    @Provides
    fun provideAuthTokenDao(db: AggregatorDatabase): AuthTokenDao = db.authTokenDao()
}
