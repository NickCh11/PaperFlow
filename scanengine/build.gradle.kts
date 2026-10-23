plugins { id("com.android.library") }
android {
    namespace = "com.paperflow.scanengine"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    androidResources { noCompress += "tflite" }
}
dependencies {
    api(libs.opencv.android)
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("com.google.ai.edge.litert:litert-support-api:1.4.1")
    api("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(project(":translation"))
}

