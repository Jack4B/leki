plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

val appVersionName: String = providers.gradleProperty("appVersionName").get()
// Numer kompilacji = numer wersji * 1000 + numer przebiegu CI (mod 1000), np. 0.14.5-preview i przebieg 101 -> 1405101 (numer wersji = major*10000 + minor*100 + patch).
// Lokalnie (bez -PbuildNumber) przebieg to 0, więc numer jest zawsze niższy niż w CI dla tej samej wersji: APK z Releases instaluje się na wierzch lokalnej kompilacji.
// Numery rosną z wersją i przebiegiem; -PversionCode=N nadpisuje całość.
val versionNumber: Int = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersionName)?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt().coerceAtMost(99) * 100 + patch.toInt().coerceAtMost(99) } ?: 1
val buildNumber: Int = providers.gradleProperty("buildNumber").map { it.toInt() }.orElse(0).get()
val appVersionCode: Int = providers.gradleProperty("versionCode").map { it.toInt() }.orElse(versionNumber * 1000 + buildNumber % 1000).get()

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

    // Podpis release ze zmiennych środowiskowych albo właściwości Gradle (-P), nigdy z hasła zapisanego w repozytorium:
    // LEKI_KEYSTORE_PATH, LEKI_KEYSTORE_PASSWORD, LEKI_KEY_ALIAS, LEKI_KEY_PASSWORD (w CI z sekretów GitHub).
    // Gdy któregoś brakuje, release jest podpisywany zwykłym kluczem debug (~/.android/debug.keystore) — wersja preview/lokalna.
    fun signingValue(name: String): String? =
        (providers.environmentVariable(name).orNull ?: providers.gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }
    val ksPath = signingValue("LEKI_KEYSTORE_PATH")
    val ksPassword = signingValue("LEKI_KEYSTORE_PASSWORD")
    val ksAlias = signingValue("LEKI_KEY_ALIAS")
    val ksKeyPassword = signingValue("LEKI_KEY_PASSWORD")
    val hasReleaseKey = ksPath != null && ksPassword != null && ksAlias != null && ksKeyPassword != null
    signingConfigs {
        if (hasReleaseKey) {
            create("leki") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }
    if (!hasReleaseKey) logger.warn("Brak LEKI_KEYSTORE_PATH/LEKI_KEYSTORE_PASSWORD/LEKI_KEY_ALIAS/LEKI_KEY_PASSWORD: release podpisany kluczem debug.")

    buildTypes {
        release {
            // Wersja preview: bez minifikacji (mniejsze ryzyko błędów w czasie działania).
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("leki") else signingConfigs.getByName("debug")
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
