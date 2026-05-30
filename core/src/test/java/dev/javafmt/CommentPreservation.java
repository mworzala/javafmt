package dev.javafmt;

import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.fail;

/// Asserts that formatting neither drops nor duplicates a comment.
///
/// The formatter is allowed to re-indent block comments and strip trailing whitespace
/// from line comments, so comparison is by a whitespace-normalized multiset of the
/// comment texts (every line stripped of leading/trailing whitespace), keyed by content
/// and independent of position. A dropped or added comment is therefore a loud failure —
/// the only thing AstEquivalence and idempotence checks can't see.
final class CommentPreservation {

    private CommentPreservation() {}

    /// @param label    identifies the source (file path / test name) in failure messages
    /// @param source   the original source text
    /// @param formatted the formatter's output
    /// @param original parse of {@code source}
    /// @param reparsed parse of {@code formatted}
    static void assertPreserved(String label, String source, String formatted,
            CompilationUnit original, CompilationUnit reparsed) {
        var before = commentCounts(original, source);
        var after = commentCounts(reparsed, formatted);
        if (before.equals(after)) return;

        var msg = new StringBuilder("comments not preserved by formatting (" + label + "):\n");
        for (var e : before.entrySet()) {
            int lost = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (lost > 0) msg.append("  DROPPED x").append(lost).append(": ").append(oneLine(e.getKey())).append('\n');
        }
        for (var e : after.entrySet()) {
            int gained = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (gained > 0) msg.append("  ADDED   x").append(gained).append(": ").append(oneLine(e.getKey())).append('\n');
        }
        fail(msg.toString());
    }

    @SuppressWarnings("unchecked")
    private static TreeMap<String, Integer> commentCounts(CompilationUnit cu, String src) {
        var counts = new TreeMap<String, Integer>();
        for (Comment c : (List<Comment>) cu.getCommentList()) {
            int start = c.getStartPosition();
            if (start < 0) continue; // defensive: comments always have a source range
            var text = src.substring(start, start + c.getLength());
            counts.merge(normalize(text), 1, Integer::sum);
        }
        return counts;
    }

    /// Strip each line of leading/trailing whitespace and rejoin. This makes the key
    /// invariant under the formatter's re-indentation of block comments and its
    /// trailing-whitespace stripping of line comments, while still catching any change
    /// to the actual comment content.
    private static String normalize(String text) {
        var sb = new StringBuilder();
        boolean first = true;
        for (var line : text.split("\n", -1)) {
            if (!first) sb.append('\n');
            sb.append(line.strip());
            first = false;
        }
        return sb.toString();
    }

    private static String oneLine(String key) {
        return key.replace("\n", "⏎");
    }
}
