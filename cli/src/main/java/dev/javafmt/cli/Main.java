package dev.javafmt.cli;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import dev.javafmt.api.Formatter;
import dev.javafmt.api.Formatter.Result;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

public class Main {
    private static final String USAGE = """
            javafmt [options] format [files...]
                              check [files...]

            An opinionated Java code formatter. `format` rewrites files in place;
            `check` prints a unified diff of what would change and exits non-zero
            when any file would be reformatted. Use `-` as a file name to read
            from stdin (output goes to stdout).

            Files ignored by git are skipped while walking directories; name a file
            explicitly to format it regardless.

            Available options:
            --threads <n>, -t<n>     number of threads to use (default: number of CPUs)
            --release <n>            Java language release (default: 25)
            --enable-preview         enable Java preview features
            --line-length <n>        max line width (default: 100)
            --only-changed[=<ref>]   only process files git reports as changed; with a <ref>
                                     (e.g. origin/main) compare against it instead of HEAD
            --verbose, -V            print stack traces on internal errors
            --help, -h               show this help message and exit

            Exit codes:
              0  success (format completed, or check found no differences)
              1  check mode: at least one file would be reformatted
              2  error (bad flags, missing file, syntax error, I/O failure)
            """.stripIndent();

    private static final BiPredicate<Path, BasicFileAttributes> JAVA_FILE_MATCHER = (
        path,
        attrs
    ) -> attrs.isRegularFile() && path.toString().endsWith(".java");

    private enum Mode {
        FORMAT,
        CHECK
    }

    record Diff(Path path, List<String> printLines) {}

    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;
    private final Path cwd = Path.of("").toAbsolutePath();
    private final AtomicInteger changedCount = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final List<Diff> diffs = Collections.synchronizedList(new ArrayList<>());
    private boolean ignoredAny;

    private Formatter formatter;
    private Mode mode = Mode.CHECK;
    private boolean verbose;

    private Main(InputStream in, PrintStream out, PrintStream err) {
        this.in = in;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        return new Main(in, out, err).execute(args);
    }

    private int execute(String[] args) {
        var flags = new Flags("javafmt", USAGE, err);
        var threads = flags.intFlag("threads", "t", Runtime.getRuntime().availableProcessors(),
                                     "number of threads to use");
        var release = flags.intFlag("release", 25, "Java language release");
        var enablePreview = flags.boolFlag("enable-preview", false, "enable Java preview features");
        var lineLength = flags.intFlag("line-length", 100, "max line width");
        var onlyChanged = flags.optionalStringFlag("only-changed", "",
                                                   "only process files git reports as changed (optionally vs <ref>)");
        var verboseFlag = flags.boolFlag("verbose", "V", false, "print stack traces on internal errors");

        var rc = flags.parse(args);
        if (rc.isPresent()) return rc.getAsInt();
        verbose = verboseFlag.get();

        var paths = flags.args();
        if (paths.isEmpty()) {
            flags.printUsage();
            return 2;
        }

        var command = paths.removeFirst();
        mode = switch (command) {
            case "check" -> Mode.CHECK;
            case "format" -> Mode.FORMAT;
            default -> {
                err.println("javafmt: unknown command '" + command + "'");
                yield null;
            }
        };
        if (mode == null) return 2;

        var stdin = paths.contains("-");
        if (stdin && paths.size() > 1) {
            err.println("javafmt: cannot mix stdin and files");
            return 2;
        }
        if (!stdin && paths.isEmpty()) {
            err.println("javafmt: no files listed");
            return 2;
        }

        formatter = Formatter.create(Formatter.Config.defaults()
                .withRelease(release.get())
                .withPreview(enablePreview.get())
                .withLineLength(lineLength.get()));

        if (stdin) {
            return processStdin();
        }

        var files = resolveFiles(paths);
        if (failed.get() > 0) return 2;
        if (files.isEmpty()) {
            // An empty set purely because everything was gitignored is "nothing to do", not the
            // usage error of pointing javafmt at paths that contain no Java files.
            if (ignoredAny) return 0;
            err.println("javafmt: no files listed");
            return 2;
        }

        // `--only-changed[=<ref>]` is an explicit opt-in that restricts the set to files git
        // reports as changed; it applies uniformly to walked and explicitly-named files. Probe git
        // from a target file rather than the process CWD so the relevant repo is the one holding
        // the files. Because it is requested explicitly, a missing git binary or non-repo is a
        // hard error (unlike the implicit .gitignore handling in resolveFiles, which degrades).
        if (onlyChanged.isSet()) {
            var probe = files.iterator().next().getParent();
            if (!GitFiles.isAvailable(probe)) {
                err.println("javafmt: --only-changed requires a git repository "
                    + "(git is not installed, or " + probe + " is not inside a work tree)");
                return 2;
            }
            try {
                files.retainAll(GitFiles.changedFiles(GitFiles.repoRoot(probe), onlyChanged.get()));
            } catch (IOException e) {
                err.println("javafmt: " + e.getMessage());
                return 2;
            }
            if (files.isEmpty()) return 0;
        }

        try (var executor = Executors.newFixedThreadPool(threads.get())) {
            files.forEach(file -> executor.submit(() -> processFile(file)));
        }

        diffs.sort(Comparator.comparing(Diff::path));
        for (var diff : diffs) {
            diff.printLines.forEach(out::println);
        }

        if (failed.get() > 0) return 2;
        if (mode == Mode.CHECK && changedCount.get() > 0) return 1;
        return 0;
    }

