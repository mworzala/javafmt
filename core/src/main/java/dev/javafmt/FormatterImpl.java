package dev.javafmt;

import dev.javafmt.api.Formatter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Reusable, thread-safe implementation of {@link Formatter}.
///
/// Not part of the published API surface. Library users construct formatters via
/// {@link Formatter#defaults()} or {@link Formatter#create(Formatter.Config)}; this
/// class is reached only through the ServiceLoader-discovered {@link FormatterProviderImpl}.
public final class FormatterImpl implements Formatter {

    private final int javaRelease;
    private final boolean enablePreview;
    private final int lineLength;
    private final Map<String, String> compilerOptions;
    // ASTParser isn't safe to share across threads, but reusing one per thread avoids
    // the non-trivial newParser cost on every file. Per-call setup happens in format()
    // because createAST() invokes JDT's private initializeDefaults() afterwards, which
    // resets compilerOptions back to JavaCore.getOptions() (compliance 1.8). Without
    // re-applying, every parse after the first would reject any post-Java-8 syntax.
    private final ThreadLocal<ASTParser> parserCache;

    public FormatterImpl(int javaRelease, boolean enablePreview, int lineLength) {
        this.javaRelease = javaRelease;
        this.enablePreview = enablePreview;
        this.lineLength = lineLength;
        this.parserCache = ThreadLocal.withInitial(() -> ASTParser.newParser(javaRelease));
        var options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(versionString(javaRelease), options);
        options.put(
                JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES,
                enablePreview ? JavaCore.ENABLED : JavaCore.DISABLED
        );
        this.compilerOptions = options;
    }

    private static String versionString(int release) {
        return release <= 8 ? "1." + release : String.valueOf(release);
    }

    @Override
    public Result format(String source) {
        try {
            var parser = parserCache.get();
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setCompilerOptions(compilerOptions);
            parser.setSource(source.toCharArray());
            var cu = (CompilationUnit) parser.createAST(null);

            var problems = cu.getProblems();
            if (problems.length > 0) {
                var errors = collectErrors(source, problems);
                if (!errors.isEmpty()) return new Result.SyntaxError(errors);
            }

            var comments = CommentMap.build(cu, source);
            var a2d = new AstToDoc(source, comments);
            cu.accept(a2d);
            var printer = new DocPrinter(lineLength);
            var formatted = printer.print(a2d.result());
            return new Result.Success(formatted);
        } catch (RuntimeException e) {
            return new Result.Failure(e);
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
