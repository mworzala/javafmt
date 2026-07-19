package dev.javafmt.gradle;

import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

// formatJava rewrites its input files in place, so it is untracked: it must run every
// invocation and never be satisfied from history or the build cache. The reverted-content
// cases below are the regression tests for the stale-state bug where a git revert back to
// previously fingerprinted content left the task UP-TO-DATE on an unformatted tree.
class FormatJavaRerunFunctionalTest extends FunctionalTestSupport {

    @Test
    void neverUpToDate() throws IOException {
        setupBuild();
        writeFile(srcFile("Main.java"), "class   Main   {   int   x  ;  }\n");

        runner("formatJava").build();
        var second = runner("formatJava").build();

        assertEquals(TaskOutcome.SUCCESS, second.task(":formatJava").getOutcome());
    }

    @Test
    void reformatsContentRevertedToPreviouslyFormattedState() throws IOException {
        setupBuild();
        var unformatted = "class   Main   {   int   x  ;  }\n";
        writeFile(srcFile("Main.java"), unformatted);

        runner("formatJava").build();
        var formatted = readFile(srcFile("Main.java"));

        // Simulate `git checkout`/`stash` restoring the exact pre-format content.
        writeFile(srcFile("Main.java"), unformatted);
        var result = runner("formatJava").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":formatJava").getOutcome());
        assertEquals(formatted, readFile(srcFile("Main.java")));
    }

    @Test
    void reformatsRevertedContentEvenWithBuildCache() throws IOException {
        setupBuild();
        var unformatted = "class   Main   {   int   x  ;  }\n";
        writeFile(srcFile("Main.java"), unformatted);

        runner("formatJava", "--build-cache").build();
        var formatted = readFile(srcFile("Main.java"));

        writeFile(srcFile("Main.java"), unformatted);
        var result = runner("formatJava", "--build-cache").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":formatJava").getOutcome());
        assertEquals(formatted, readFile(srcFile("Main.java")));
    }
}
