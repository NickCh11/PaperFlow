plugins {
    alias(libs.plugins.android.application)
}

val modelMirrorRepository = providers.gradleProperty("paperflowModelRepository")
    .orElse(providers.environmentVariable("PAPERFLOW_MODEL_REPOSITORY")).orElse("").get()
require(modelMirrorRepository.isEmpty() || (modelMirrorRepository.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]+")) && modelMirrorRepository.substringAfter('/') !in setOf(".", ".."))) {
    "paperflowModelRepository must be a public GitHub owner/repository"
}

android {
    buildFeatures { resValues = true }
    namespace = "com.example.myapplication"
    ndkVersion = "27.2.12479018"
    providers.gradleProperty("paperflowNdkPath").orNull?.let { ndkPath = it }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        resValue("string", "model_mirror_repository", modelMirrorRepository)
        applicationId = "com.example.myapplication"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.example.myapplication.PaperFlowTestRunner"
        if (!providers.gradleProperty("paperflowAbi").isPresent) ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64")) }
        providers.gradleProperty("paperflowAbi").orNull?.let { selectedAbi ->
            require(selectedAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                "Unsupported paperflowAbi: $selectedAbi"
            }
            ndk { abiFilters.add(selectedAbi) }
        }
    }

    buildTypes {
        release {
            proguardFiles("proguard-rules.pro")
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
    testOptions { animationsDisabled = true }
}

dependencies {
    implementation(project(":scanengine"))
    implementation(project(":translation"))
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(libs.androidx.appcompat)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.opencv.android)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

