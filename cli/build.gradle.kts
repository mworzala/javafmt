plugins {
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvm.native)
}

group = "dev.javafmt"
version = System.getenv("JAVAFMT_VERSION") ?: "dev"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
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
    mainClass = "dev.javafmt.cli.Main"
}

tasks.shadowJar {
    archiveBaseName.set("javafmt")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Implementation-Title" to "javafmt",
            "Implementation-Version" to project.version,
        )
    }
}

graalvmNative {
    // TODO: some metadata needed here
    binaries.named("main") {
        imageName = "javafmt"

        buildArgs.add("--future-defaults=all")
    }
}
