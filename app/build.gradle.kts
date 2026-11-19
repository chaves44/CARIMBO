plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.archaves.carimbo"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.archaves.carimbo"
        minSdk = 26
        targetSdk = 34
        val execucao = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = execucao
        versionName = "1.0.$execucao"
    }

    signingConfigs {
        create("fixa") {
            storeFile = file("carimbo.keystore")
            storePassword = "carimbo123"
            keyAlias = "carimbo"
            keyPassword = "carimbo123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixa")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
