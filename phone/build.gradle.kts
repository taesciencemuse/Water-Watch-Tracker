plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    // Persistent debug signing (v12 lesson): AGP's default debug
    // keystore lives in ~/.android, which does not survive VM
    // replacements here, so each fresh keystore broke updates with a
    // signature mismatch (v11 cert 9d01… vs v12 cert dc9d…). If a
    // debug.keystore exists at the project root, pin to it; otherwise
    // fall back to the default, so a fresh clone still builds.
    if (file("../debug.keystore").exists()) {
        signingConfigs {
            create("persistentDebug") {
                storeFile = file("../debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    namespace = "com.colehydro.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taescience.waterlogged"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.7.8"
    }
    buildTypes {
        getByName("debug") {
            if (file("../debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("persistentDebug")
            }
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")

    // Health Connect (phone only — this is the writer)
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Periodic reminders (WorkManager, no exact-alarm permission needed)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Home screen widget (Glance, v12 item 8)
    implementation("androidx.glance:glance-appwidget:1.1.0")

    // Watch -> phone transport
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
