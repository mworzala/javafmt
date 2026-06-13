package dev.javafmt;

import dev.javafmt.api.Formatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// End-to-end tests for `// @formatter:off` / `on` handling through the real {@link Formatter}
/// pipeline. These complement the golden `.test` cases (which normalize trailing whitespace) by
/// asserting byte-exact preservation, robustness against false markers, and idempotence.
class FormatterDirectivesTest {

    private static final Formatter FMT = Formatter.create(Formatter.Config.defaults().withRelease(25));

    private static String fmt(String source) {
        var result = FMT.format(source);
        if (result instanceof Formatter.Result.Success s) return s.formatted();
        return fail("expected Success, got " + result);
    }

    @Test
    void disabledRegionIsLeftByteForByte() {
        // Trailing whitespace and odd internal spacing inside the region must survive exactly —
        // something the whitespace-normalizing golden harness cannot assert. Built without a text
        // block because text blocks strip trailing whitespace from each line.
        var src = "class A {\n"
                + "    void m() {\n"
                + "        int x=1;\n"
                + "        // @formatter:off\n"
                + "        int     y =  2 ;   \n"  // three trailing spaces, must survive verbatim
                + "        // @formatter:on\n"
                + "        int z=1;\n"
                + "    }\n"
                + "}\n";
        var out = fmt(src);
        assertTrue(out.contains("        int     y =  2 ;   \n"),
                "verbatim region (with trailing spaces) not preserved:\n" + out);
        // Code outside the region is still formatted.
        assertTrue(out.contains("        int x = 1;\n"), out);
        assertTrue(out.contains("        int z = 1;\n"), out);
    }

    @Test
    void wholeFileOffReturnsInputExactly() {
        // A leading `// @formatter:off` with no matching `on` disables to EOF, so the file is
        // returned byte-identical — including its (deliberately mangled) original layout.
        var src = "// @formatter:off\nclass    A   {\n  int   x=1 ;\n}\n";
        assertEquals(src, fmt(src));
    }

    @Test
    void offWithoutOnDisablesToEndOfFile() {
        var src = """
                class A {
                    int a=1;
                    // @formatter:off
                    int   b = 2 ;
                    void   m(){}
                }
                """;
        var out = fmt(src);
        assertTrue(out.contains("    int a = 1;\n"), out);          // before: formatted
        assertTrue(out.contains("    int   b = 2 ;\n"), out);       // after: verbatim
        assertTrue(out.contains("    void   m(){}\n"), out);        // after: verbatim
    }

    @Test
    void secondOffWhileDisabledIsIgnored() {
        // off … off … on  ->  a single region closed by the first on; the inner off rides along
        // as part of the verbatim text.
        var src = """
                class A {
                    // @formatter:off
                    int  a=1;
                    // @formatter:off
                    int  b=2;
                    // @formatter:on
                    int c=3;
                }
                """;
        var out = fmt(src);
        assertTrue(out.contains("    int  a=1;\n"), out);
        assertTrue(out.contains("    int  b=2;\n"), out);
        assertTrue(out.contains("    int c = 3;\n"), out);          // re-enabled after the on
    }

    @Test
    void strayOnIsIgnored() {
        var src = """
                class A {
                    // @formatter:on
                    int  a=1;
                }
                """;
        var out = fmt(src);
        assertTrue(out.contains("    int a = 1;\n"), out);          // formatted, no region opened
        assertTrue(out.contains("// @formatter:on"), out);         // marker kept as a normal comment
    }

    @Test
    void trailingMarkerIsNotADirective() {
        var src = """
                class A {
                    int a=1; // @formatter:off
                    int  b=2;
                }
                """;
        var out = fmt(src);
        assertTrue(out.contains("int a = 1; // @formatter:off"), out);
        assertTrue(out.contains("    int b = 2;\n"), out);         // still formatted
    }

    @Test
    void markerInStringLiteralIsNotADirective() {
        // The detector reads parsed LineComment nodes, not raw text, so a marker-looking string
        // literal must not switch formatting off.
        var src = """
                class A {
                    String s="// @formatter:off";
                    int  x=1;
                }
                """;
        var out = fmt(src);
        assertTrue(out.contains("    int x = 1;\n"),
                "string-literal marker wrongly disabled formatting:\n" + out);
    }

    @Test
    void markerInBlockCommentIsNotADirective() {
        var src = """
                class A {
                    /* @formatter:off */
                    int  x=1;
                }
                """;
        var out = fmt(src);
        assertTrue(out.contains("    int x = 1;\n"), out);
    }

    @Test
    void idempotentAcrossRegions() {
        var src = """
                class A {
                    int x=1;
                    // @formatter:off
                    int     y=2 ;
                    // @formatter:on
                    int z=3;
                }
                """;
        var once = fmt(src);
        assertEquals(once, fmt(once), "formatter not idempotent with a disabled region");
    }

    @Test
    void disabledRegionMayBeNonIdempotentSourceYetStable() {
        // The region contains code that is NOT in javafmt style; it must stay un-touched on every
        // pass, never converging toward formatted output.
        var src = """
                class A {
                    // @formatter:off
                    int q   =   1+2*3;
                    // @formatter:on
                }
                """;
        var once = fmt(src);
        assertTrue(once.contains("int q   =   1+2*3;"), once);
        assertEquals(once, fmt(once));
    }

    @Test
    void noDirectivesIsUnaffected() {
        var src = """
                class A {
                    int x=1;
                }
                """;
        var out = fmt(src);
        assertEquals("class A {\n    int x = 1;\n}\n", out);
        assertFalse(out.contains("@formatter"), out);
    }

    @Test
    void markerRelocatedAcrossStructuralTokenFallsBackSafely() {
        // The `@formatter:on` sits between the implements list and the `{`; the formatter relocates
        // that comment across the brace, so whole-line splicing would misalign. The safety net must
        // catch this and fall back to plain formatted output rather than emit corrupt source.
        var src = """
                interface X {}
                interface Y {}
                // @formatter:off
                class C implements
                        X,
                            Y
                // @formatter:on
                {
                    int a=1;
                }
                """;
        var out = fmt(src);
        // Output is valid Java and stable...
        assertInstanceOf(Formatter.Result.Success.class, FMT.format(out));
        assertEquals(out, fmt(out), "fallback output must be idempotent");
        // ...and the directive was dropped, so the body is formatted normally.
        assertTrue(out.contains("int a = 1;"), out);
        assertTrue(out.contains("implements") && out.contains("X") && out.contains("Y"), out);
    }

    @Test
    void disabledRegionPreservesAstEquivalence() {
        var src = """
                class A {
                    int x=1;
                    // @formatter:off
                    int     y =  2 ;
                    // @formatter:on
                    int z=3;
                }
                """;
        var out = fmt(src);
        assertInstanceOf(Formatter.Result.Success.class, FMT.format(out));
    }
}
