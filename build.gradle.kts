// Top-level build file. Open this folder in Android Studio and let it
// sync Gradle. If a library version is flagged, let Android Studio's
// version catalog / upgrade assistant pick the matching Wear OS 7 set.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0+: Compose compiler is a separate plugin, required when
    // buildFeatures.compose is enabled (was previously bundled).
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
