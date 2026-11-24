plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shivansh.raasta"
    compileSdk = 37
    // Pinned to the installed build-tools so AGP never tries to download its own.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.shivansh.vaysmaps"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.6.0"
        // For testing tiles before they're published: -PtileBase=file:/sdcard/Android/data/com.shivansh.vaysmaps/files/data
        buildConfigField("String", "TILE_BASE", "\"${providers.gradleProperty("tileBase").getOrElse("")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded for testing, so the debug key is fine for now.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    // Lucide: consistent 2 px outline icons (the closest open match to SF Symbols' look).
    implementation("com.composables:icons-lucide-android:1.1.0")
}
