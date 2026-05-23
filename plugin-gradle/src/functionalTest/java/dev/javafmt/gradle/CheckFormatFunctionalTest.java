package dev.javafmt.gradle;

import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckFormatFunctionalTest extends FunctionalTestSupport {

    @Test
    void cleanTreePasses() throws IOException {
        setupBuild();
        writeFile(srcFile("Main.java"), "class Main {}\n");

        var result = runner("checkFormat").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkFormat").getOutcome());
    }

    @Test
    void dirtyTreeFails() throws IOException {
        setupBuild();
        writeFile(srcFile("Main.java"), "class   Main   {   int   x  ;  }\n");

        var result = runner("checkFormat").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":checkFormat").getOutcome());
        assertTrue(result.getOutput().contains("Main.java"),
                "expected output to mention Main.java but got:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("need formatting"),
                "expected 'need formatting' summary in output but got:\n" + result.getOutput());
    }

    @Test
    void cleanTreeIsUpToDateOnSecondRun() throws IOException {
        setupBuild();
        writeFile(srcFile("Main.java"), "class Main {}\n");

        runner("checkFormat").build();
        var second = runner("checkFormat").build();

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":checkFormat").getOutcome());
    }

    @Test
    void reportFileListsAffectedPaths() throws IOException {
        setupBuild();
        writeFile(srcFile("a/Bad.java"), "class   Bad   {   int   x  ;  }\n");
        writeFile(srcFile("a/Good.java"), "class Good {}\n");

        runner("checkFormat").buildAndFail();

        var report = new File(projectDir, "build/javafmt/check-report.txt");
        var content = readFile(report);
        assertTrue(content.contains("src/main/java/a/Bad.java"),
                "expected report to list Bad.java; report:\n" + content);
        assertTrue(!content.contains("Good.java"),
                "expected report to NOT list Good.java; report:\n" + content);
    }

    @Test
    void wiredIntoCheckLifecycle() throws IOException {
        setupBuild();
        writeFile(srcFile("Main.java"), "class   Main   {   int   x  ;  }\n");

        var result = runner("check", "-x", "test").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":checkFormat").getOutcome());
    }
}
