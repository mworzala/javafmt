plugins {
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":black-core"))
}

application {
    mainClass = "black.cli.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        nativeImageCapable = true
    }
}

graalvmNative {
    binaries.named("main") {
        imageName = "black"

        buildArgs.add("--future-defaults=all")
    }
}
