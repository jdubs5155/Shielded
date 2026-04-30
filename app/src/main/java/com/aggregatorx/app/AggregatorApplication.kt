package com.aggregatorx.app

import android.app.Application
import com.aggregatorx.app.data.database.DatabaseInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AggregatorApplication : Application() {

    @Inject
    lateinit var dbInitializer: DatabaseInitializer

    override fun onCreate() {
        super.onCreate()
        // Seeds the database with initial search providers
        dbInitializer.initialize()
    }
}
