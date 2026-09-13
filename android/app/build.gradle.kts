plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Firma y version para la CI (publicacion continua en cada push):
// si las variables no existen (compilacion local), se usa la version por
// defecto y el APK de release sale sin firmar, igual que hasta ahora.
val ciStorePath = System.getenv("SIGNING_KEYSTORE_PATH")
val ciStorePass = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "locator-agent"
val ciSigned = !ciStorePath.isNullOrBlank() && !ciStorePass.isNullOrBlank()

android {
    namespace = "com.locator.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.locator.agent"
        minSdk = 26          // Android 8.0
        targetSdk = 34       // Android 14
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (ciSigned) {
            create("ci") {
                storeFile = file(ciStorePath!!)
                storePassword = ciStorePass
                keyAlias = ciKeyAlias
                keyPassword = ciStorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (ciSigned) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Lint sigue ejecutandose y reporta en el log, pero no bloquea el build de CI
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Ubicacion hibrida (fusa GPS + celdas + WiFi)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Base de datos cifrada offline
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // IMPORTANTE: usar el artifact -ktx de sqlcipher (el antiguo net.zetetic:android-database-sqlcipher esta deprecado)
    implementation("net.zetetic:sqlcipher-android:4.5.6")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Preferencias cifradas (token de emparejamiento)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Sincronizacion en segundo plano
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // HTTP con pooling/HTTP-2
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Corrutinas + lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
