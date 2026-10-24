// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.sonarqube") version "7.5.0.8588"
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
        property("sonar.exclusions", "**/build/**,**/.gradle/**,**/.sonar/**,**/.scannerwork/**,**/third-party/**,**/src/main/assets/**")
        // C/C++ analysis needs its own compilation database/capture pipeline.
        property("sonar.c.file.suffixes", "-")
        property("sonar.cpp.file.suffixes", "-")
        property("sonar.objc.file.suffixes", "-")
    }
}