    private Collection<Path> resolveFiles(List<String> paths) {
        Set<Path> resolved = new LinkedHashSet<>();
        Map<Path, Set<Path>> notIgnoredByRepo = new HashMap<>();
        for (var raw : paths) {
            try {
                var path = Path.of(raw).toRealPath();
                if (Files.isRegularFile(path)) {
                    // Explicitly-named files are always honored, even if git would ignore them.
                    resolved.add(path);
                } else if (Files.isDirectory(path)) {
                    List<Path> walked;
                    try (var stream = Files.find(path, Integer.MAX_VALUE, JAVA_FILE_MATCHER)) {
                        walked = stream.toList();
                    }
                    resolved.addAll(filterGitignored(path, walked, notIgnoredByRepo));
                } else {
                    err.println(raw + ": no such file or directory");
                    failed.incrementAndGet();
                }
            } catch (IOException e) {
                err.println(raw + ": " + e.getMessage());
                failed.incrementAndGet();
            }
        }
        return resolved;
    }

    /// Drop git-ignored files from a directory walk. `.gitignore` is respected by default; there is
    /// no flag to opt in. This is best effort: when git is unavailable or `dir` is not inside a work
    /// tree, every walked file is kept. Sets [#ignoredAny] when at least one file is dropped, so an
    /// all-ignored result reads as "nothing to do" rather than "no files listed". The per-repo
    /// allow-set is cached so multiple directory arguments in one repo only query git once, and so
    /// arguments spanning different repos are each filtered against their own repository.
    private List<Path> filterGitignored(Path dir, List<Path> walked, Map<Path, Set<Path>> cache) {
        if (walked.isEmpty() || !GitFiles.isAvailable(dir)) return walked;
        try {
            var root = GitFiles.repoRoot(dir);
            var allowed = cache.get(root);
            if (allowed == null) {
                allowed = GitFiles.notIgnored(root);
                cache.put(root, allowed);
            }
            var kept = new ArrayList<Path>(walked.size());
            for (var file : walked) {
                if (allowed.contains(file)) {
                    kept.add(file);
                } else {
                    ignoredAny = true;
                }
            }
            return kept;
        } catch (IOException e) {
            if (verbose) {
                err.println("javafmt: skipped .gitignore filtering for " + dir + ": " + e.getMessage());
            }
            return walked;
        }
    }

    private int processStdin() {
        String source;
        try {
            source = new String(in.readAllBytes());
        } catch (IOException e) {
            err.println("<stdin>: " + e.getMessage());
            return 2;
        }

        var result = formatter.format(source);
        return switch (result) {
            case Result.Success(var formatted) -> {
                if (mode == Mode.FORMAT) {
                    out.print(formatted);
                    yield 0;
                }
                if (formatted.equals(source)) yield 0;
                var diff = UnifiedDiffUtils.generateUnifiedDiff(
                    "a/<stdin>",
                    "b/<stdin>",
                    source.lines().toList(),
                    DiffUtils.diff(source.lines().toList(), formatted.lines().toList()),
                    2
                );
                diff.forEach(out::println);
                yield 1;
            }
            case Result.SyntaxError(var problems) -> {
                printSyntaxErrors("<stdin>", problems);
                yield 2;
            }
            case Result.Failure(var error) -> {
                printFailure("<stdin>", error);
                yield 2;
            }
        };
    }

    private void processFile(Path path) {
        var printPath = cwd.relativize(path);
        try {
            var source = Files.readString(path);
            switch (formatter.format(source)) {
                case Result.Success(var formatted) -> {
                    var changed = !formatted.equals(source);
                    if (!changed) return;
                    changedCount.incrementAndGet();
                    if (mode == Mode.FORMAT) {
                        Files.writeString(path, formatted);
                    } else {
                        var sourceLines = source.lines().toList();
                        var formattedLines = formatted.lines().toList();
                        var diff = UnifiedDiffUtils.generateUnifiedDiff(
                            "a/" + printPath,
                            "b/" + printPath,
                            sourceLines,
                            DiffUtils.diff(sourceLines, formattedLines),
                            2
                        );
                        diffs.add(new Diff(path, diff));
                    }
                }
                case Result.SyntaxError(var problems) -> {
                    printSyntaxErrors(printPath.toString(), problems);
                    failed.incrementAndGet();
                }
                case Result.Failure(var error) -> {
                    printFailure(printPath.toString(), error);
                    failed.incrementAndGet();
                }
            }
        } catch (IOException e) {
            err.println(printPath + ": " + e.getMessage());
            failed.incrementAndGet();
        } catch (RuntimeException e) {
            printFailure(printPath.toString(), e);
            failed.incrementAndGet();
        }
    }

    private void printSyntaxErrors(String displayPath, List<Formatter.Problem> problems) {
        if (problems.isEmpty()) {
            err.println(displayPath + ": syntax error");
            return;
        }
        for (var p : problems) {
            err.println(displayPath + ":" + p.line() + ":" + p.column() + ": syntax error: " + p.message());
        }
    }

    private void printFailure(String displayPath, Throwable error) {
        var msg = error.getMessage();
        err.println(displayPath + ": error: " + (msg != null ? msg : error.getClass().getSimpleName()));
        if (verbose) error.printStackTrace(err);
    }
}
