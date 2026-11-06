plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningReady = listOf(
    "ANDROID_KEYSTORE_FILE", "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"
).all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.videokeeper.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.videokeeper.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(System.getenv("ANDROID_KEYSTORE_FILE")!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")!!
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")!!
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")!!
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
}
