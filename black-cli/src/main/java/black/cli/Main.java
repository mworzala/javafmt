package black.cli;

import black.Black;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

public class Main {
    private static final String USAGE = """
            black [options] format [files...]
                            check [files...]
            
            TODO description
            note that - can be used as a file name to read from stdin.
            
            Available options:
            --threads <n>, -t<n>  number of threads to use (default: 1)
            --version, -v         show version and exit
            --help, -h            show this help message and exit
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

    private static final Path cwd = Path.of("").toAbsolutePath();
    private static final AtomicInteger changedCount = new AtomicInteger();
    private static final List<Diff> diffs = new ArrayList<>(); // only present with diff flag.

    private static Mode mode = Mode.CHECK;

    static void main(String[] args) {
        var flags = new Flags("black", USAGE);
        var threads = flags.intFlag("threads", "t", 1, "number of threads to use");
        flags.parse(args);

        var paths = flags.args();
        if (paths.isEmpty()) {
            flags.printUsage();
            System.exit(1);
        }

        var command = paths.removeFirst();
        mode = switch (command) {
            case "check" -> Mode.CHECK;
            case "format" -> Mode.FORMAT;
            default -> {
                System.err.println("black: unknown command '" + command + "'");
                System.exit(1);
                yield Mode.CHECK; // unreachable
            }
        };

        var stdin = !paths.isEmpty() && paths.contains("-");
        if (stdin && paths.size() > 1) {
            System.err.println("black: cannot mix stdin and files");
            System.exit(1);
        }

        Collection<Path> files = !stdin ? resolveFiles(paths) : List.of();
        if (!stdin && files.isEmpty()) {
            System.err.println("black: no files listed");
            System.exit(1);
        }

        try (var executor = Executors.newFixedThreadPool(threads.get())) {
            files.forEach(file -> executor.submit(() -> processFile(file)));
        }

        diffs.sort(Comparator.comparing(Diff::path));
        for (var diff : diffs) {
            diff.printLines.forEach(System.out::println);
        }

        if (mode == Mode.CHECK && changedCount.get() > 0) System.exit(1);
    }

    private static Collection<Path> resolveFiles(List<String> paths) {
        Set<Path> resolved = new HashSet<>();
        for (var raw : paths) {
            try {
                var path = Path.of(raw).toRealPath();
                if (Files.isRegularFile(path)) {
                    resolved.add(path);
                } else if (Files.isDirectory(path)) {
                    var stream = Files.find(path, Integer.MAX_VALUE, JAVA_FILE_MATCHER);
                    try (stream) {
                        stream.forEach(resolved::add);
                    }
                } else {
                    System.err.println(raw + ": no such file or directory");
                    System.exit(1);
                }
            } catch (Exception e) {
                System.err.println(raw + ": " + e.getMessage());
            }
        }
        return resolved;
    }

    private static void processFile(Path path) {
        var printPath = cwd.relativize(path);
        try {
            var source = Files.readString(path);
            var formatted = Black.formatSource(source);

            var changed = !formatted.equals(source);
            if (changed) changedCount.incrementAndGet();

            if (mode == Mode.FORMAT) {
                Files.writeString(path, formatted);
            } else if (mode == Mode.CHECK && changed) {
                // System.out.println(printPath);

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
        } catch (IOException e) {
            System.err.println(path + ": " + e.getMessage());
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }
}
