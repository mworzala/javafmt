package dev.javafmt;

import java.util.List;

final class DocPrinter {
    private final int maxWidth;
    private final int indentStep;
    private final StringBuilder out = new StringBuilder();
    private int currentLineWidth = 0;
    private int pendingIndent = -1; // -1 means no pending newline

    // Main work stack — drives `print`. Replaces an ArrayDeque<Frame> so that each
    // push/pop is a couple of array writes/reads instead of a Frame object allocation
    // per stack entry. Three parallel arrays act as one logical stack of (indent,
    // flat, doc) entries.
    private int[]     mainIndents = new int[64];
    private boolean[] mainFlats   = new boolean[64];
    private Doc[]     mainDocs    = new Doc[64];
    private int       mainTop     = 0;

    // Scratch work stack — reused across every `fits` / `fitsFirstLine` call so the
    // fits algorithm itself does zero allocation per invocation. (Previously each
    // call did `new ArrayDeque<>()` + a `Frame[]` snapshot of the main stack + a
    // fresh Frame on every push.)
    private int[]     scratchIndents = new int[64];
    private boolean[] scratchFlats   = new boolean[64];
    private Doc[]     scratchDocs    = new Doc[64];
    private int       scratchTop     = 0;

    public DocPrinter(int maxWidth) {
        this(maxWidth, 4);
    }

    public DocPrinter(int maxWidth, int indentStep) {
        this.maxWidth = maxWidth;
        this.indentStep = indentStep;
    }

    public String print(Doc doc) {
        mainTop = 0;
        pushMain(0, false, doc);

        while (mainTop > 0) {
            mainTop--;
            int indent = mainIndents[mainTop];
            boolean flat = mainFlats[mainTop];
            Doc d = mainDocs[mainTop];

            switch (d) {

                case Doc.Text t -> {
                    flushPendingIndent();
                    out.append(t.value());
                    currentLineWidth += t.value().length();
                }

                case Doc.Concat c -> pushMainReversed(indent, flat, c.parts());

                case Doc.Indent inner -> pushMain(indent + indentStep, flat, inner.doc());

                case Doc.Line ignored -> {
                    if (flat) {
                        flushPendingIndent();
                        out.append(' ');
                        currentLineWidth += 1;
                    } else {
                        emitNewline(indent);
                    }
                }

                case Doc.SoftLine ignored -> {
                    if (!flat) emitNewline(indent);
                }

                case Doc.BoundaryLine ignored -> {
                    // Renders identically to SoftLine — the difference is only in fits().
                    if (!flat) emitNewline(indent);
                }

                case Doc.HardLine h -> emitNewline(indent);

                case Doc.Group g -> {
                    boolean flatMode = !g.shouldBreak() && fits(indent, g.doc());
                    pushMain(indent, flatMode, g.doc());
                }

                case Doc.ConditionalGroup cg -> {
                    var alts = cg.alternatives();
                    boolean found = false;
                    for (Doc alt : alts) {
                        if (fitsFirstLine(indent, alt)) {
                            pushMain(indent, true, alt);
                            found = true;
                            break;
                        }
                    }
                    if (!found) pushMain(indent, false, alts.getLast());
                }
            }
        }
        return out.toString();
    }

    private void emitNewline(int indent) {
        // If there's already a pending indent (back-to-back newlines),
        // flush it as a bare newline first.
        if (pendingIndent >= 0) {
            out.append('\n');
        }
        pendingIndent = indent;
    }

    private void flushPendingIndent() {
        if (pendingIndent >= 0) {
            out.append('\n');
            out.append(" ".repeat(pendingIndent));
            currentLineWidth = pendingIndent;
            pendingIndent = -1;
        }
    }

    // ── fit checks ────────────────────────────────────────────────────────────
    //
    // Two-phase rest-aware fit check. Phase 1 walks the candidate; in `fits` a
    // HardLine here means the group cannot render flat (returns false), while in
    // `fitsFirstLine` it means "first line ends, fits if accumulated width is OK"
    // (returns true). Phase 2 walks the main stack — the in-flight rest of the doc
    // — in each frame's actual mode; a HardLine or break-mode line ends the
    // current line and we fit.
    //
    // BoundaryLine is the exception: in phase 2 it ALWAYS ends the line, even when
    // seen inside a subgroup that's being measured flat. That's its whole reason
    // for existing — exposing a break point through a subgroup boundary so e.g. an
    // arg-wrap group inside a chain's root can see "the chain will break before
    // that long segment, so my line really does end here" instead of measuring
    // the entire chain as if it were flat.
    //
    // Both methods reuse the scratch stack and never allocate.

    private boolean fits(int candidateIndent, Doc candidateDoc) {
        scratchTop = 0;
        pushScratch(candidateIndent, true, candidateDoc);
        int remaining = maxWidth - (pendingIndent >= 0 ? pendingIndent : currentLineWidth);

        // Phase 1: walk the candidate flat.
        while (scratchTop > 0) {
            if (remaining < 0) return false;
            scratchTop--;
            int indent = scratchIndents[scratchTop];
            Doc doc = scratchDocs[scratchTop];

            switch (doc) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line ignored -> remaining -= 1;
                case Doc.SoftLine ignored -> { /* zero-width when flat */ }
                case Doc.BoundaryLine ignored -> { /* zero-width when flat */ }
                case Doc.HardLine h -> { return false; }
                case Doc.Indent i -> pushScratch(indent + indentStep, true, i.doc());
                case Doc.Group g -> pushScratch(indent, true, g.doc());
                case Doc.ConditionalGroup cg -> pushScratch(indent, true, cg.alternatives().getFirst());
                case Doc.Concat c -> pushScratchReversed(indent, true, c.parts());
            }
        }

