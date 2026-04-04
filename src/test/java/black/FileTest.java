package black;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FileTest {

    static Stream<Path> testFiles() throws IOException {
        var roots = FileTest.class.getClassLoader().getResources("");
        return java.util.Collections.list(roots).stream()
                .map(url -> {
                    try {
                        return Path.of(url.toURI());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(Files::isDirectory)
                .flatMap(root -> {
                    try {
                        return Files.walk(root);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(p -> p.toString().endsWith(".test"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testFiles")
    void format(Path testFile) throws IOException {
        var content = Files.readString(testFile);
        var parts = content.split("---\n", 2);
        var input = parts[0];
        var expected = parts[1];

        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(input.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        var ast = parser.createAST(null);

        var a2d = new AstToDoc(input);
        ast.accept(a2d);

        var printer = new DocPrinter(100);
        var actual = printer.print(a2d.result());

        actual = Arrays.stream(actual.split("\n"))
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"));

        assertEquals(expected.strip(), actual.strip());
    }
}
