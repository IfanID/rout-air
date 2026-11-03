import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Skema redirect AppAuth: client ID dibalik tanpa akhiran .apps.googleusercontent.com. */
fun redirectScheme(clientId: String): String {
    if (clientId.isBlank()) return "com.rout.air"
    return "com.googleusercontent.apps." +
        clientId.removeSuffix(".apps.googleusercontent.com")
}

android {
    namespace = "com.rout.air"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rout.air"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // Client ID OAuth Android berbeda untuk tiap sertifikat (debug/release).
    // Nilainya diisi lewat local.properties agar tidak masuk repo.
    val local = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val androidClientIdDebug = local.getProperty("routair.googleAndroidClientIdDebug", "")
    val androidClientIdRelease = local.getProperty("routair.googleAndroidClientIdRelease", "")
    val fallbackClientId = local.getProperty("routair.googleAndroidClientId", "")

    buildTypes {
        debug {
            val id = androidClientIdDebug.ifBlank { fallbackClientId }
            buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"$id\"")
            manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme(id)
        }
        release {
            val id = androidClientIdRelease.ifBlank { fallbackClientId }
            buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"$id\"")
            manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme(id)
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appauth)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // org.json versi JVM: stub android.jar di unit test tidak bisa dipakai,
    // jadi format JSON diuji dengan implementasi asli.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}