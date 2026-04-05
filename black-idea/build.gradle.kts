plugins {
    alias(libs.plugins.intellij.platform)
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":black-core"))

    intellijPlatform {
        intellijIdea("2026.1")

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
    }
}
