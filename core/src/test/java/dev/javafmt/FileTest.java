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

        // Cases under testcases/module/ are module-info.java units: the parser only accepts
        // their module syntax when told the unit name.
        boolean module = testFile.toString().replace('\\', '/').contains("/module/");

        var actual = formatSource(input, module);
        assertEquals(expected, actual);

        // No curated case may drop or duplicate a comment.
        CommentPreservation.assertPreserved(testFile.toString(), input, actual, parse(input, module), parse(actual, module));

        // Idempotence: formatting the expected output again must be a no-op.
        var reformatted = formatSource(expected, module);
        assertEquals(expected, reformatted, "formatter is not idempotent for " + testFile);
    }

    private static org.eclipse.jdt.core.dom.CompilationUnit parse(String source, boolean module) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);
        if (module) parser.setUnitName("module-info.java");
        parser.setSource(source.toCharArray());
        return (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(null);
    }

    private static String formatSource(String source, boolean module) {
        var ast = parse(source, module);

        var commentMap = CommentMap.build(ast, source);
        var a2d = new AstToDoc(source, commentMap);
        ast.accept(a2d);

        // STRICT emission ledger: every attached comment must be emitted. A drop here is a
        // located, build-breaking failure — not something that slips through to the whole-file
        // multiset check. Curated cases are the contract, so they hold the strictest line.
        var dropped = CommentLedger.dropped(commentMap.attachedComments(), a2d.emittedComments());
        if (!dropped.isEmpty()) {
            throw new AssertionError(CommentLedger.describe(dropped, source));
        }

        var printer = new DocPrinter(100);
        var out = printer.print(a2d.result());

        return Arrays.stream(out.split("\n"))
            .map(String::stripTrailing)
            .collect(Collectors.joining("\n"))
            .strip();
    }
}
