package dev.javafmt;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.LineComment;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/// Verbatim preservation for `// @formatter:off` / `// @formatter:on` directives.
///
/// javafmt is an AST → {@link Doc} → print pipeline that discards the original formatting on
/// purpose, so a region the user has opted out of can't simply be "left alone" mid-print. The
/// trick — the same one Spotless uses — is to format the whole file normally and then splice the
/// original source back over each disabled region. The marker comments survive a reformat (the
/// {@link CommentLedger} guarantees no comment is ever dropped), so the same regions can be
/// located in the formatted output and overwritten with the original bytes.
///
/// Rules (see also `STYLE.md`):
///
///   - A directive is a `//` line comment whose text, with the leading `//` and surrounding
///     whitespace removed, is exactly `@formatter:off` or `@formatter:on`. Block comments, `///`
///     Javadoc, and trailing markers (anything other than whitespace before the `//` on its line)
///     are not directives.
///   - State is a single toggle. An `off` seen while enabled opens a region; the next `on` closes
///     it. Any further `off` seen while already disabled, or any `on` seen while already enabled,
///     is ignored.
///   - An `off` with no following `on` disables to end of file — so a standalone
///     `// @formatter:off` on the first line preserves the whole file verbatim.
///   - Regions are whole-line: from the start of the off marker's line through the end of the on
///     marker's line (its trailing newline included).
final class FormatterDirectives {

    private FormatterDirectives() {}

    private static final String OFF = "@formatter:off";
    private static final String ON = "@formatter:on";

    /// Applies `// @formatter:off` / `on` to an already-formatted compilation unit: splices the
    /// original source back over each disabled region, then guards the result.
    ///
    /// @param source     the original source
    /// @param sourceCu   parse of {@code source} (its comment list locates the directives)
    /// @param formatted  the formatter's normal output for {@code source}
    /// @param reparse    parses a Java source string the same way {@code sourceCu} was parsed
    /// @return the spliced output, or {@code formatted} unchanged when there are no directives or
    ///         the splice could not be applied safely
    static String apply(String source, CompilationUnit sourceCu, String formatted,
            Function<String, CompilationUnit> reparse) {
        var regions = disabledRegions(source, sourceCu.getCommentList());
        if (regions.isEmpty()) return formatted;

        var formattedCu = reparse.apply(formatted);
        var formattedRegions = disabledRegions(formatted, formattedCu.getCommentList());
        var spliced = reconcile(source, regions, formatted, formattedRegions);
        if (spliced.equals(formatted)) return formatted;

        // Safety net (invariant #1): splicing original bytes back in must neither break the parse
        // nor change meaning. A directive whose markers the formatter relocated across a structural
        // token — an `@formatter:on` wedged between an `implements` list and its `{`, or a region
        // spanning an import block that gets sorted — can misalign whole-line splicing. When that
        // happens we drop the directive for this file and keep the plain formatted output (which is
        // equivalent by construction) rather than emit corrupt or meaning-changed source.
        var splicedCu = reparse.apply(spliced);
        if (splicedCu.getProblems().length > 0) return formatted;
        return AstEquivalence.equivalent(sourceCu, splicedCu) ? spliced : formatted;
    }

    /// Char ranges `[start, end)` of {@code source} that lie inside a disabled region, in source
    /// order. {@code comments} is the parsed comment list of {@code source}
    /// ({@code CompilationUnit#getCommentList()}); detecting markers from real {@link LineComment}
    /// nodes — rather than scanning text — is what keeps a `@formatter:off` inside a string literal
    /// or block comment from being mistaken for a directive.
    ///
    /// Returns an empty list when the file has no effective directives, letting the caller skip
    /// the reparse-and-splice step entirely.
    static List<int[]> disabledRegions(String source, @Nullable List<?> comments) {
        if (comments == null || comments.isEmpty()) return List.of();
        // Cheap bail-out for the overwhelmingly common no-directive file.
        if (source.indexOf("@formatter:") < 0) return List.of();

        var regions = new ArrayList<int[]>();
        int regionStart = -1; // start-of-line offset of the open `off`, or -1 while enabled
        for (var o : comments) {
            if (!(o instanceof LineComment c)) continue;
            int start = c.getStartPosition();
            if (start < 0) continue;
            if (!standalone(source, start)) continue;
            var tag = tagOf(source, start, c.getLength());
            if (tag == null) continue;
            if (regionStart < 0) {
                if (tag == Tag.OFF) regionStart = startOfLine(source, start);
            } else if (tag == Tag.ON) {
                regions.add(new int[] {regionStart, endOfLine(source, start + c.getLength())});
                regionStart = -1;
            }
        }
        if (regionStart >= 0) regions.add(new int[] {regionStart, source.length()});
        return regions;
    }

    /// Overwrites each disabled region of {@code formatted} with the corresponding original bytes
    /// from {@code original}. The Nth region in the formatted output (markers preserved, so they
    /// appear in the same order) corresponds to the Nth region of the original.
    ///
    /// If the marker structure failed to round-trip (the counts differ — e.g. a reformat reordered
    /// a directive that sat among imports), this returns the plain formatted output rather than
    /// splicing misaligned ranges. That degrades to "directive ignored" but never corrupts.
    static String reconcile(String original, List<int[]> originalRegions,
            String formatted, List<int[]> formattedRegions) {
        if (originalRegions.isEmpty()) return formatted;
        if (originalRegions.size() != formattedRegions.size()) return formatted;
        var sb = new StringBuilder(formatted);
        // Splice back-to-front so earlier offsets stay valid as later regions are replaced.
        for (int i = originalRegions.size() - 1; i >= 0; i--) {
            var dst = formattedRegions.get(i);
            var src = originalRegions.get(i);
            sb.replace(dst[0], dst[1], original.substring(src[0], src[1]));
        }
        return sb.toString();
    }

    private enum Tag { OFF, ON }

    /// Classifies a line comment as an `off`/`on` directive, or {@code null} if it is neither.
    private static @Nullable Tag tagOf(String source, int start, int length) {
        int end = Math.min(start + length, source.length());
        // A LineComment always opens with `//`; a third `/` makes it `///` Markdown Javadoc, not a
        // directive.
        if (start + 2 > end || source.charAt(start) != '/' || source.charAt(start + 1) != '/') {
            return null;
        }
        int body = start + 2;
        if (body < end && source.charAt(body) == '/') return null;
        var text = source.substring(body, end).trim();
        if (text.equals(OFF)) return Tag.OFF;
        if (text.equals(ON)) return Tag.ON;
        return null;
    }

    /// Whether only whitespace precedes the comment on its physical line. A trailing marker
    /// (`foo(); // @formatter:off`) is not standalone and so is not honored — that also sidesteps
    /// the case where a wrapped statement pushes a trailing comment off its original line, which
    /// would break whole-line splicing.
    private static boolean standalone(String source, int commentStart) {
        for (int i = startOfLine(source, commentStart); i < commentStart; i++) {
            char ch = source.charAt(i);
            if (ch != ' ' && ch != '\t' && ch != '\f') return false;
        }
        return true;
    }

    private static int startOfLine(String source, int pos) {
        int i = Math.min(pos, source.length());
        while (i > 0 && source.charAt(i - 1) != '\n') i--;
        return i;
    }

    /// Index just past the end of the line containing {@code pos} — i.e. the start of the next
    /// line, with the trailing newline included, or the length of {@code source} at EOF.
    private static int endOfLine(String source, int pos) {
        int i = Math.min(pos, source.length());
        while (i < source.length() && source.charAt(i) != '\n') i++;
        if (i < source.length()) i++;
        return i;
    }
}
