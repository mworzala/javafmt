package dev.javafmt.gradle;

import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncrementalFormatFunctionalTest extends FunctionalTestSupport {

    // Because formatJava modifies its inputs in place, Gradle records the
    // pre-action input fingerprint on run 1, sees the post-format content as
    // "changed" on run 2 (re-runs idempotently, records the formatted hash),
    // and only on run 3 are the inputs unchanged from the recorded snapshot.
    @Test
    void taskBecomesUpToDateOnceStateSettles() throws IOException {
        setupBuild();
        writeFile(srcFile("Main.java"), "class   Main   {   int   x  ;  }\n");

        runner("formatJava").build();
        runner("formatJava").build();
        var third = runner("formatJava").build();

        assertEquals(TaskOutcome.UP_TO_DATE, third.task(":formatJava").getOutcome());
    }

    @Test
    void incrementalRunReformatsOnlyChangedFile() throws IOException {
        setupBuild();
        writeFile(srcFile("A.java"), "class   A   {   int   x  ;  }\n");
        writeFile(srcFile("B.java"), "class   B   {   int   y  ;  }\n");

        runner("formatJava").build();
        runner("formatJava").build();

        var stamp = new File(projectDir, "build/javafmt/format-stamp.txt");

        // Re-introduce unformatted content for A only; B stays in its formatted state.
        writeFile(srcFile("A.java"), "class   A   {   int   xx  ;  }\n");

        var third = runner("formatJava").build();
        assertEquals(TaskOutcome.SUCCESS, third.task(":formatJava").getOutcome());
        assertEquals("processed=1\n", readFile(stamp));
    }
}
