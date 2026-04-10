package dev.javafmt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

@Deprecated
public final class Black {

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
        options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.ENABLED);
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
        return printer.print(a2d.result());
    }
}
