package dev.javafmt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CommentLedgerTest {

    /// The comment-loss backlog must stay empty: a real drop is now a STRICT-ledger build
    /// failure to be fixed, never an entry to record. This tripwire fails if anyone adds one.
    @Test
    void commentLossBacklogIsEmpty() throws IOException {
        try (var in = CommentLedgerTest.class.getResourceAsStream("/comment-loss-ignore.txt")) {
            assertTrue(in != null, "comment-loss-ignore.txt missing");
            for (var raw : new String(in.readAllBytes()).split("\n")) {
                var line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                fail("comment-loss-ignore.txt must be empty — fix the drop, do not list it: " + line);
            }
        }
    }

    /// Each of these places a comment in a deep expression/type position that no
    /// construct-specific handler emits. The generic mop-up must claim it with NO production
    /// fallback (appendOrphansAtCu = false, as in STRICT), so the ledger reports zero drops.
    @ParameterizedTest
    @ValueSource(strings = {
        "class A { int x = ( /*c*/ 1 ); }",
        "class A { int x = (int) /*c*/ y; }",
        "class A { int x = a[ /*c*/ 0]; }",
        "class A { boolean b = x /*c*/ instanceof String; }",
        "class A { void m() { return /*c*/ 1; } }",
        "class A { void m() { throw /*c*/ new E(); } }",
        "class A { void m() { assert x /*c*/ : \"m\"; } }",
        "class A<T extends /*c*/ Object> {}",
        "class A { void m(int... /*c*/ x) {} }",
        "@interface A { String value() default /*c*/ \"x\"; }",
    })
    void mopUpClosesExpressionGaps(String source) {
        var cu = parse(source);
        var map = CommentMap.build(cu, source);
        var a2d = new AstToDoc(source, map); // fallback OFF — the mop-up alone must cover it
        cu.accept(a2d);
        var dropped = CommentLedger.dropped(map.attachedComments(), a2d.emittedComments());
        if (!dropped.isEmpty()) fail(CommentLedger.describe(dropped, source));
    }

    private static CompilationUnit parse(String source) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }
}
