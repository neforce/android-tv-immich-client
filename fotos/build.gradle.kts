import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.immichclient.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.immichclient.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.3"
        // MapLibre heeft native code: 32-bit (NVIDIA Shield: abilist armeabi-v7a) én 64-bit voor andere boxen; x86 niet.
        // Elke ABI kost ~8 MB in de APK.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // alleen de talen van de app meenemen (anders ~80 vertalingen van de bibliotheken in de APK)
        resourceConfigurations += listOf("en", "nl", "de", "fr", "es")
    }

    // Vaste release-sleutel, zodat een nieuwe versie over een oude heen geïnstalleerd kan worden.
    // Keystore + wachtwoorden staan buiten de bronmap: ~/.android/immichclient-release.properties (storeFile, storePassword,
    // keyAlias, keyPassword; chmod 600; het oudere ~/.android/fotos-release.properties wordt ook nog gelezen) of de
    // omgevingsvariabelen IMMICHCLIENT_KEYSTORE / IMMICHCLIENT_KEYSTORE_PASSWORD / IMMICHCLIENT_KEY_ALIAS /
    // IMMICHCLIENT_KEY_PASSWORD. Ontbreekt alles, dan de debug-sleutel.
    val releaseProps = Properties().apply {
        val huis = System.getProperty("user.home")
        val f = listOf("immichclient-release.properties", "fotos-release.properties").map { File(huis, ".android/$it") }.firstOrNull { it.isFile }
        f?.inputStream()?.use { load(it) }
    }
    fun releaseWaarde(naam: String, env: String) = releaseProps.getProperty(naam) ?: System.getenv("IMMICHCLIENT_$env") ?: System.getenv("FOTOS_$env")
    val releaseStore = releaseWaarde("storeFile", "KEYSTORE")?.let { File(it) }?.takeIf { it.isFile }
    if (releaseStore != null) signingConfigs.create("release") {
        storeFile = releaseStore
        storePassword = releaseWaarde("storePassword", "KEYSTORE_PASSWORD")
        keyAlias = releaseWaarde("keyAlias", "KEY_ALIAS") ?: "fotos"
        keyPassword = releaseWaarde("keyPassword", "KEY_PASSWORD")
    }

    buildTypes {
        // Altijd de release-build op de tv zetten: Compose is in een debug-build merkbaar trager.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api")
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Beelden: geheugen- en schijfcache, OkHttp met de API-sleutel in de header
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR-code op het instelscherm (alleen de encoder, ~0,5 MB)
    implementation("com.google.zxing:core:3.5.3")
    // Wereldkaart: MapLibre (OpenGL) met de vector-tegelstijl van Immich (tiles.immich.cloud).
    // 12.3.1 is de nieuwste die met Kotlin 2.0 is gebouwd; 13.x (Kotlin 2.2-metadata) compileert niet met Kotlin 2.0.21.
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    // Screensaver: Compose in een DreamService heeft een eigen Lifecycle/SavedState-eigenaar nodig (Dream.kt)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    // Screensaver instellen via de eigen netwerk-adb van de box (ScreensaverInstellen.kt); klein, alleen okio
    implementation("dev.mobile:dadb:1.2.10")
    // Video's
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
}
