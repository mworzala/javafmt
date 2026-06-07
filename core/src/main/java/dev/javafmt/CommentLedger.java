package dev.javafmt;

import org.eclipse.jdt.core.dom.BlockComment;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.LineComment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/// The emission half of the comment conservation law.
///
/// {@link CommentMap#build} guarantees every eligible comment is *attached* to exactly one AST
/// node — it throws if attachment is incomplete. Nothing historically guaranteed the symmetric
/// property: that every *attached* comment is also *emitted* into the output. A comment attached
/// to a node whose visitor never consults the CommentMap was silently dropped, caught only by the
/// whole-file multiset check in CommentPreservation (which runs on curated cases and the corpus,
/// not on arbitrary input, and does not say *which* comment or *where*).
///
/// This class closes that gap. {@link AstToDoc} records every comment it renders; this class diffs
/// that against {@link CommentMap#attachedComments()} and reports the difference — the dropped
/// comments — located by source line and text. Because it compares identities, not positions, its
/// verdict is layout-independent and identical across formatting passes, so it cannot itself break
/// idempotence.
final class CommentLedger {

    private CommentLedger() {}

    /// How an un-emitted comment is handled on the production format() path.
    enum Mode {
        /// No ledger and no fallback — the legacy behavior, available as an escape hatch.
        OFF,
        /// Production default: force-append any un-emitted comment at the end of the compilation
        /// unit so it is never lost, without throwing. Combined with the statement-level mop-up
        /// this makes a dropped comment structurally impossible.
        WARN,
        /// CI/test mode: no fallback; a comment the handlers and mop-up did not emit is a loud,
        /// located, build-breaking failure. Surfaces gaps so they get proper in-place placement.
        STRICT;

        /// Resolve the mode from the {@code javafmt.commentLedger} system property
        /// (case-insensitive; defaults to {@link #WARN}, the production safety net).
        static Mode fromProperty() {
            var raw = System.getProperty("javafmt.commentLedger");
            if (raw == null) return WARN;
            return switch (raw.strip().toLowerCase()) {
                case "strict" -> STRICT;
                case "off" -> OFF;
                default -> WARN;
            };
        }
    }

    /// Comments that were attached but never emitted, sorted by source position.
    static List<Comment> dropped(Set<Comment> attached, Set<Comment> emitted) {
        var missing = new ArrayList<Comment>();
        for (var c : attached) {
            if (!emitted.contains(c)) missing.add(c);
        }
        missing.sort(Comparator.comparingInt(Comment::getStartPosition));
        return missing;
    }

    /// A human- and CI-readable description of dropped comments, each located by 1-based source
    /// line with its verbatim text, suitable for an exception message or a log line.
    static String describe(List<Comment> dropped, String source) {
        var sb = new StringBuilder();
        sb.append(dropped.size()).append(dropped.size() == 1 ? " comment" : " comments")
                .append(" attached but never emitted:");
        for (var c : dropped) {
            sb.append("\n  line ").append(lineOf(source, c.getStartPosition()))
                    .append(" (").append(kindOf(c)).append("): ")
                    .append(snippet(source, c));
        }
        return sb.toString();
    }

    private static String kindOf(Comment c) {
        if (c instanceof LineComment) return "line";
        if (c instanceof BlockComment) return "block";
        if (c instanceof Javadoc) return "javadoc";
        return c.getClass().getSimpleName();
    }

    private static int lineOf(String source, int pos) {
        if (source == null || pos < 0) return -1;
        int line = 1;
        int limit = Math.min(pos, source.length());
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    /// The comment's source text, collapsed to one line and clipped, for a compact message.
    private static String snippet(String source, Comment c) {
        if (source == null) return "<unknown>";
        int start = Math.max(0, c.getStartPosition());
        int end = Math.min(source.length(), c.getStartPosition() + c.getLength());
        if (start >= end) return "<empty>";
        var text = source.substring(start, end).replace("\n", "⏎").strip();
        return text.length() > 80 ? text.substring(0, 77) + "..." : text;
    }
}
