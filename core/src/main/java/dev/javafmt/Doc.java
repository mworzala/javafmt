package dev.javafmt;

sealed interface Doc {

    /** Literal text (no newlines allowed inside). */
    record Text(String value) implements Doc {}

    /** Space when the enclosing group fits flat; newline when broken. */
    record Line() implements Doc {}

    /** Empty when the enclosing group fits flat; newline when broken. */
    record SoftLine() implements Doc {}

    /** Always a newline, regardless of grouping. */
    record HardLine() implements Doc {}

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
    static Doc hardLine()               { return new HardLine(); }
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
