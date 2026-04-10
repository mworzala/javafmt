plugins {
    `java-library`
}

group = "dev.javafmt"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.jspecify)
    implementation(libs.jdt)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
