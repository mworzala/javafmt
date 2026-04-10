plugins {
    alias(libs.plugins.intellij.platform)
}

group = "dev.javafmt"
version = "1.0.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        intellijIdea("2026.1")

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
    }
}
