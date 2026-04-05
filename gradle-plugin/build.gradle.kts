plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "black"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains:annotations:26.1.0")

    // Just compileOnly since we include the jar output of the root project
    // into this one. We also need to include the root project dependencies
    // so they are still downloaded by gradle.
    compileOnly(rootProject)
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.45.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Jar>("jar") {
    from(rootProject.sourceSets.main.map { it.output })
}

gradlePlugin {
    plugins {
        create("black") {
            id = "black"
            implementationClass = "black.gradle.BlackPlugin"
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

val functionalTestSourceSet = sourceSets.create("functionalTest") {
}

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
    // Use JUnit Jupiter for unit tests.
    useJUnitPlatform()
}