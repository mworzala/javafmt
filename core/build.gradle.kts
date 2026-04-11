plugins {
    `java-library`

    alias(libs.plugins.maven.publish)
}

group = "dev.javafmt"
version = System.getenv("JAVAFMT_VERSION") ?: "dev"

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

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates("dev.javafmt", "core", project.version as String)
    pom {
        name.set("javafmt")
        description.set("An opinionated code formatter for Java")
        inceptionYear.set("2026")
        url.set("https://github.com/mworzala/javafmt")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/mworzala/javafmt/blob/main/LICENSE")
                distribution.set("https://github.com/mworzala/javafmt/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("mworzala")
                name.set("Matt Worzala")
                url.set("https://github.com/mworzala")
            }
        }
        scm {
            url.set("https://github.com/mworzala/javafmt")
            connection.set("scm:git:git://github.com/mworzala/javafmt.git")
            developerConnection.set("scm:git:ssh://git@github.com/mworzala/javafmt.git")
        }
    }
}
