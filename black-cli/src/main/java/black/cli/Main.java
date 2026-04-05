package black.cli;

import black.Black;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
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

    private static final BiPredicate<Path, BasicFileAttributes> JAVA_FILE_MATCHER = (path, attrs) ->
            attrs.isRegularFile() && path.toString().endsWith(".java");

    private enum Mode { FORMAT, CHECK }

    static void main(String[] args) throws InterruptedException {
        var flags = new Flags("black", USAGE);
        var threads = flags.intFlag("threads", "t", 1, "number of threads to use");
        flags.parse(args);

        var paths = flags.args();
        if (paths.isEmpty()) {
            flags.printUsage();
            System.exit(1);
        }

        var command = paths.removeFirst();
        var mode = switch (command) {
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
        try {
            var source = Files.readString(path);
            var formatted = Black.formatSource(source);
            if (formatted.equals(source)) {
                System.out.println(path);
            } else {
                Files.writeString(path, formatted);
            }
        } catch (IOException e) {
            System.err.println(path + ": " + e.getMessage());
            return;
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }
}
