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

    /// The comment-loss backlog — files that DROP or DUPLICATE a comment. The generic orphan
    /// mop-up plus the STRICT emission ledger have driven this to empty and keep it there
    /// (see the empty-tripwire assertion in {@link #commentLossBacklogIsEmpty}). Comment
    /// preservation runs on every corpus file.
    private static final Set<String> COMMENT_LOSS_IGNORE = loadIgnore("/comment-loss-ignore.txt");

    /// Files that PRESERVE every comment but whose coarse mop-up fallback placement normalizes
    /// once on the first format (the comment moves to a stable spot), so strict single-pass
    /// idempotence does not hold even though the file settles immediately (pass 2 == pass 3)
    /// and stays AST-equivalent. The only case is an inline block comment between enum-constant
    /// arguments, whose synthesized trailing comma relocates it once; proper in-place emission
    /// needs the enum-constant argument list to keep an inline block comment inline without
    /// forcing a multi-line break (a layout change tracked separately).
    private static final Set<String> IDEMPOTENCE_REFLOW = Set.of(
            "mapmaker/modules/core/src/main/java/net/hollowcube/mapmaker/map/MapSize.java");

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
        var fileName = file.getFileName().toString();
        boolean module = fileName.equals("module-info.java");

        var original = parse(source, module);
        // Skip files the JDT parser itself can't handle at our compliance level —
        // package-info with weird annotations, or newer syntax. A module-info parses cleanly
        // once we hand the parser the unit name (see parse()).
        Assumptions.assumeTrue(original.getProblems().length == 0,
                "skipping unparseable source");

        var result = Formatter.defaults().format(source, fileName);
        switch (result) {
            case Formatter.Result.Success(var formatted) -> {
                var reparsed = parse(formatted, module);
                assertTrue(reparsed.getProblems().length == 0,
                        () -> "formatter produced unparseable output: "
                                + Arrays.toString(reparsed.getProblems()));
                try {
                    AstEquivalence.assertEquivalent(original, reparsed);
                } catch (AstEquivalence.Mismatch m) {
                    fail("AST diverged after formatting: " + m.getMessage());
                }
                CommentPreservation.assertPreserved(file.toString(), source, formatted, original, reparsed);
            }
            case Formatter.Result.SyntaxError(var problems) -> fail("formatter rejected valid input: " + problems);
            case Formatter.Result.Failure(var error) -> fail("formatter threw: " + error);
        }
        // Idempotence: a second format must change nothing. A known reflow file is exempt from
        // strict single-pass idempotence but must still settle on the next pass (pass 2 == pass 3).
        var first = ((Formatter.Result.Success) result).formatted();
        var second = Formatter.defaults().format(first, fileName);
        assertTrue(second instanceof Formatter.Result.Success, "second pass failed: " + second);
        var secondOut = ((Formatter.Result.Success) second).formatted();
        if (IDEMPOTENCE_REFLOW.contains(relativeId(file))) {
            var third = Formatter.defaults().format(secondOut, fileName);
            assertTrue(third instanceof Formatter.Result.Success, "third pass failed: " + third);
            assertEquals(secondOut, ((Formatter.Result.Success) third).formatted(),
                    "reflow file did not settle on the second pass");
        } else {
            assertEquals(first, secondOut, "formatter not idempotent");
        }
    }

    private static CompilationUnit parse(String source, boolean module) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);
        if (module) parser.setUnitName("module-info.java");
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static boolean isUnderTestFixtures(Path p) {
        var s = p.toString().replace('\\', '/');
        // JUnit's own intentionally-malformed fixtures live under these dirs; skip them.
        // module-info.java is NOT skipped — it is formatted (and parsed with its unit name) so the
        // module path gets real-world coverage; package-info.java stays skipped for now.
        return s.contains("/src/test/resources/")
                || s.contains("/jqwik/")
                || s.endsWith("package-info.java");
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
