package black;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.List;

/// Reusable and thread-safe formatter.
public final class Formatter {
    public sealed interface Result {}

    public record Success(String formatted) implements Result {}

    public record SyntaxError(List<Object> errors) implements Result {}

    public record Failure(Throwable error) implements Result {}

    private final int javaRelease;
    private final boolean enablePreview;

    public Formatter() {
        this(AST.getJLSLatest(), false);
    }

    public Formatter(int javaRelease) {
        this(javaRelease, false);
    }

    public Formatter(int javaRelease, boolean enablePreview) {
        this.javaRelease = javaRelease;
        this.enablePreview = enablePreview;
    }

    public Result format(String source) {
        try {
            var parser = ASTParser.newParser(javaRelease);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            var options = JavaCore.getOptions();
            options.put(JavaCore.COMPILER_SOURCE, String.valueOf(javaRelease));
            options.put(
                    JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES,
                    enablePreview ? JavaCore.ENABLED : JavaCore.DISABLED
            );
            parser.setCompilerOptions(options);

            parser.setSource(source.toCharArray());
            var cu = (CompilationUnit) parser.createAST(null);

            // In case of compilation errors we stop trying immediately. We would delete problematic source ranges.
            var problems = cu.getProblems();
            if (problems.length > 0) return formatProblems(problems);

            var a2d = new AstToDoc(source);
            cu.accept(a2d);
            var printer = new DocPrinter(100);
            var formatted = printer.print(a2d.result());
            return new Success(formatted);
        } catch (RuntimeException e) {
            return new Failure(e);
        }
    }

    private Result formatProblems(IProblem[] problems) {
        //        var errors = Arrays.stream(problems).filter(p -> p.isError()).toList();
        //        if (!errors.isEmpty()) {
        //            throw new RuntimeException(
        //                    "Syntax error: " + errors.getFirst().getMessage() + " (line " + errors.getFirst()
        //                            .getSourceLineNumber() + ")"
        //            );
        //        }
        return new SyntaxError(List.of());
    }

}
