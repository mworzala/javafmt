package dev.javafmt.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

abstract class FunctionalTestSupport {
    @TempDir
    File projectDir;

    protected File buildFile() {
        return new File(projectDir, "build.gradle");
    }

    protected File settingsFile() {
        return new File(projectDir, "settings.gradle");
    }

    protected File srcFile(String relative) {
        return new File(projectDir, "src/main/java/" + relative);
    }

    protected void writeFile(File file, String content) throws IOException {
        Files.createDirectories(file.toPath().getParent());
        Files.writeString(file.toPath(), content);
    }

    protected String readFile(File file) throws IOException {
        return Files.readString(file.toPath());
    }

    protected void setupBuild() throws IOException {
        setupBuild("");
    }

    protected void setupBuild(String extra) throws IOException {
        writeFile(settingsFile(), "");
        writeFile(buildFile(), """
                plugins {
                    id("java")
                    id("dev.javafmt.gradle")
                }
                """ + extra);
    }

    protected GradleRunner runner(String... args) {
        var allArgs = new String[args.length + 1];
        allArgs[0] = "--configuration-cache";
        System.arraycopy(args, 0, allArgs, 1, args.length);
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(allArgs);
    }
}
