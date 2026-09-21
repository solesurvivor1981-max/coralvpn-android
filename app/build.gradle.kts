plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.aiboost.coralvpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.aiboost.coralvpn"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.4"
        vectorDrawables { useSupportLibrary = true }
        // libbox.aar ships arm64-v8a + armeabi-v7a (universal; TV boxes are often 32-bit).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    val keystorePath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed only when the CI provides the keystore; otherwise unsigned release.
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/*.kotlin_module"
        }
        jniLibs {
            // Compress the native core in the APK (~50MB .so -> a few MB) so the
            // download fits distribution limits; extracted on install.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // sing-box core (built by build-libbox.yml, fetched into app/libs in CI)
    implementation(files("libs/libbox.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
