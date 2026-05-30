package dev.javafmt.idea;

import com.intellij.openapi.util.TextRange;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class RangeRestrictedFormat {

    private RangeRestrictedFormat() {}

    /// Returns a full-document string equal to {@code original} with only the
    /// formatting changes overlapping one of {@code ranges} applied.
    ///
    /// When {@code ranges} is empty (a non-selection "reformat file"), the restriction
    /// is a no-op and the result equals {@code formatted}.
    static @NotNull String restrict(@NotNull String original, @NotNull String formatted, @NotNull List<TextRange> ranges) {
        if (original.equals(formatted)) return original;

        // No ranges = whole-document format
        var restrict = !ranges.isEmpty();
        if (!restrict) return formatted;

        var origLines = Diff.splitLines(original);
        var fmtLines = Diff.splitLines(formatted);
        var origStarts = lineStartOffsets(original, origLines);
        var fmtStarts = lineStartOffsets(formatted, fmtLines);

        if (origStarts[origLines.length] != original.length()
                || fmtStarts[fmtLines.length] != formatted.length()) {
            return formatted;
        }

        Diff.Change change;
        try {
            change = Diff.buildChanges(origLines, fmtLines);
        } catch (FilesTooBigForDiffException e) {
            return formatted;
        }

        var out = new StringBuilder(formatted.length());
        var cursor = 0; // offset into `original` already copied to `out`
        for (var c = change; c != null; c = c.link) {
            var origStart = origStarts[c.line0];
            var origEnd = origStarts[c.line0 + c.deleted];
            // Unchanged region before this hunk is copied verbatim.
            out.append(original, cursor, origStart);
            if (intersectsAny(ranges, origStart, origEnd)) {
                out.append(formatted, fmtStarts[c.line1], fmtStarts[c.line1 + c.inserted]);
            } else {
                out.append(original, origStart, origEnd);
            }
            cursor = origEnd;
        }
        out.append(original, cursor, original.length());
        return out.toString();
    }

    private static int[] lineStartOffsets(@NotNull String text, @NotNull String[] lines) {
        var starts = new int[lines.length + 1];
        var offset = 0;
        for (var i = 0; i < lines.length; i++) {
            starts[i] = offset;
            offset += lines[i].length();
            offset += separatorLength(text, offset);
        }
        starts[lines.length] = offset;
        return starts;
    }

    private static int separatorLength(@NotNull String text, int offset) {
        if (offset >= text.length()) return 0;
        var ch = text.charAt(offset);
        if (ch == '\r') {
            return (offset + 1 < text.length() && text.charAt(offset + 1) == '\n') ? 2 : 1;
        }
        return ch == '\n' ? 1 : 0;
    }

    private static boolean intersectsAny(@NotNull List<TextRange> ranges, int start, int end) {
        for (var r : ranges) {
            if (start <= r.getEndOffset() && r.getStartOffset() <= end) return true;
        }
        return false;
    }
}
