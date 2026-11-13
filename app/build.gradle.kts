plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPath = System.getenv("ARCHIVATOR_KEYSTORE_PATH")
val signingReady = !signingPath.isNullOrBlank() &&
    !System.getenv("ARCHIVATOR_STORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("ARCHIVATOR_KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("ARCHIVATOR_KEY_PASSWORD").isNullOrBlank()

android {
    signingConfigs {
        if (signingReady) create("stableRelease") {
            storeFile = file(signingPath!!)
            storePassword = System.getenv("ARCHIVATOR_STORE_PASSWORD")
            keyAlias = System.getenv("ARCHIVATOR_KEY_ALIAS")
            keyPassword = System.getenv("ARCHIVATOR_KEY_PASSWORD")
        }
    }
    namespace = "ch.archivator.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.archivator.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.5.0-beta1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingReady) signingConfig = signingConfigs.getByName("stableRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
