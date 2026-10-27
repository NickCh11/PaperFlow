import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.sonarqube") version "7.5.0.8588"
}

subprojects {
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.15" }
    tasks.register<JacocoReport>("coverageReport") {
        description = "Merge JVM and device coverage for SonarQube Cloud."
        dependsOn("testDebugUnitTest")
        sourceDirectories.from(file("src/main/java"), file("src/main/kotlin"))
        classDirectories.from(
            fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
                // This src/debug-only SAF provider and its nested control objects
                // are test fixtures, not code shipped in the release application.
                exclude("com/example/myapplication/TranslationTestDocumentsProvider*.class")
            },
            fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
        )
        executionData.from(fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) { include("**/*.exec") })
        executionData.from(rootProject.fileTree("build/device-coverage") { include("**/*.ec") })
        reports {
            xml.required = true
            html.required = true
        }
    }
    sonar {
        properties {
            property("sonar.coverage.jacoco.xmlReportPaths", layout.buildDirectory.file("reports/jacoco/coverageReport/coverageReport.xml").get().asFile)
        }
    }
}

sonar {
    properties {
        property("sonar.projectName", "PaperFlow")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.projectVersion", java.util.Properties().apply {
            rootProject.file("version.properties").inputStream().use { load(it) }
        }.getProperty("versionName") ?: error("Missing versionName in version.properties"))
        property("sonar.sourceEncoding", "UTF-8")
        // Provision the private Cloud project first; never commit its analysis token.
        providers.environmentVariable("SONAR_ORGANIZATION").orNull?.let {
            property("sonar.organization", it)
        }
        providers.environmentVariable("SONAR_PROJECT_KEY").orNull?.let {
            property("sonar.projectKey", it)
        }
        // Binary images and Office test documents are not UTF-8 source files.
        property("sonar.exclusions", "**/build/**,**/.gradle/**,**/.sonar/**,**/.scannerwork/**,**/third-party/**,**/src/main/assets/**,**/*.webp,**/src/androidTest/assets/**/*.docx,**/src/androidTest/assets/**/*.xlsx,**/src/androidTest/assets/**/*.pptx")
        // C/C++ analysis needs its own compilation database/capture pipeline.
        property("sonar.c.file.suffixes", "-")
        property("sonar.cpp.file.suffixes", "-")
        property("sonar.objc.file.suffixes", "-")
    }
}
