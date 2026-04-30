package com.aggregatorx.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * AggregatorX - Advanced Multi-Provider Web Search & Scraping
 * 
 * Features:
 * - Multi-provider search with intelligent NLP query rewriting
 * - Resilient scraping with Cloudflare bypass and Headless WebView
 * - Media3-powered video playback and background downloads
 * - Local AI refinement loop using Dolphin-3.0-Llama3.1-8B-GGUF
 */
@HiltAndroidApp
class AggregatorApplication : Application()
