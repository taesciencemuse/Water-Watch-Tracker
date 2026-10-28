plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.colehydro.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.colehydro.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.7.8"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Wear UI
    implementation("androidx.core:core-ktx:1.13.1")
    // NOTE: compose-material3 never had a 1.3.0 (its versions jump from
    // 1.0.0-alpha37 to 1.5.0); 1.3.0 was the foundation/material version.
    implementation("androidx.wear.compose:compose-material3:1.5.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.compose.material3:material3:1.3.0")

    // Tile (ProtoLayout / Tiles)
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.3.0")

    // Complication
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.0")
    implementation("androidx.wear.watchface:watchface-complications-data:1.2.0")

    // Local store + watch -> phone transport
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Task.await() for Play Services Tasks (SyncSender) and
    // CoroutineScope.future() for ListenableFuture (TileService).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // coroutines-guava pulls full guava:31.0.1-jre, whose pom repeatedly
    // fails through this VM's egress proxy; listenablefuture alone
    // provides the ListenableFuture/Futures classes it actually uses.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1") {
        exclude(group = "com.google.guava", module = "guava")
    }
    // Futures.immediateFuture() for TileService needs full Guava:
    // listenablefuture alone only contains the ListenableFuture interface.
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
