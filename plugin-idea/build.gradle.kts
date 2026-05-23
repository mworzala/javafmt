import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.intellij.platform)
}

group = "dev.javafmt"
version = System.getenv("JAVAFMT_VERSION") ?: "dev"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Resolvable-only configuration whose files we hand to the test as a system property.
// Lets the URLClassLoader test actually have a real core+JDT jar set to load from,
// without putting them on the plugin's runtime/test classpath (which would defeat
// the point of testing dynamic loading).
val formatterTestClasspath by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":api"))

    formatterTestClasspath(project(":core"))

    intellijPlatform {
        intellijIdea("2026.1")

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")

        testFramework(TestFrameworkType.Platform)
    }

    // BasePlatformTestCase extends junit.framework.TestCase (JUnit3); the IntelliJ
    // testFramework declaration doesn't pull JUnit onto the test compile classpath.
    testImplementation("junit:junit:4.13.2")
}

tasks.buildPlugin {
    archiveBaseName.set("javafmt-idea")
}

tasks.test {
    useJUnit()
    val classpathFiles = formatterTestClasspath
    inputs.files(classpathFiles)
    doFirst {
        systemProperty(
            "javafmt.test.classpath",
            classpathFiles.files.joinToString(File.pathSeparator) { it.absolutePath }
        )
    }
}
