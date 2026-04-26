package com.aggregatorx.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadDirectoryModule {
    @Provides
    @Singleton
    fun provideDownloadDirectory(): String = "Downloads/AggregatorX"
}
