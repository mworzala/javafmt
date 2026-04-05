plugins {
    application
    alias(libs.plugins.graalvm.native)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":black-core"))
    implementation(libs.diffutils)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        nativeImageCapable = true
    }
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "black.cli.Main"
}

graalvmNative {
    // TODO: some metadata needed here
    binaries.named("main") {
        imageName = "black"

        buildArgs.add("--future-defaults=all")
    }
}
