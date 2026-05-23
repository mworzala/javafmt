package dev.javafmt.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MainTest {

    private static final String MISFORMATTED = "class Example {\nprivate\nint a = 1;\n}\n";
    private static final String FORMATTED = """
            class Example {
                private int a = 1;

            }
            """;

    record CliResult(int code, String stdout, String stderr) {}

    static CliResult invoke(String stdin, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        int code = Main.run(args, in, new PrintStream(out), new PrintStream(err));
        return new CliResult(
            code,
            out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8)
        );
    }

    @Test
    void formatRewritesFileInPlace(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("E.java");
        Files.writeString(file, MISFORMATTED);

        var r = invoke("", "format", file.toString());

        assertEquals(0, r.code(), r.stderr());
        assertEquals("", r.stdout());
        var rewritten = Files.readString(file);
        assertNotEquals(MISFORMATTED, rewritten);
        assertTrue(rewritten.contains("    private int a = 1;"), rewritten);
    }

    @Test
    void checkOnFormattedFileExitsZeroSilently(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("E.java");
        Files.writeString(file, FORMATTED);

        var r = invoke("", "check", file.toString());

        assertEquals(0, r.code(), r.stderr());
        assertEquals("", r.stdout());
    }

    @Test
    void checkOnMisformattedFilePrintsDiffAndExitsOne(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("E.java");
        Files.writeString(file, MISFORMATTED);
        var originalOnDisk = Files.readString(file);

        var r = invoke("", "check", file.toString());

        assertEquals(1, r.code(), r.stderr());
        assertTrue(r.stdout().contains("--- a/"), r.stdout());
        assertTrue(r.stdout().contains("+++ b/"), r.stdout());
        assertTrue(r.stdout().contains("@@"), r.stdout());
        assertEquals(originalOnDisk, Files.readString(file));
    }

    @Test
    void stdinFormatWritesToStdout() {
        var r = invoke(MISFORMATTED, "format", "-");

        assertEquals(0, r.code(), r.stderr());
        assertTrue(r.stdout().contains("    private int a = 1;"), r.stdout());
    }

    @Test
    void stdinCheckCleanIsSilentZero() {
        var r = invoke(FORMATTED, "check", "-");

        assertEquals(0, r.code(), r.stderr());
        assertEquals("", r.stdout());
    }

    @Test
    void stdinCheckDirtyPrintsDiff() {
        var r = invoke(MISFORMATTED, "check", "-");

        assertEquals(1, r.code(), r.stderr());
        assertTrue(r.stdout().contains("--- a/<stdin>"), r.stdout());
        assertTrue(r.stdout().contains("+++ b/<stdin>"), r.stdout());
    }

    @Test
    void lineLengthAffectsWrapping() {
        var longSig = "class C { void m(int aaaaaaaa, int bbbbbbbb, int cccccccc, int dddddddd) {} }\n";

        var wide = invoke(longSig, "--line-length", "120", "format", "-");
        var narrow = invoke(longSig, "--line-length", "30", "format", "-");

        assertEquals(0, wide.code(), wide.stderr());
        assertEquals(0, narrow.code(), narrow.stderr());
        assertTrue(narrow.stdout().lines().count() > wide.stdout().lines().count(),
            "narrow output should have more lines than wide:\nwide:\n" + wide.stdout() + "\nnarrow:\n" + narrow.stdout());
    }

    @Test
    void releaseFlagIsAccepted() {
        var r = invoke("class A {}\n", "--release", "21", "format", "-");

        assertEquals(0, r.code(), r.stderr());
        assertTrue(r.stdout().contains("class A"), r.stdout());
    }

    @Test
    void enablePreviewFlagIsAccepted() {
        var r = invoke("class A {}\n", "--enable-preview", "format", "-");

        assertEquals(0, r.code(), r.stderr());
        assertTrue(r.stdout().contains("class A"), r.stdout());
    }

    @Test
    void syntaxErrorProducesStructuredMessage(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("Bad.java");
        Files.writeString(file, "class { }\n");

        var r = invoke("", "format", file.toString());

        assertEquals(2, r.code());
        assertTrue(r.stderr().contains("syntax error"), r.stderr());
        assertTrue(r.stderr().matches("(?s).*Bad\\.java:\\d+:\\d+: syntax error:.*"), r.stderr());
    }

    @Test
    void unknownFlagExitsTwo() {
        var r = invoke("", "--bogus", "format", "foo.java");

        assertEquals(2, r.code());
        assertTrue(r.stderr().contains("unknown flag"), r.stderr());
    }

    @Test
    void unknownCommandExitsTwo(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("E.java");
        Files.writeString(file, FORMATTED);

        var r = invoke("", "frobnicate", file.toString());

        assertEquals(2, r.code());
        assertTrue(r.stderr().contains("unknown command"), r.stderr());
    }

    @Test
    void noArgsPrintsUsageAndExitsTwo() {
        var r = invoke("", new String[0]);

        assertEquals(2, r.code());
        assertTrue(r.stderr().contains("javafmt"), r.stderr());
    }

    @Test
    void noFilesAfterCommandExitsTwo() {
        var r = invoke("", "format");

        assertEquals(2, r.code());
        assertTrue(r.stderr().contains("no files"), r.stderr());
    }

    @Test
    void helpExitsZero() {
        var r = invoke("", "--help");

        assertEquals(0, r.code());
        assertTrue(r.stderr().contains("javafmt"), r.stderr());
        assertTrue(r.stderr().contains("--line-length"), r.stderr());
    }
}
