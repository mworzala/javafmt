package black;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class Black {
    static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: black <file.java> [file2.java ...]");
            System.exit(1);
        }

        int failures = 0;
        for (var arg : args) {
            var path = Path.of(arg);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + path);
                failures++;
                continue;
            }

            try {
                long start = System.nanoTime();
                formatFile(path);
                double duration = (System.nanoTime() - start) / 1_000_000.0;
                System.out.println("Formatted: " + path.getFileName().toString() + " in " + duration + "ms");
            } catch (Exception e) {
                System.err.println("Failed: " + path + " - " + e.getMessage());
                failures++;
            }
        }

        if (failures > 0) {
            System.exit(1);
        }
    }

    public static void formatFile(Path path) throws IOException {
        var source = Files.readString(path);
        var formatted = formatSource(source);
        Files.writeString(path, formatted);
    }

    public static String formatSource(String source) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);

        var ast = (CompilationUnit) parser.createAST(null);

        var problems = ast.getProblems();
        if (problems.length > 0) {
            var errors = Arrays.stream(problems).filter(p -> p.isError()).toList();
            if (!errors.isEmpty()) {
                throw new RuntimeException(
                        "Syntax error: " + errors.getFirst().getMessage() + " (line " + errors.getFirst()
                                .getSourceLineNumber() + ")"
                );
            }
        }

        var a2d = new AstToDoc(source);
        ast.accept(a2d);

        var printer = new DocPrinter(100);
        var formatted = printer.print(a2d.result());

        formatted = formatted.stripTrailing();

        if (!formatted.endsWith("\n")) {
            formatted += "\n";
        }
        return formatted;
    }
}
