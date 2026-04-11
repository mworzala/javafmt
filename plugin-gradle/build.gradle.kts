import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.gradle.plugin.publish)
    signing
}


group = "dev.javafmt"
version = System.getenv("JAVAFMT_VERSION") ?: "dev"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    website.set("https://github.com/mworzala/javafmt")
    vcsUrl.set("https://github.com/mworzala/javafmt")

    plugins {
        create("javafmt") {
            id = "dev.javafmt.gradle"
            implementationClass = "dev.javafmt.gradle.JavaFmtPlugin"

            displayName = "javafmt"
            description = "Highly opinionated Java formatter"
            tags.set(listOf("java", "format", "formatter", "code style"))

            compatibility {
                features.configurationCache = true
            }

        }
    }
}

publishing {
    repositories {
        maven {
            url = uri("${layout.projectDirectory}/publish")
        }
    }
}

val functionalTestSourceSet = sourceSets.create("functionalTest")

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val functionalTest by tasks.registering(Test::class) {
    description = "Runs functional tests."
    group = "verification"
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.named<Task>("check") {
    dependsOn(functionalTest)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
