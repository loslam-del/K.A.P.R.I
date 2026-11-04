plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("KAPRI_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("KAPRI_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("KAPRI_KEY_ALIAS")
val releaseKeyPassword = System.getenv("KAPRI_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.screenai"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.screenai"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("KAPRI_VERSION_CODE")?.toIntOrNull() ?: 2)
        versionName = System.getenv("KAPRI_VERSION_NAME") ?: "1.1"
    }
    if (hasReleaseSigning) {
        signingConfigs {
            create("stableRelease") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
