package dev.javafmt.gradle;

import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxErrorFunctionalTest extends FunctionalTestSupport {

    @Test
    void formatJavaFailsOnSyntaxErrorWithFilePath() throws IOException {
        setupBuild();
        writeFile(srcFile("Bad.java"), "class Bad { void m( {} }\n");

        var result = runner("formatJava").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":formatJava").getOutcome());
        var output = result.getOutput();
        assertTrue(output.contains("Bad.java"),
                "expected output to mention Bad.java; got:\n" + output);
        assertTrue(output.matches("(?s).*Bad\\.java:\\d+:\\d+: syntax error: .*"),
                "expected output to include Bad.java:line:col syntax error; got:\n" + output);
    }

    @Test
    void checkFormatFailsOnSyntaxErrorWithFilePath() throws IOException {
        setupBuild();
        writeFile(srcFile("Bad.java"), "class Bad { void m( {} }\n");

        var result = runner("checkFormat").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":checkFormat").getOutcome());
        var output = result.getOutput();
        assertTrue(output.contains("Bad.java"),
                "expected output to mention Bad.java; got:\n" + output);
        assertTrue(output.matches("(?s).*Bad\\.java:\\d+:\\d+: syntax error: .*"),
                "expected output to include Bad.java:line:col syntax error; got:\n" + output);
    }

    @Test
    void multipleSyntaxErrorsAreAllReportedTogether() throws IOException {
        setupBuild();
        writeFile(srcFile("A.java"), "class A { void m( {} }\n");
        writeFile(srcFile("B.java"), "class B { void m( {} }\n");
        // A well-formed file mixed in to confirm we don't bail on the first error.
        writeFile(srcFile("Ok.java"), "class Ok {}\n");

        var result = runner("formatJava").buildAndFail();

        var output = result.getOutput();
        assertTrue(output.contains("A.java"), "expected A.java; got:\n" + output);
        assertTrue(output.contains("B.java"), "expected B.java; got:\n" + output);
    }
}
