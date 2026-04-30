plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.shielded.aggregatorx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shielded.aggregatorx"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android & Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51")
    kapt("com.google.dagger:hilt-compiler:2.51")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Media3 - Video Playback & Downloads
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    // Required by MediaDownloadManager (SimpleCache + StandaloneDatabaseProvider).
    implementation("androidx.media3:media3-database:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")

    // Lifecycle: ViewModel + SavedStateHandle (used by SearchViewModel for
    // state preservation across configuration changes / process death).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Compose foundation (Box, BackgroundModifier) used by VideoPlayerActivity.
    implementation("androidx.compose.foundation:foundation")

    // Jsoup is used to parse already-rendered HTML returned by the WebView
    // (we don't fetch with it — fetch goes through the HeadlessBrowserHelper).
    implementation("org.jsoup:jsoup:1.17.2")

    // JSON helper used by the JS bridge in HeadlessBrowserHelper (org.json
    // is part of the Android platform; no extra dep required).

    // Headless Scraping Upgrade
    implementation("androidx.webkit:webkit:1.12.0")

    // Networking, Anti-Detection & Proxy Support
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor")

    // Coil for UI Image Loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Local LLM integration (llama.cpp / kotlinllamacpp bindings)
    // Note: The quantized model will be placed in src/main/assets/
    // Native bindings handled via standard JNI/AAR included in project
}
