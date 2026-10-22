plugins { id("com.android.library") }
android {
    namespace = "com.paperflow.translation"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    providers.gradleProperty("paperflowNdkPath").orNull?.let { ndkPath = it }
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters.addAll(providers.gradleProperty("paperflowAbi").orNull?.let { listOf(it) } ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")) }
        externalNativeBuild { cmake {
            arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            targets += "bergamot_jni"
        } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
