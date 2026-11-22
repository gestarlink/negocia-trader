plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "br.com.negocia.trader"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.negocia.trader"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.0"
    }
    signingConfigs {
        create("niaRelease") {
            val path = System.getenv("NIA_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("NIA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NIA_KEY_ALIAS")
                keyPassword = System.getenv("NIA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("niaRelease")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
