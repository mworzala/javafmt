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
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (System.getenv("CI") != null) {
        signAllPublications()
    }

    coordinates("dev.javafmt", "api", project.version as String)
    pom {
        name.set("javafmt-api")
        description.set("Stable API and SPI for the javafmt code formatter")
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
