package dev.javafmt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.List;

/// Reusable and thread-safe formatter.
public final class Formatter {
    public sealed interface Result {}

    public record Success(String formatted) implements Result {}

    public record SyntaxError(List<Problem> problems) implements Result {}

    public record Failure(Throwable error) implements Result {}

    public record Problem(int line, int column, String message) {}

    private final int javaRelease;
    private final boolean enablePreview;
    private final int lineLength;

    public Formatter() {
        this(AST.getJLSLatest(), false, 100);
    }

    public Formatter(int javaRelease) {
        this(javaRelease, false, 100);
    }

    public Formatter(int javaRelease, boolean enablePreview) {
        this(javaRelease, enablePreview, 100);
    }

    public Formatter(int javaRelease, boolean enablePreview, int lineLength) {
        this.javaRelease = javaRelease;
        this.enablePreview = enablePreview;
        this.lineLength = lineLength;
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

            var problems = cu.getProblems();
            if (problems.length > 0) {
                var errors = collectErrors(source, problems);
                if (!errors.isEmpty()) return new SyntaxError(errors);
            }

            var comments = CommentMap.build(cu, source);
            var a2d = new AstToDoc(source, comments);
            cu.accept(a2d);
            var printer = new DocPrinter(lineLength);
            var formatted = printer.print(a2d.result());
            return new Success(formatted);
        } catch (RuntimeException e) {
            return new Failure(e);
        }
    }

    private static List<Problem> collectErrors(String source, IProblem[] problems) {
        var errors = new ArrayList<Problem>();
        for (var p : problems) {
            if (!p.isError()) continue;
            errors.add(new Problem(p.getSourceLineNumber(), columnOf(source, p.getSourceStart()), p.getMessage()));
        }
        return errors;
    }

    private static int columnOf(String source, int sourceStart) {
        if (sourceStart < 0) return 1;
        int start = sourceStart;
        if (start > source.length()) start = source.length();
        int lineStart = start;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') lineStart--;
        return start - lineStart + 1;
    }

}
