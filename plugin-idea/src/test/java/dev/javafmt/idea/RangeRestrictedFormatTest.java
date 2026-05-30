package dev.javafmt.idea;

import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class RangeRestrictedFormatTest extends BasePlatformTestCase {

    // a() and b() each need expanding; mid() is already formatted and is left unchanged,
    // so it anchors the diff into two separate hunks.
    private static final String ORIGINAL = join(
            "class A {",
            "  void a() {x();}",
            "  void mid() {}",
            "  void b() {y();}",
            "}");
    private static final String FORMATTED = join(
            "class A {",
            "  void a() {",
            "    x();",
            "  }",
            "  void mid() {}",
            "  void b() {",
            "    y();",
            "  }",
            "}");

    public void testSelectionRestrictsChangesToThatMethod() {
        var ranges = List.of(lineRangeContaining(ORIGINAL, "void a"));

        var result = RangeRestrictedFormat.restrict(ORIGINAL, FORMATTED, ranges);

        // a() is reformatted; b() is left exactly as it was in the original.
        var expected = join(
                "class A {",
                "  void a() {",
                "    x();",
                "  }",
                "  void mid() {}",
                "  void b() {y();}",
                "}");
        assertEquals(expected, result);
        assertTrue("b() must be byte-identical to the original", result.contains("  void b() {y();}"));
    }

    public void testSelectionInSecondMethodLeavesFirstUntouched() {
        var ranges = List.of(lineRangeContaining(ORIGINAL, "void b"));

        var result = RangeRestrictedFormat.restrict(ORIGINAL, FORMATTED, ranges);

        var expected = join(
                "class A {",
                "  void a() {x();}",
                "  void mid() {}",
                "  void b() {",
                "    y();",
                "  }",
                "}");
        assertEquals(expected, result);
    }

    public void testEmptyRangesAppliesFullFormat() {
        var result = RangeRestrictedFormat.restrict(ORIGINAL, FORMATTED, List.of());
        assertEquals(FORMATTED, result);
    }

    public void testWholeDocumentRangeAppliesFullFormat() {
        var ranges = List.of(new TextRange(0, ORIGINAL.length()));
        var result = RangeRestrictedFormat.restrict(ORIGINAL, FORMATTED, ranges);
        assertEquals(FORMATTED, result);
    }

    public void testIdenticalInputReturnsOriginal() {
        var ranges = List.of(new TextRange(0, 5));
        var result = RangeRestrictedFormat.restrict(ORIGINAL, ORIGINAL, ranges);
        assertEquals(ORIGINAL, result);
    }

    public void testCrlfDocumentPreservesSeparators() {
        var original = joinCrlf(
                "class A {",
                "  void a() {x();}",
                "  void mid() {}",
                "  void b() {y();}",
                "}");
        var formatted = joinCrlf(
                "class A {",
                "  void a() {",
                "    x();",
                "  }",
                "  void mid() {}",
                "  void b() {",
                "    y();",
                "  }",
                "}");
        var ranges = List.of(lineRangeContaining(original, "void a"));

        var result = RangeRestrictedFormat.restrict(original, formatted, ranges);

        var expected = joinCrlf(
                "class A {",
                "  void a() {",
                "    x();",
                "  }",
                "  void mid() {}",
                "  void b() {y();}",
                "}");
        assertEquals(expected, result);
        // The applied hunk must keep CRLF, not collapse to LF.
        assertFalse("result must not contain a bare LF", result.replace("\r\n", "").contains("\n"));
    }

    public void testInsertionInsideRangeIsApplied() {
        // Formatter inserts a blank line between the two members.
        var original = join(
                "class A {",
                "  int a;",
                "  int b;",
                "}");
        var formatted = join(
                "class A {",
                "  int a;",
                "",
                "  int b;",
                "}");
        // Select across the gap (from 'int a' through 'int b') so the insertion point is covered.
        var start = original.indexOf("int a");
        var end = original.indexOf("int b") + "int b".length();
        var ranges = List.of(new TextRange(start, end));

        var result = RangeRestrictedFormat.restrict(original, formatted, ranges);
        assertEquals(formatted, result);
    }

    public void testInsertionOutsideRangeIsNotApplied() {
        var original = join(
                "class A {",
                "  int a;",
                "  int b;",
                "}");
        var formatted = join(
                "class A {",
                "  int a;",
                "",
                "  int b;",
                "}");
        // Select only the class header line — nowhere near the inserted blank line.
        var ranges = List.of(lineRangeContaining(original, "class A"));

        var result = RangeRestrictedFormat.restrict(original, formatted, ranges);
        assertEquals(original, result);
    }

    /// Two adjacent fully-rewritten lines with nothing matching between them collapse
    /// into a single line-diff hunk. Selecting inside it therefore applies the whole
    /// hunk (both methods expand). This is the accepted granularity of the line-based
    /// restriction; in real code an unchanged line between the regions splits them.
    public void testContiguousChangesAreOneHunk() {
        var original = join(
                "class A {",
                "  void a() {x();}",
                "  void b() {y();}",
                "}");
        var formatted = join(
                "class A {",
                "  void a() {",
                "    x();",
                "  }",
                "  void b() {",
                "    y();",
                "  }",
                "}");
        var ranges = List.of(lineRangeContaining(original, "void a"));

        var result = RangeRestrictedFormat.restrict(original, formatted, ranges);
        assertEquals(formatted, result);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /// A range covering the content of the (single) line containing {@code needle},
    /// excluding the trailing newline so adjacent lines aren't pulled in by the
    /// inclusive boundary check.
    private static TextRange lineRangeContaining(String text, String needle) {
        var idx = text.indexOf(needle);
        assertTrue("needle not found: " + needle, idx >= 0);
        var lineStart = text.lastIndexOf('\n', idx) + 1;
        var nl = text.indexOf('\n', idx);
        var lineEnd = nl < 0 ? text.length() : nl;
        // For CRLF, exclude the trailing '\r' too so the range is pure line content.
        if (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r') lineEnd--;
        return new TextRange(lineStart, lineEnd);
    }

    private static String join(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String joinCrlf(String... lines) {
        return String.join("\r\n", lines) + "\r\n";
    }
}
