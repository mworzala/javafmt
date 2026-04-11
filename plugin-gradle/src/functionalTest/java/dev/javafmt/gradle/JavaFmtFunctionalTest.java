package dev.javafmt.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JavaFmtFunctionalTest {
    @TempDir
    File projectDir;

    private File getBuildFile() {
        return new File(projectDir, "build.gradle");
    }

    private File getSettingsFile() {
        return new File(projectDir, "settings.gradle");
    }

    @BeforeEach
    void setup() throws IOException {
        writeString(getSettingsFile(), "");
        writeString(getBuildFile(), """
                    plugins {
                        id("java")
                        id("dev.javafmt.gradle")
                    }
                """
        );
    }

    @Test
    void formatSingleFile() throws IOException {
        var testFile = new File(projectDir, "src/main/java/Main.java");
        writeString(testFile, """
                void main() {
                    IO.println( "Hello, world!"      );
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("formatJava", "--stacktrace")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":formatJava").getOutcome());

        assertEquals(
                """
                        void main() {
                            IO.println("Hello, world!");
                        }
                        """.strip(),
                readString(testFile).strip());
    }

    private static void writeString(File file, String string) throws IOException {
        Files.createDirectories(file.toPath().getParent());
        Files.writeString(file.toPath(), string);
    }

    private static String readString(File file) throws IOException {
        return Files.readString(file.toPath());
    }

}
