plugins {
    alias(libs.plugins.android.application)
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val webShellUrl =
    (findProperty("SOLANA_MOBILE_URL") as String?)
        ?.trim()
        ?.ifBlank { null }
        ?: "https://play.plonk.land/?src=app"
val webShellApplicationId =
    (findProperty("SOLANA_MOBILE_APPLICATION_ID") as String?)
        ?.trim()
        ?.ifBlank { null }
        ?: "land.plonk.app"
val webShellVersionCode =
    (findProperty("SOLANA_MOBILE_VERSION_CODE") as String?)
        ?.trim()
        ?.ifBlank { null }
        ?.toIntOrNull()
        ?: 1
val webShellVersionName =
    (findProperty("SOLANA_MOBILE_VERSION_NAME") as String?)
        ?.trim()
        ?.ifBlank { null }
        ?: "1.0"
val webShellSigningStoreFile =
    (findProperty("SOLANA_MOBILE_KEYSTORE_PATH") as String?)
        ?.trim()
        ?.ifBlank { null }
val webShellSigningStorePassword =
    (findProperty("SOLANA_MOBILE_KEYSTORE_PASSWORD") as String?)
        ?.trim()
        ?.ifBlank { null }
        ?: System
            .getenv("SOLANA_MOBILE_KEYSTORE_PASSWORD")
            ?.trim()
            ?.ifBlank { null }
val webShellSigningKeyAlias =
    (findProperty("SOLANA_MOBILE_KEYSTORE_ALIAS") as String?)
        ?.trim()
        ?.ifBlank { null }
val webShellSigningKeyPassword =
    (findProperty("SOLANA_MOBILE_KEY_PASSWORD") as String?)
        ?.trim()
        ?.ifBlank { null }
        ?: System
            .getenv("SOLANA_MOBILE_KEY_PASSWORD")
            ?.trim()
            ?.ifBlank { null }
val hasReleaseSigning =
    webShellSigningStoreFile != null &&
        webShellSigningStorePassword != null &&
        webShellSigningKeyAlias != null

android {
    namespace = "land.plonk.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = webShellApplicationId
        minSdk = 28
        targetSdk = 37
        versionCode = webShellVersionCode
        versionName = webShellVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SOLANA_MOBILE_URL", "\"${webShellUrl.escapeForBuildConfig()}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("webShellRelease") {
                storeFile = file(webShellSigningStoreFile!!)
                storePassword = webShellSigningStorePassword
                keyAlias = webShellSigningKeyAlias
                keyPassword = webShellSigningKeyPassword ?: webShellSigningStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("webShellRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
}
