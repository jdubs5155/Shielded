// Top-level build file for AggregatorX - Advanced Web Scraping Aggregator
plugins {
    // Android Gradle Plugin for the application
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    
    // Kotlin Android support (Must match the version used in the app-level file)
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    
    // Jetpack Compose Compiler Plugin (Required for Kotlin 2.0+)
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    
    // Dependency Injection (Hilt)
    id("com.google.dagger.hilt.android") version "2.55" apply false
    
    // Kotlin Serialization (Used for deep state persistence and JSON handling)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
