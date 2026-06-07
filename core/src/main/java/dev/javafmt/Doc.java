package dev.javafmt;

sealed interface Doc {

    /** Literal text (no newlines allowed inside). */
    record Text(String value) implements Doc {}

    /** Space when the enclosing group fits flat; newline when broken. */
    record Line() implements Doc {}

    /** Empty when the enclosing group fits flat; newline when broken. */
    record SoftLine() implements Doc {}

    /// Renders identically to {@link SoftLine} — empty when the enclosing group
    /// fits flat, newline when broken. The difference is in fit measurement:
    /// when an ENCLOSING group's fits check walks past this node (phase 2 of
    /// `fits`), it always terminates the line, regardless of whether the
    /// surrounding subgroup is being measured flat.
    ///
    /// Use sparingly. The intended (and only current) use is the method-chain
    /// pretty-printer, which wraps its first segment in a nested group so that
    /// the partial-vs-full break decision is made by ACTUAL line widths. That
    /// inner softLine MUST be visible as a break point to the surrounding
    /// fits checks (e.g. the args group of the root), otherwise the root's
    /// args incorrectly see the entire rest of the chain on its line and
    /// wrap themselves.
    record BoundaryLine() implements Doc {}

    /** Always a newline, regardless of grouping. */
    record HardLine() implements Doc {}

    /// A forced line end that terminates a trailing line comment's line.
    ///
    /// Renders like {@link HardLine} — an unconditional newline that forces every enclosing
    /// group to break (a line comment must end its line, or it would comment out whatever
    /// follows). The one difference is in the printer: a {@code CommentBreak} ABSORBS one
    /// immediately-following break, so a trailing line comment placed by the generic comment
    /// emitter inside an already-breaking context (a statement in a block, an enum constant)
    /// does not stack with that context's own separator into a blank line. This lets the
    /// emitter append a break after every trailing line comment uniformly, without each call
    /// site knowing whether its surroundings already break.
    record CommentBreak() implements Doc {}

    /** Sequence of docs. */
    record Concat(java.util.List<Doc> parts) implements Doc {}

    /** Increase indent for contents by one step (configured on the printer). */
    record Indent(Doc doc) implements Doc {}

    /**
     * Try to fit {@code doc} on a single line.
     * If it doesn't fit within the remaining width, break all Line/SoftLine nodes.
     * When {@code shouldBreak} is true, the group enters break mode immediately
     * without consulting the available width.
     */
    record Group(Doc doc, boolean shouldBreak) implements Doc {
        public Group(Doc doc) { this(doc, false); }
    }

    /**
     * Try each alternative in order; use the first that fits flat.
     * If none fit, use the last alternative in breaking mode.
     * Mirrors Prettier's conditionalGroup / propagateBreak concept.
     */
    record ConditionalGroup(java.util.List<Doc> alternatives) implements Doc {}

    // ── convenience builders ──

    static Doc space()                  { return text(" "); }
    static Doc text(String s)           { return new Text(s); }
    static Doc line()                   { return new Line(); }
    static Doc softLine()               { return new SoftLine(); }
    static Doc boundaryLine()           { return new BoundaryLine(); }
    static Doc hardLine()               { return new HardLine(); }
    static Doc commentBreak()           { return new CommentBreak(); }
    static Doc indent(Doc d)            { return new Indent(d); }
    static Doc group(Doc d)             { return new Group(d); }
    static Doc breakGroup(Doc d)        { return new Group(d, true); }
    static Doc conditionalGroup(java.util.List<Doc> alts) { return new ConditionalGroup(alts); }

    static Doc concat(Doc... parts) {
        return new Concat(java.util.List.of(parts));
    }

    static Doc concat(java.util.List<Doc> parts) {
        return new Concat(parts);
    }

    /** Join docs with a separator doc between each pair. */
    static Doc join(Doc separator, java.util.List<Doc> docs) {
        if (docs.isEmpty()) return text("");
        var parts = new java.util.ArrayList<Doc>();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) parts.add(separator);
            parts.add(docs.get(i));
        }
        return new Concat(parts);
    }
}
