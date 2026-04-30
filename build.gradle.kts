// Top-level build file for AggregatorX
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    
    // Kotlin Version 2.1.10
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
    
    // KSP - Required to replace Kapt and fix the NonExistentClass error
    id("com.google.devtools.ksp") version "2.1.10-1.0.29" apply false
    
    // Dependency Injection
    id("com.google.dagger.hilt.android") version "2.55" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
