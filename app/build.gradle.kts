plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Fixed signing key (step B). CI passes the keystore through environment variables; without them the normal
// throwaway debug key is used (local builds, pull requests from forks). An in-app APK update only works when
// the installed app and the new APK are signed with the SAME key.
val novaKsPath: String? = System.getenv("NOVA_KEYSTORE_PATH")
val novaKsPass: String? = System.getenv("NOVA_KEYSTORE_PASSWORD")
val novaKsAlias: String? = System.getenv("NOVA_KEY_ALIAS")
val novaKeyPass: String? = System.getenv("NOVA_KEY_PASSWORD") ?: novaKsPass
val haveNovaKeystore: Boolean =
    !novaKsPath.isNullOrBlank() && file(novaKsPath).exists() &&
        !novaKsPass.isNullOrBlank() && !novaKsAlias.isNullOrBlank()

android {
    namespace = "com.nova.assistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nova.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("NOVA_VERSION_CODE") ?: "6").toIntOrNull() ?: 6
        versionName = "6.0"
        // The offline engine (llama.cpp) ships arm64-v8a + x86_64; real phones are arm64. Keeps the APK smaller.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        if (haveNovaKeystore) {
            create("nova") {
                storeFile = file(novaKsPath ?: "")
                storePassword = novaKsPass
                keyAlias = novaKsAlias
                keyPassword = novaKeyPass
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (haveNovaKeystore) signingConfig = signingConfigs.getByName("nova")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
