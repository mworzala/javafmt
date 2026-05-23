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
