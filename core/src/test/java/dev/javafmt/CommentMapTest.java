package dev.javafmt;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentMapTest {

    private record Fixture(CompilationUnit cu, CommentMap map, String source) {
        TypeDeclaration topType() {
            return (TypeDeclaration) cu.types().get(0);
        }
    }

    private static Fixture parse(String source) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        var cu = (CompilationUnit) parser.createAST(null);
        return new Fixture(cu, CommentMap.build(cu, source), source);
    }

    private static String text(Fixture f, Comment c) {
        return f.source.substring(c.getStartPosition(), c.getStartPosition() + c.getLength());
    }

    @Test
    void trailingComment_attachesToPrevOnSameLine() {
        var f = parse("""
                class A {
                    int x = 1; // trailing
                }
                """);
        FieldDeclaration field = (FieldDeclaration) f.topType().bodyDeclarations().get(0);
        List<Comment> trailing = f.map.trailing(field);
        assertEquals(1, trailing.size());
        assertEquals("// trailing", text(f, trailing.get(0)));
        assertTrue(f.map.leading(field).isEmpty());
    }

    @Test
    void leadingComment_attachesToNextWhenOnPriorLine() {
        var f = parse("""
                class A {
                    // leading
                    int x = 1;
                }
                """);
        FieldDeclaration field = (FieldDeclaration) f.topType().bodyDeclarations().get(0);
        List<Comment> leading = f.map.leading(field);
        assertEquals(1, leading.size());
        assertEquals("// leading", text(f, leading.get(0)));
    }

    @Test
    void betweenSiblings_defaultsToLeadingOfNext() {
        var f = parse("""
                class A {
                    int x = 1;
                    // between
                    int y = 2;
                }
                """);
        FieldDeclaration second = (FieldDeclaration) f.topType().bodyDeclarations().get(1);
        assertEquals(1, f.map.leading(second).size());
        assertEquals("// between", text(f, f.map.leading(second).get(0)));
    }

    @Test
    void emptyBlock_commentAttachesAsDangling() {
        var f = parse("""
                class A {
                    void m() {
                        // todo
                    }
                }
                """);
        MethodDeclaration method = (MethodDeclaration) f.topType().bodyDeclarations().get(0);
        Block body = method.getBody();
        List<Comment> dangling = f.map.dangling(body);
        assertEquals(1, dangling.size());
        assertEquals("// todo", text(f, dangling.get(0)));
    }

    @Test
    void markdownJavadoc_isNotAttached() {
        var f = parse("""
                class A {
                    /// doc line 1
                    /// doc line 2
                    int x = 1;
                }
                """);
        FieldDeclaration field = (FieldDeclaration) f.topType().bodyDeclarations().get(0);
        assertTrue(f.map.leading(field).isEmpty(),
                "/// lines should be consumed as Javadoc, not attached as comments");
        assertTrue(f.map.trailing(field).isEmpty());
    }

    @Test
    void multipleTrailingComments_allAttachToPrev() {
        // Block-comment-then-line-comment trailing the same statement.
        var f = parse("""
                class A {
                    int x = 1; /* one */ // two
                }
                """);
        FieldDeclaration field = (FieldDeclaration) f.topType().bodyDeclarations().get(0);
        List<Comment> trailing = f.map.trailing(field);
        assertEquals(2, trailing.size());
        assertEquals("/* one */", text(f, trailing.get(0)));
        assertEquals("// two", text(f, trailing.get(1)));
    }

    @Test
    void multipleLeadingComments_allAttachToNext() {
        var f = parse("""
                class A {
                    // first
                    // second
                    int x = 1;
                }
                """);
        FieldDeclaration field = (FieldDeclaration) f.topType().bodyDeclarations().get(0);
        List<Comment> leading = f.map.leading(field);
        assertEquals(2, leading.size());
        assertEquals("// first", text(f, leading.get(0)));
        assertEquals("// second", text(f, leading.get(1)));
    }

    @Test
    void commentBetweenTopLevelTypes_attachesAsLeadingOfSecond() {
        var f = parse("""
                class A {}
                // between types
                class B {}
                """);
        TypeDeclaration second = (TypeDeclaration) f.cu.types().get(1);
        assertEquals(1, f.map.leading(second).size());
        assertEquals("// between types", text(f, f.map.leading(second).get(0)));
    }

    @Test
    void commentAfterLastType_attachesAsDanglingOfCompilationUnit() {
        var f = parse("""
                class A {}
                // tail
                """);
        List<Comment> dangling = f.map.dangling(f.cu);
        assertEquals(1, dangling.size());
        assertEquals("// tail", text(f, dangling.get(0)));
    }

    @Test
    void leadingThenTrailing_onSameDeclaration() {
        var f = parse("""
                class A {
                    // before
                    int x = 1; // after
                }
                """);
        FieldDeclaration field = (FieldDeclaration) f.topType().bodyDeclarations().get(0);
        assertEquals(1, f.map.leading(field).size());
        assertEquals(1, f.map.trailing(field).size());
        assertEquals("// before", text(f, f.map.leading(field).get(0)));
        assertEquals("// after", text(f, f.map.trailing(field).get(0)));
    }

    @Test
    void blockComment_betweenSiblings_isLeadingOfNext() {
        var f = parse("""
                class A {
                    int x = 1;
                    /* block between */
                    int y = 2;
                }
                """);
        FieldDeclaration second = (FieldDeclaration) f.topType().bodyDeclarations().get(1);
        assertEquals(1, f.map.leading(second).size());
        assertEquals("/* block between */", text(f, f.map.leading(second).get(0)));
    }

    @Test
    void commentInsideStatement_doesNotAttachToBlock() {
        // A comment buried inside an expression isn't a block-level comment;
        // the smallest enclosing node is whatever contains it.
        var f = parse("""
                class A {
                    void m() {
                        foo(/* inline */ 1);
                    }
                }
                """);
        MethodDeclaration method = (MethodDeclaration) f.topType().bodyDeclarations().get(0);
        Block body = method.getBody();
        // The block shouldn't see this comment as one of its own attachments.
        assertTrue(f.map.leading(body).isEmpty());
        assertTrue(f.map.trailing(body).isEmpty());
        assertTrue(f.map.dangling(body).isEmpty());
    }
}
