package dev.javafmt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FileTest {

    static Stream<Path> testFiles() throws Exception {
        var url = FileTest.class.getClassLoader().getResource("testcases");
        if (url == null) throw new IllegalStateException("testcases/ not found on classpath");
        var root = Path.of(url.toURI());
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".test")).toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testFiles")
    void format(Path testFile) throws IOException {
        var content = Files.readString(testFile);
        var parts = content.split("---\n", 2);
        var input = parts[0];
        var expected = parts[1].strip();

        var actual = formatSource(input);
        assertEquals(expected, actual);

        // Idempotence: formatting the expected output again must be a no-op.
        var reformatted = formatSource(expected);
        assertEquals(expected, reformatted, "formatter is not idempotent for " + testFile);
    }

    private static String formatSource(String source) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);
        var ast = (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(null);

        var commentMap = CommentMap.build(ast, source);
        var a2d = new AstToDoc(source, commentMap);
        ast.accept(a2d);

        var printer = new DocPrinter(100);
        var out = printer.print(a2d.result());

        return Arrays.stream(out.split("\n"))
            .map(String::stripTrailing)
            .collect(Collectors.joining("\n"))
            .strip();
    }
}
