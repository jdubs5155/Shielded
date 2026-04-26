package com.aggregatorx.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * AggregatorX - Advanced Multi-Provider Web Scraping Aggregator
 * 
 * Features:
 * - Multi-provider search with intelligent result aggregation
 * - Advanced site analyzer with DOM parsing and pattern recognition
 * - Resilient scraping with fallback mechanisms
 * - Beautiful modern UI with smooth scrolling
 * - Provider management with enable/disable toggles
 * - Smart ranking system for search results
 */
@HiltAndroidApp
class AggregatorXApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    
    companion object {
        lateinit var instance: AggregatorXApp
            private set
    }
}
