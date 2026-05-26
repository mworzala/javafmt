package dev.javafmt.gradle;

import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsPropagationFunctionalTest extends FunctionalTestSupport {

    @Test
    void languageVersionReachesFormatter() throws IOException {
        // Force the formatter to use AST level 8, where 'record' is not a keyword.
        // The record declaration should be rejected as a syntax error.
        setupBuild("""
                import org.gradle.jvm.toolchain.JavaLanguageVersion
                tasks.named('formatJava') {
                    languageVersion = JavaLanguageVersion.of(8)
                }
                """);
        writeFile(srcFile("R.java"), "record R(int x) {}\n");

        var result = runner("formatJava").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":formatJava").getOutcome());
        assertTrue(result.getOutput().contains("R.java"),
                "expected R.java in output; got:\n" + result.getOutput());
    }

    @Test
    void toolchainLanguageVersionReachesFormatter() throws IOException {
        // Configure the project's Java toolchain to Java 21; the plugin must propagate
        // that into the formatter so modern syntax (records, switch arrows, type
        // patterns, '_') parses cleanly.
        setupBuild("""
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(25)
                    }
                }
                """);
        // Multiple files: a regression where the cached parser loses its compiler
        // options after the first parse would format file 1 fine and reject file 2.
        for (int i = 1; i <= 3; i++) {
            writeFile(srcFile("Modern" + i + ".java"), """
                    class Modern%d {
                        sealed interface S permits A, B {}
                        record A(int x) implements S {}
                        record B(int y) implements S {}
                        static String describe(S s) {
                            return switch (s) {
                                case A a -> "a";
                                case B b -> "b";
                            };
                        }
                    }
                    """.formatted(i));
        }

        var result = runner("formatJava").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":formatJava").getOutcome());
    }

    @Test
    void enablePreviewIsAcceptedOnTask() throws IOException {
        setupBuild("""
                tasks.named('formatJava') {
                    enablePreview = true
                }
                tasks.named('checkFormat') {
                    enablePreview = true
                }
                """);
        writeFile(srcFile("Main.java"), "class Main {}\n");

        var result = runner("formatJava", "checkFormat").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":formatJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkFormat").getOutcome());
    }
}
