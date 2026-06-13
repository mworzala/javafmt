package dev.javafmt;

import dev.javafmt.api.Formatter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jspecify.annotations.Nullable;

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
    private final CommentLedger.Mode ledgerMode = CommentLedger.Mode.fromProperty();
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

    /// Whether {@code fileName}'s final path segment is {@code module-info.java}. Accepts a
    /// bare name or a `/`- or `\`-separated path; {@code null} (e.g. stdin) is not a module.
    private static boolean isModuleInfo(@Nullable String fileName) {
        if (fileName == null) return false;
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        var base = slash < 0 ? fileName : fileName.substring(slash + 1);
        return base.equals("module-info.java");
    }

    @Override
    public Result format(String source, @Nullable String fileName) {
        try {
            var cu = parse(source, fileName);

            var problems = cu.getProblems();
            if (problems.length > 0) {
                var errors = collectErrors(source, problems);
                if (!errors.isEmpty()) return new Result.SyntaxError(errors);
            }

            var comments = CommentMap.build(cu, source);
            // WARN (the production default) force-appends any otherwise-unemitted comment so a
            // drop is impossible; STRICT instead leaves it unemitted and throws below, so CI
            // surfaces the gap; OFF does neither (legacy).
            var a2d = new AstToDoc(source, comments, ledgerMode == CommentLedger.Mode.WARN);
            cu.accept(a2d);
            if (ledgerMode == CommentLedger.Mode.STRICT) {
                var dropped = CommentLedger.dropped(comments.attachedComments(), a2d.emittedComments());
                if (!dropped.isEmpty()) {
                    throw new IllegalStateException(
                            "javafmt dropped comments — " + CommentLedger.describe(dropped, source));
                }
            }
            var printer = new DocPrinter(lineLength);
            var formatted = printer.print(a2d.result());

            // `// @formatter:off` / `on`: the whole file is already formatted; splice the original
            // source back over any region the user opted out of (no-op when there are none).
            formatted = FormatterDirectives.apply(source, cu, formatted, s -> parse(s, fileName));

            return new Result.Success(formatted);
        } catch (RuntimeException e) {
            return new Result.Failure(e);
        }
    }

    private CompilationUnit parse(String source, @Nullable String fileName) {
        var parser = parserCache.get();
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(compilerOptions);
        // JDT only switches its grammar into module mode when the unit is named
        // module-info.java; without it, `module`/`requires`/`exports`/... are parsed as
        // ordinary identifiers and the parse fails. Set the name every call (clearing it to
        // null otherwise) so a module parse can't leak into the next file on this cached,
        // per-thread parser.
        parser.setUnitName(isModuleInfo(fileName) ? "module-info.java" : null);
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
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
