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
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { implementation(libs.camera.core) }

val requiredAbis = providers.gradleProperty("paperflowAbi").orNull?.let { listOf(it) }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val runtimeFiles = requiredAbis.map { layout.projectDirectory.file("src/main/jniLibs/$it/libpaperflow_runtime.so") }
val checkLanguageRuntime by tasks.registering {
    inputs.files(runtimeFiles)
    doLast {
        runtimeFiles.forEach { library ->
            check(library.asFile.isFile) {
                "Missing ${library.asFile}. Build tools/ci/build_language_runtime.sh for the selected ABI, or download the Offline language runtime workflow artifacts. See docs/BUILD.md."
            }
            check(library.asFile.readText(Charsets.ISO_8859_1).contains("Java_com_paperflow_translation_RuntimeNative_apiVersion")) {
                "Stale offline language runtime. Download or rebuild native artifacts for this source revision."
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) dependsOn(checkLanguageRuntime)
}
