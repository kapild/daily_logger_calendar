plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kapil.healthcal"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kapil.healthcal"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Health Connect (local, on-device). If Gradle can't resolve this,
    // use the newest 1.1.x listed at developer.android.com/jetpack/androidx/releases/health-connect
    implementation("androidx.health.connect:connect-client:1.1.0")

    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Geofences + current location for places (runs inside Play Services, low power)
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
