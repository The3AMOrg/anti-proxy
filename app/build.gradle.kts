apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.plugin.compose")
apply(plugin = "com.google.gms.google-services")

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "org.ap.protocol"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ap.protocol"
        minSdk = 26
        targetSdk = 35
        // Release tags are v<versionName> (e.g. v1.0.0); the in-app update check compares against it
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release builds: one APK per CPU type plus a universal one, like AttendX. The only native code
    // is Compose's shape library (libandroidx.graphics.path.so), so per-CPU APKs are smaller.
    // The in-app updater picks the APK matching the phone. Debug builds stay a single APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // CI signs releases from secrets (see .github/workflows/release.yml); unset locally, so
    // local release builds stay unsigned and debug builds use the debug key as before.
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    "implementation"(project(":core"))
    "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    "implementation"("androidx.core:core-ktx:1.15.0")

    // Compose + Material 3 Expressive (same versions as AttendX)
    "implementation"(platform("androidx.compose:compose-bom:2025.11.00"))
    "implementation"("androidx.compose.ui:ui")
    "implementation"("androidx.compose.material3:material3:1.5.0-alpha08")
    "implementation"("androidx.compose.material:material-icons-extended:1.7.8")
    "implementation"("androidx.activity:activity-compose:1.11.0")
    "implementation"("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    "implementation"("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    // Profile photos (Google account avatars) with memory and disk caching
    "implementation"("io.coil-kt.coil3:coil-compose:3.3.0")
    "implementation"("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    // Daily background update check
    "implementation"("androidx.work:work-runtime-ktx:2.10.1")

    // Firebase + Google sign-in (same versions as AttendX)
    "implementation"(platform("com.google.firebase:firebase-bom:33.9.0"))
    "implementation"("com.google.firebase:firebase-auth")
    "implementation"("com.google.firebase:firebase-firestore")
    "implementation"("androidx.credentials:credentials:1.6.0")
    "implementation"("androidx.credentials:credentials-play-services-auth:1.6.0")
    "implementation"("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    "testImplementation"("junit:junit:4.13.2")
    // Real org.json for JVM tests (Android's is a stub there)
    "testImplementation"("org.json:json:20240303")
    "androidTestImplementation"("androidx.test:runner:1.6.2")
    "androidTestImplementation"("androidx.test.ext:junit:1.2.1")
}
