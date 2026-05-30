package dev.javafmt.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void onlyChangedReportsModifiedAndSkipsUnmodifiedMisformatted(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        var changed = repo.resolve("Changed.java");
        var untouched = repo.resolve("Untouched.java");
        Files.writeString(changed, FORMATTED);
        Files.writeString(untouched, MISFORMATTED);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "initial");

        // Only Changed.java is modified relative to HEAD; Untouched.java stays misformatted but clean.
        Files.writeString(changed, MISFORMATTED);

        var r = invoke("", "check", "--only-changed", repo.toString());

        assertEquals(1, r.code(), r.stderr());
        assertTrue(r.stdout().contains("Changed.java"), r.stdout());
        assertFalse(r.stdout().contains("Untouched.java"), r.stdout());
    }

    @Test
    void onlyChangedWithCleanTreeFormatsNothing(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        var file = repo.resolve("M.java");
        Files.writeString(file, MISFORMATTED);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "initial");

        // Misformatted, but committed and unchanged: --only-changed has nothing to do.
        var r = invoke("", "check", "--only-changed", repo.toString());

        assertEquals(0, r.code(), r.stderr());
        assertEquals("", r.stdout());
    }

    @Test
    void onlyChangedAgainstRefComparesToThatRef(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        var base = repo.resolve("Base.java");
        Files.writeString(base, MISFORMATTED);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "base");

        var added = repo.resolve("Added.java");
        Files.writeString(added, MISFORMATTED);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "added");

        // Both files are misformatted, but only Added.java differs from the previous commit.
        var r = invoke("", "check", "--only-changed=HEAD~1", repo.toString());

        assertEquals(1, r.code(), r.stderr());
        assertTrue(r.stdout().contains("Added.java"), r.stdout());
        assertFalse(r.stdout().contains("Base.java"), r.stdout());
    }

    @Test
    void gitignoreRespectedByDefaultWhenWalkingDirectory(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        Files.writeString(repo.resolve(".gitignore"), "Ignored.java\n");
        Files.writeString(repo.resolve("Tracked.java"), MISFORMATTED);
        Files.writeString(repo.resolve("Ignored.java"), MISFORMATTED);

        // No flag: .gitignore is honored automatically when discovering files by walking.
        var r = invoke("", "check", repo.toString());

        assertEquals(1, r.code(), r.stderr());
        assertTrue(r.stdout().contains("Tracked.java"), r.stdout());
        assertFalse(r.stdout().contains("Ignored.java"), r.stdout());
    }

    @Test
    void explicitlyNamedGitignoredFileIsStillFormatted(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        Files.writeString(repo.resolve(".gitignore"), "Ignored.java\n");
        var ignored = repo.resolve("Ignored.java");
        Files.writeString(ignored, MISFORMATTED);

        // Naming the file explicitly overrides .gitignore.
        var r = invoke("", "check", ignored.toString());

        assertEquals(1, r.code(), r.stderr());
        assertTrue(r.stdout().contains("Ignored.java"), r.stdout());
    }

    @Test
    void allFilesGitignoredFormatsNothing(@TempDir Path repo) throws Exception {
        assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        Files.writeString(repo.resolve(".gitignore"), "*.java\n");
        Files.writeString(repo.resolve("Ignored.java"), MISFORMATTED);

        // Every walked file is ignored: nothing to do, which is success rather than an error.
        var r = invoke("", "check", repo.toString());

        assertEquals(0, r.code(), r.stderr());
        assertEquals("", r.stdout());
    }

    @Test
    void directoryWalkOutsideGitRepoStillFormats(@TempDir Path tmp) throws Exception {
        // gitignore handling degrades silently outside a repo: walking still formats everything.
        Files.writeString(tmp.resolve("E.java"), MISFORMATTED);

        var r = invoke("", "check", tmp.toString());

        assertEquals(1, r.code(), r.stderr());
        assertTrue(r.stdout().contains("E.java"), r.stdout());
    }

    @Test
    void onlyChangedOutsideRepoExitsTwo(@TempDir Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "git not available");
        var file = tmp.resolve("E.java");
        Files.writeString(file, MISFORMATTED);

        var r = invoke("", "check", "--only-changed", file.toString());

        assertEquals(2, r.code());
        assertTrue(r.stderr().contains("git repository"), r.stderr());
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void initRepo(Path dir) throws Exception {
        git(dir, "init", "-q");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "javafmt test");
        git(dir, "config", "commit.gpgsign", "false");
    }

    private static void git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        var proc = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        var output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, proc.waitFor(), "git " + String.join(" ", args) + " failed:\n" + output);
    }

}
