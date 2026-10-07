apply(plugin = "com.android.application")

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "org.ap.protocol"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.ap.protocol"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    "implementation"("androidx.appcompat:appcompat:1.6.1")
    "implementation"("com.google.android.material:material:1.10.0")
}