plugins { id("com.android.library") }
android {
    namespace = "com.paperflow.scanengine"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildTypes.named("debug") {
        enableUnitTestCoverage = providers.gradleProperty("paperflowCoverage").orNull == "true"
        enableAndroidTestCoverage = providers.gradleProperty("paperflowCoverage").orNull == "true"
    }
    testCoverage { jacocoVersion = "0.8.15" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    androidResources { noCompress += "tflite" }
}
dependencies {
    testImplementation(libs.junit)
    api(libs.opencv.android)
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("com.google.ai.edge.litert:litert-support-api:1.4.1")
    api("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(project(":translation"))
}

