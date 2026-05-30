package dev.javafmt;

import dev.javafmt.api.Formatter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Runs the formatter against every .java file in an external corpus and asserts the formatted
/// output reparses to a structurally equivalent AST. Tagged "corpus" so the default test task
/// skips it; invoke via `./gradlew :core:corpusTest`.
@Tag("corpus")
public class CorpusTest {

    private static final Path CORPUS_DIR = Path.of(
            System.getProperty("javafmt.corpus.dir", "build/corpus"));

    private static final Set<String> IGNORE = loadIgnore("/corpus-ignore.txt");

    /// Files with a known comment-loss bug in some expression/modifier context. The
    /// comment-preservation check is skipped for these (AST-equivalence and idempotence
    /// still run); a file NOT listed that drops a comment fails the build. This is the
    /// backlog — shrink it by fixing a context and deleting the files it covers.
    private static final Set<String> COMMENT_LOSS_IGNORE = loadIgnore("/comment-loss-ignore.txt");

    static Stream<Path> corpusFiles() throws IOException {
        if (!Files.isDirectory(CORPUS_DIR)) {
            throw new IllegalStateException(
                    "corpus not found at " + CORPUS_DIR + " — run :core:fetchCorpus");
        }
        try (var walk = Files.walk(CORPUS_DIR)) {
            return walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isUnderTestFixtures(p))
                    .filter(p -> !IGNORE.contains(relativeId(p)))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    void roundTripPreservesAst(Path file) throws IOException {
        var source = Files.readString(file);

        var original = parse(source);
        // Skip files the JDT parser itself can't handle at our compliance level —
        // module-info, package-info with weird annotations, or newer syntax
        Assumptions.assumeTrue(original.getProblems().length == 0,
                "skipping unparseable source");

        var result = Formatter.defaults().format(source);
        switch (result) {
            case Formatter.Result.Success(var formatted) -> {
                var reparsed = parse(formatted);
                assertTrue(reparsed.getProblems().length == 0,
                        () -> "formatter produced unparseable output: "
                                + Arrays.toString(reparsed.getProblems()));
                try {
                    AstEquivalence.assertEquivalent(original, reparsed);
                } catch (AstEquivalence.Mismatch m) {
                    fail("AST diverged after formatting: " + m.getMessage());
                }
                if (!COMMENT_LOSS_IGNORE.contains(relativeId(file))) {
                    CommentPreservation.assertPreserved(file.toString(), source, formatted, original, reparsed);
                }
            }
            case Formatter.Result.SyntaxError(var problems) -> fail("formatter rejected valid input: " + problems);
            case Formatter.Result.Failure(var error) -> fail("formatter threw: " + error);
        }
        // Idempotence: a second format must change nothing.
        var first = ((Formatter.Result.Success) result).formatted();
        var second = Formatter.defaults().format(first);
        assertTrue(second instanceof Formatter.Result.Success, "second pass failed: " + second);
        assertEquals(first, ((Formatter.Result.Success) second).formatted(), "formatter not idempotent");
    }

    private static CompilationUnit parse(String source) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static boolean isUnderTestFixtures(Path p) {
        var s = p.toString().replace('\\', '/');
        // JUnit's own intentionally-malformed fixtures live under these dirs; skip them.
        return s.contains("/src/test/resources/")
                || s.contains("/jqwik/")
                || s.endsWith("package-info.java")
                || s.endsWith("module-info.java");
    }

    private static String relativeId(Path p) {
        return CORPUS_DIR.relativize(p).toString().replace('\\', '/');
    }

    private static Set<String> loadIgnore(String resource) {
        var ignore = new HashSet<String>();
        try (var in = CorpusTest.class.getResourceAsStream(resource)) {
            if (in == null) return ignore;
            for (var raw : new String(in.readAllBytes()).split("\n")) {
                var line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                ignore.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ignore;
    }
}
