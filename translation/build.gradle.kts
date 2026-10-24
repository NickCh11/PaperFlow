import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins { id("com.android.library") }

abstract class CheckLanguageRuntimeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeLibraries: ConfigurableFileCollection

    @TaskAction
    fun checkRuntime() {
        runtimeLibraries.files.forEach { library ->
            check(library.isFile) {
                "Missing $library. Build tools/ci/build_language_runtime.sh for the selected ABI, or download the matching Offline language runtime workflow artifacts. See docs/BUILD.md."
            }
            check(library.readText(Charsets.ISO_8859_1).contains("Java_com_paperflow_translation_RuntimeNative_apiVersion")) {
                "Stale offline language runtime. Download or rebuild native artifacts for this source revision."
            }
        }
    }
}
android {
    namespace = "com.paperflow.translation"
    buildTypes.named("debug") {
        enableUnitTestCoverage = providers.gradleProperty("paperflowCoverage").orNull == "true"
        enableAndroidTestCoverage = providers.gradleProperty("paperflowCoverage").orNull == "true"
    }
    testCoverage { jacocoVersion = "0.8.15" }
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
val checkLanguageRuntime by tasks.registering(CheckLanguageRuntimeTask::class) {
    runtimeLibraries.from(runtimeFiles)
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) dependsOn(checkLanguageRuntime)
}
