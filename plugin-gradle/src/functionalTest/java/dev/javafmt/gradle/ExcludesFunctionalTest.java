package dev.javafmt.gradle;

import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcludesFunctionalTest extends FunctionalTestSupport {

    @Test
    void formatJavaSkipsExcludedFiles() throws IOException {
        setupBuild("""
                tasks.named('formatJava') {
                    excludes = ['**/generated/**']
                }
                """);
        var unformatted = "class   G   {   int   x  ;  }\n";
        writeFile(srcFile("generated/G.java"), unformatted);
        writeFile(srcFile("Main.java"), "class   Main   {   int   x  ;  }\n");

        runner("formatJava").build();

        assertEquals(unformatted, readFile(srcFile("generated/G.java")));
        // Sanity: a non-excluded file did get reformatted.
        var mainAfter = readFile(srcFile("Main.java"));
        assertEquals(false, mainAfter.equals("class   Main   {   int   x  ;  }\n"),
                "expected Main.java to be reformatted; still: " + mainAfter);
    }

    @Test
    void checkFormatSkipsExcludedFiles() throws IOException {
        setupBuild("""
                tasks.named('checkFormat') {
                    excludes = ['**/generated/**']
                }
                """);
        writeFile(srcFile("generated/G.java"), "class   G   {   int   x  ;  }\n");

        var result = runner("checkFormat").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkFormat").getOutcome());
    }
}
