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
    api(project(":api"))
    api(libs.jspecify)
    implementation(libs.jdt) {
        exclude(group = "net.java.dev.jna")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.core.filesystem")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.core.expressions")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.core.commands")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.equinox.app")
        exclude(group = "org.eclipse.platform", module = "org.eclipse.equinox.registry")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("corpus")
    }
}

val corpusRoot = layout.buildDirectory.dir("corpus")

data class Corpus(val name: String, val repo: String, val tag: String)

// Each entry is cloned into build/corpus/<name>/ and walked by CorpusTest.
// To add a corpus: append an entry; nothing else needs to change.
val corpora = listOf(
    Corpus(
        name = "junit-framework",
        repo = "https://github.com/junit-team/junit-framework.git",
        tag = "r5.14.4"
    ),
    Corpus(
        name = "mapmaker",
        repo = "https://github.com/hollow-cube/mapmaker.git",
        tag = "1.9.2"
    ),
)

val fetchTasks = corpora.map { corpus ->
    val taskSuffix = corpus.name
        .split('-', '_')
        .joinToString("") { it.replaceFirstChar(Char::uppercase) }
    tasks.register<Exec>("fetch${taskSuffix}Corpus") {
        description = "Shallow-clones the ${corpus.name} corpus at ${corpus.tag}."
        group = "verification"
        val outDir = corpusRoot.get().dir(corpus.name).asFile
        outputs.dir(outDir)
        onlyIf { !outDir.resolve(".git").exists() }
        doFirst {
            outDir.parentFile.mkdirs()
            if (outDir.exists()) outDir.deleteRecursively()
        }
        commandLine(
            "git", "clone",
            "--depth", "1",
            "--branch", corpus.tag,
            corpus.repo,
            outDir.absolutePath
        )
    }
}

val fetchCorpus by tasks.registering {
    description = "Fetches every corpus declared in build.gradle.kts."
    group = "verification"
    dependsOn(fetchTasks)
}

val corpusTest by tasks.registering(Test::class) {
    description = "Runs the corpus AST-equivalence test against every declared corpus."
    group = "verification"
    dependsOn(fetchCorpus)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("corpus")
    }
    systemProperty("javafmt.corpus.dir", corpusRoot.get().asFile.absolutePath)
    // Corpus runs the STRICT emission ledger: a comment the handlers and the statement-level
    // mop-up fail to emit (the production fallback is disabled under STRICT) fails the build,
    // so a real drop is caught here rather than silently force-appended.
    systemProperty("javafmt.commentLedger", System.getProperty("javafmt.commentLedger", "strict"))
    // Corpus is large; surface progress and don't fail-fast.
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (System.getenv("CI") != null) {
        signAllPublications()
    }

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
