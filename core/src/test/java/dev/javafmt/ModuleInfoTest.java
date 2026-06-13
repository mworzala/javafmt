package dev.javafmt;

import dev.javafmt.api.Formatter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Module-info coverage that the golden `.test` cases under `testcases/module/` don't reach:
/// AST equivalence across directive reordering, the strict emission ledger over a
/// directive-internal comment, and the file-name gating / parser-state handling in
/// {@link FormatterImpl}.
class ModuleInfoTest {

    /// Parse + format a module-info source through the same pipeline FileTest uses, with a
    /// strict emission ledger so a dropped comment fails here too (the main `test` task runs the
    /// formatter's own ledger in WARN mode, which would otherwise paper over a drop).
    private static String format(String source) {
        var ast = parse(source);
        var commentMap = CommentMap.build(ast, source);
        var a2d = new AstToDoc(source, commentMap);
        ast.accept(a2d);
        var dropped = CommentLedger.dropped(commentMap.attachedComments(), a2d.emittedComments());
        if (!dropped.isEmpty()) throw new AssertionError(CommentLedger.describe(dropped, source));
        var out = new DocPrinter(100).print(a2d.result());
        return Arrays.stream(out.split("\n"))
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static CompilationUnit parse(String source) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);
        parser.setUnitName("module-info.java");
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    @Test
    void reordersDirectivesPreservingAst() {
        var input = """
                module com.foo.bar {
                    uses com.foo.Spi;
                    requires transitive java.sql;
                    exports com.foo.api to com.bar, com.baz;
                    provides com.foo.Spi with com.foo.Impl;
                    opens com.foo.impl;
                    requires java.base;
                }
                """;
        var output = format(input);

        // The directives are regrouped (exports/opens/requires/uses/provides); reordering must
        // not change program meaning, so the reparsed output stays AST-equivalent to the input.
        AstEquivalence.assertEquivalent(parse(input), parse(output));
        CommentPreservation.assertPreserved("module reorder", input, output, parse(input), parse(output));
        assertEquals(output, format(output), "module formatting must be idempotent");

        assertTrue(output.indexOf("exports") < output.indexOf("opens"), "exports before opens");
        assertTrue(output.indexOf("opens") < output.indexOf("requires"), "opens before requires");
        assertTrue(output.indexOf("requires") < output.indexOf("uses"), "requires before uses");
        assertTrue(output.indexOf("uses") < output.indexOf("provides"), "uses before provides");
    }

    @Test
    void directiveInternalCommentIsNeverDropped() {
        // A comment that attaches to a directive's sub-node is reached by no leading/trailing/
        // dangling emitter and by no postVisit mop-up; the module-level orphan sweep must catch
        // it. format() runs a strict ledger, so a drop throws.
        var input = """
                module com.foo {
                    exports com.foo.api /* note */ to com.bar;
                }
                """;
        var output = format(input);
        CommentPreservation.assertPreserved("internal comment", input, output, parse(input), parse(output));
        assertTrue(output.contains("/* note */"), "directive-internal comment preserved");
        assertEquals(output, format(output), "must stay idempotent after the sweep");
    }

    @Test
    void moduleSyntaxIsGatedOnTheFileName() {
        var formatter = new FormatterImpl(AST.getJLSLatest(), false, 100);
        var input = "module com.foo { requires java.base; }\n";

        // Module grammar is a syntax error unless the parser is told the unit is module-info.java.
        assertInstanceOf(Formatter.Result.SyntaxError.class, formatter.format(input, null));
        assertInstanceOf(Formatter.Result.SyntaxError.class, formatter.format(input)); // delegates to null
        // A bare name or a path whose final segment is module-info.java is recognized.
        assertInstanceOf(Formatter.Result.Success.class, formatter.format(input, "module-info.java"));
        assertInstanceOf(Formatter.Result.Success.class, formatter.format(input, "src/main/java/module-info.java"));
        assertInstanceOf(Formatter.Result.Success.class, formatter.format(input, "C:\\proj\\module-info.java"));
    }

    @Test
    void moduleParseDoesNotLeakIntoTheNextFile() {
        // The parser is cached per thread; the unit name from a module parse must not carry over
        // to a following ordinary file. Output after a module parse must match a fresh formatter.
        var formatter = new FormatterImpl(AST.getJLSLatest(), false, 100);
        formatter.format("module com.foo { requires java.base; }\n", "module-info.java");

        var afterModule = assertInstanceOf(Formatter.Result.Success.class,
                formatter.format("class A {}\n", "A.java")).formatted();
        var fresh = assertInstanceOf(Formatter.Result.Success.class,
                new FormatterImpl(AST.getJLSLatest(), false, 100).format("class A {}\n", "A.java")).formatted();
        assertEquals(fresh, afterModule);
    }
}
