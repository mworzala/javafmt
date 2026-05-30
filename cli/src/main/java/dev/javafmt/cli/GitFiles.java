package dev.javafmt.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class GitFiles {
    private GitFiles() {}

    /// True when `dir` is inside a git work tree and the `git` binary is runnable.
    static boolean isAvailable(Path dir) {
        try {
            var r = exec(dir, List.of("git", "rev-parse", "--is-inside-work-tree"));
            return r.code() == 0 && r.stdoutText().trim().equals("true");
        } catch (IOException e) {
            return false;
        }
    }

    /// Absolute, canonical path to the repository root containing `dir`.
    static Path repoRoot(Path dir) throws IOException {
        var r = exec(dir, List.of("git", "rev-parse", "--show-toplevel"));
        if (r.code() != 0) throw new IOException(r.errorMessage("git rev-parse --show-toplevel"));
        var top = r.stdoutText().trim();
        if (top.isEmpty()) throw new IOException("git rev-parse --show-toplevel produced no output");
        return Path.of(top).toRealPath();
    }

    /// Java files git reports as changed under `repoRoot`. With an empty `baseRef`, compares the
    /// working tree and index against HEAD; otherwise compares both against `baseRef`. Untracked,
    /// non-ignored files are always included. Renames list the new path; deletions are dropped
    /// because their path no longer resolves on disk.
    static Set<Path> changedFiles(Path repoRoot, String baseRef) throws IOException {
        Set<Path> result = new LinkedHashSet<>();

        var unstaged = new ArrayList<>(List.of("git", "diff", "--name-only", "-z"));
        var staged = new ArrayList<>(List.of("git", "diff", "--name-only", "--cached", "-z"));
        if (!baseRef.isEmpty()) {
            unstaged.add(baseRef);
            staged.add(baseRef);
        }

        addJavaFiles(repoRoot, result, exec(repoRoot, unstaged), "git diff");
        addJavaFiles(repoRoot, result, exec(repoRoot, staged), "git diff --cached");
        addJavaFiles(repoRoot, result,
            exec(repoRoot, List.of("git", "ls-files", "--others", "--exclude-standard", "-z")),
            "git ls-files --others");
        return result;
    }

    /// Java files git does not ignore (tracked plus untracked-non-ignored) under `repoRoot`.
    static Set<Path> notIgnored(Path repoRoot) throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        addJavaFiles(repoRoot, result,
            exec(repoRoot, List.of("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z")),
            "git ls-files");
        return result;
    }

    private static void addJavaFiles(Path repoRoot, Set<Path> out, ExecResult r, String what)
        throws IOException {
        if (r.code() != 0) throw new IOException(r.errorMessage(what));
        for (var rel : splitNul(r.stdout())) {
            if (!rel.endsWith(".java")) continue;
            try {
                out.add(repoRoot.resolve(rel).toRealPath());
            } catch (IOException ignored) {
                // Listed by git but absent on disk (e.g. a deletion). It naturally falls out
                // of the intersection with the resolved set, so just skip it.
            }
        }
    }

    /// Split a NUL-delimited byte payload (git's `-z` output) into path strings.
    private static List<String> splitNul(byte[] bytes) {
        var out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                if (i > start) out.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            out.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static ExecResult exec(Path dir, List<String> command) throws IOException {
        var pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        Process proc = pb.start();

        // Drain stderr on a separate thread so a full stderr pipe can never deadlock our
        // blocking read of stdout (which may be large for repos with many files).
        var stderr = new ByteArrayOutputStream();
        var drain = new Thread(() -> {
            try {
                proc.getErrorStream().transferTo(stderr);
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();

        byte[] stdout;
        int code;
        try {
            stdout = proc.getInputStream().readAllBytes();
            code = proc.waitFor();
            drain.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroy();
            throw new IOException("interrupted while running " + String.join(" ", command), e);
        }
        return new ExecResult(code, stdout, stderr.toString(StandardCharsets.UTF_8));
    }

    private record ExecResult(int code, byte[] stdout, String stderr) {
        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String errorMessage(String what) {
            var trimmed = stderr.strip();
            return trimmed.isEmpty()
                ? what + " exited with status " + code
                : what + " failed: " + trimmed;
        }
    }
}
