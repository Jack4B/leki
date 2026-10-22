plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

val appVersionName: String = providers.gradleProperty("appVersionName").get()
val appVersionCode: Int = providers.gradleProperty("versionCode").map { it.toInt() }.orElse(1).get()

android {
    namespace = "pl.asystent.leki"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.asystent.leki"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // Biblioteka OCR zawiera natywny kod dla każdej architektury; telefony to arm64 lub (starsze) armeabi-v7a, x86 pomijamy.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // CI podaje jawnie plik klucza (-PlekiKeystore=...), żeby podpis był stały i nie zależał od domyślnego miejsca klucza debug.
    // Bez tego parametru (build lokalny) używany jest zwykły klucz debug.
    val lekiKeystore: String? = providers.gradleProperty("lekiKeystore").orNull
    signingConfigs {
        if (lekiKeystore != null) {
            create("leki") {
                storeFile = file(lekiKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // Wersja preview: bez minifikacji (mniejsze ryzyko błędów w czasie działania).
            isMinifyEnabled = false
            signingConfig = if (lekiKeystore != null) signingConfigs.getByName("leki") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