        // Phase 2: walk the rest of the main stack in pop order (top-down). Push
        // bottom-up into the scratch so that the first pop sees what would have
        // been popped first from the main stack.
        for (int i = 0; i < mainTop; i++) {
            pushScratch(mainIndents[i], mainFlats[i], mainDocs[i]);
        }
        return walkRest(remaining);
    }

    private boolean fitsFirstLine(int candidateIndent, Doc candidateDoc) {
        scratchTop = 0;
        pushScratch(candidateIndent, true, candidateDoc);
        int remaining = maxWidth - (pendingIndent >= 0 ? pendingIndent : currentLineWidth);

        // Phase 1 — identical to fits except HardLine succeeds: it means the
        // first line of the alternative ends here, and the width accumulated so
        // far has stayed within budget.
        while (scratchTop > 0) {
            if (remaining < 0) return false;
            scratchTop--;
            int indent = scratchIndents[scratchTop];
            Doc doc = scratchDocs[scratchTop];

            switch (doc) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line ignored -> remaining -= 1;
                case Doc.SoftLine ignored -> { /* zero-width when flat */ }
                case Doc.BoundaryLine ignored -> { /* zero-width when flat */ }
                case Doc.HardLine h -> { return true; }
                case Doc.Indent i -> pushScratch(indent + indentStep, true, i.doc());
                case Doc.Group g -> pushScratch(indent, true, g.doc());
                case Doc.ConditionalGroup cg -> pushScratch(indent, true, cg.alternatives().getFirst());
                case Doc.Concat c -> pushScratchReversed(indent, true, c.parts());
            }
        }

        for (int i = 0; i < mainTop; i++) {
            pushScratch(mainIndents[i], mainFlats[i], mainDocs[i]);
        }
        return walkRest(remaining);
    }

    /// Phase 2 — drains the scratch stack (which holds the main stack copied
    /// into it, plus any children we recursively push as we walk). Shared
    /// between `fits` and `fitsFirstLine`: their phase 2 semantics are identical.
    private boolean walkRest(int remaining) {
        while (scratchTop > 0) {
            if (remaining < 0) return false;
            scratchTop--;
            int indent = scratchIndents[scratchTop];
            boolean flat = scratchFlats[scratchTop];
            Doc doc = scratchDocs[scratchTop];

            switch (doc) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line ignored -> {
                    if (flat) remaining -= 1;
                    else return true;
                }
                case Doc.SoftLine ignored -> {
                    if (!flat) return true;
                }
                case Doc.BoundaryLine ignored -> { return true; }
                case Doc.HardLine h -> { return true; }
                case Doc.Indent i -> pushScratch(indent + indentStep, flat, i.doc());
                case Doc.Group g -> pushScratch(indent, true, g.doc());
                case Doc.ConditionalGroup cg -> pushScratch(indent, flat, cg.alternatives().getFirst());
                case Doc.Concat c -> pushScratchReversed(indent, flat, c.parts());
            }
        }
        return remaining >= 0;
    }

    // ── stack helpers ─────────────────────────────────────────────────────────

    private void pushMain(int indent, boolean flat, Doc doc) {
        if (mainTop == mainDocs.length) growMain();
        mainIndents[mainTop] = indent;
        mainFlats[mainTop]   = flat;
        mainDocs[mainTop]    = doc;
        mainTop++;
    }

    private void pushMainReversed(int indent, boolean flat, List<Doc> parts) {
        int n = parts.size();
        ensureMain(mainTop + n);
        for (int i = n - 1; i >= 0; i--) {
            mainIndents[mainTop] = indent;
            mainFlats[mainTop]   = flat;
            mainDocs[mainTop]    = parts.get(i);
            mainTop++;
        }
    }

    private void pushScratch(int indent, boolean flat, Doc doc) {
        if (scratchTop == scratchDocs.length) growScratch();
        scratchIndents[scratchTop] = indent;
        scratchFlats[scratchTop]   = flat;
        scratchDocs[scratchTop]    = doc;
        scratchTop++;
    }

    private void pushScratchReversed(int indent, boolean flat, List<Doc> parts) {
        int n = parts.size();
        ensureScratch(scratchTop + n);
        for (int i = n - 1; i >= 0; i--) {
            scratchIndents[scratchTop] = indent;
            scratchFlats[scratchTop]   = flat;
            scratchDocs[scratchTop]    = parts.get(i);
            scratchTop++;
        }
    }

    private void growMain() {
        ensureMain(mainTop + 1);
    }

    private void growScratch() {
        ensureScratch(scratchTop + 1);
    }

    private void ensureMain(int needed) {
        if (needed <= mainDocs.length) return;
        int cap = nextPowerOfTwo(needed);
        mainIndents = java.util.Arrays.copyOf(mainIndents, cap);
        mainFlats   = java.util.Arrays.copyOf(mainFlats,   cap);
        mainDocs    = java.util.Arrays.copyOf(mainDocs,    cap);
    }

    private void ensureScratch(int needed) {
        if (needed <= scratchDocs.length) return;
        int cap = nextPowerOfTwo(needed);
        scratchIndents = java.util.Arrays.copyOf(scratchIndents, cap);
        scratchFlats   = java.util.Arrays.copyOf(scratchFlats,   cap);
        scratchDocs    = java.util.Arrays.copyOf(scratchDocs,    cap);
    }

    private static int nextPowerOfTwo(int n) {
        // Lifted from HashMap.tableSizeFor.
        int x = n - 1;
        x |= x >>> 1;
        x |= x >>> 2;
        x |= x >>> 4;
        x |= x >>> 8;
        x |= x >>> 16;
        return x + 1;
    }
}
