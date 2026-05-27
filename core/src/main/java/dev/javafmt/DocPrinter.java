package dev.javafmt;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

final class DocPrinter {
    private final int maxWidth;
    private final int indentStep;
    private final StringBuilder out = new StringBuilder();
    private int currentLineWidth = 0;
    private int pendingIndent = -1; // -1 means no pending newline

    // Stack frames: (indent level, breaking mode, doc to process)
    private record Frame(int indent, boolean flat, Doc doc) {}

    public DocPrinter(int maxWidth) {
        this(maxWidth, 4);
    }

    public DocPrinter(int maxWidth, int indentStep) {
        this.maxWidth = maxWidth;
        this.indentStep = indentStep;
    }

    public String print(Doc doc) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(0, false, doc));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            switch (frame.doc()) {

                case Doc.Text t -> {
                    flushPendingIndent();
                    out.append(t.value());
                    currentLineWidth += t.value().length();
                }

                case Doc.Concat c -> {
                    // Push in reverse so first child is processed first.
                    var parts = c.parts();
                    for (int i = parts.size() - 1; i >= 0; i--) {
                        stack.push(new Frame(frame.indent(), frame.flat(), parts.get(i)));
                    }
                }

                case Doc.Indent d -> {
                    stack.push(new Frame(frame.indent() + indentStep, frame.flat(), d.doc()));
                }

                case Doc.Line ignored -> {
                    if (frame.flat()) {
                        flushPendingIndent();
                        out.append(' ');
                        currentLineWidth += 1;
                    } else {
                        emitNewline(frame.indent());
                    }
                }

                case Doc.SoftLine ignored -> {
                    if (!frame.flat()) {
                        emitNewline(frame.indent());
                    }
                }

                case Doc.BoundaryLine ignored -> {
                    // Renders identically to SoftLine — the difference is only in fits().
                    if (!frame.flat()) {
                        emitNewline(frame.indent());
                    }
                }

                case Doc.HardLine h -> {
                    emitNewline(frame.indent());
                }

                case Doc.Group g -> {
                    boolean flatMode = !g.shouldBreak()
                            && fits(new Frame(frame.indent(), true, g.doc()), stack);
                    stack.push(new Frame(frame.indent(), flatMode, g.doc()));
                }

                case Doc.ConditionalGroup cg -> {
                    var alts = cg.alternatives();
                    boolean found = false;
                    for (Doc alt : alts) {
                        if (fitsFirstLine(new Frame(frame.indent(), true, alt), stack)) {
                            stack.push(new Frame(frame.indent(), true, alt));
                            found = true;
                            break;
                        }
                    }
                    if (!found) stack.push(new Frame(frame.indent(), false, alts.getLast()));
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

    /**
     * Two-phase rest-aware fit check. Phase 1 walks the candidate; HardLine here means
     * the group cannot render flat. Phase 2 walks the pending stack in each frame's
     * actual mode; HardLine or a break-mode line ends the current line and we fit.
     * Nested groups in either phase are measured as flat (Prettier MODE_FLAT propagation).
     *
     * BoundaryLine is the exception: in phase 2 it ALWAYS ends the line, even when seen
     * inside a subgroup that's being measured flat. That's its whole reason for existing —
     * exposing a break point through a subgroup boundary so e.g. an arg-wrap group inside
     * a chain's root can see "the chain will break before that long segment, so my line
     * really does end here" instead of measuring the entire chain as if it were flat.
     */
    private boolean fits(Frame candidate, Deque<Frame> rest) {
        int remaining = maxWidth - (pendingIndent >= 0 ? pendingIndent : currentLineWidth);

        Deque<Frame> work = new ArrayDeque<>();
        work.push(candidate);
        while (!work.isEmpty()) {
            if (remaining < 0) return false;
            Frame f = work.pop();
            switch (f.doc()) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line ignored -> remaining -= 1;
                case Doc.SoftLine ignored -> { /* zero-width when flat */ }
                case Doc.BoundaryLine ignored -> { /* zero-width when flat */ }
                case Doc.HardLine h -> { return false; }
                case Doc.Indent i -> work.push(new Frame(f.indent() + indentStep, true, i.doc()));
                case Doc.Group g -> work.push(new Frame(f.indent(), true, g.doc()));
                case Doc.ConditionalGroup cg -> work.push(new Frame(f.indent(), true, cg.alternatives().getFirst()));
                case Doc.Concat c -> pushReversed(work, f.indent(), true, c.parts());
            }
        }

        var snapshot = rest.toArray(Frame[]::new);
        for (int i = snapshot.length - 1; i >= 0; i--) work.push(snapshot[i]);

        while (!work.isEmpty()) {
            if (remaining < 0) return false;
            Frame f = work.pop();
            switch (f.doc()) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line ignored -> {
                    if (f.flat()) remaining -= 1;
                    else return true;
                }
                case Doc.SoftLine ignored -> {
                    if (!f.flat()) return true;
                }
                case Doc.BoundaryLine ignored -> { return true; }
                case Doc.HardLine h -> { return true; }
                case Doc.Indent i -> work.push(new Frame(f.indent() + indentStep, f.flat(), i.doc()));
                case Doc.Group g -> work.push(new Frame(f.indent(), true, g.doc()));
                case Doc.ConditionalGroup cg -> work.push(new Frame(f.indent(), f.flat(), cg.alternatives().getFirst()));
                case Doc.Concat c -> pushReversed(work, f.indent(), f.flat(), c.parts());
            }
        }
        return remaining >= 0;
    }

    private static void pushReversed(Deque<Frame> work, int indent, boolean flat, List<Doc> parts) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            work.push(new Frame(indent, flat, parts.get(i)));
        }
    }

    /**
     * Like {@link #fits}, but accepts a candidate that contains HardLines: phase 1 treats
     * the first HardLine as "first line ends here" and succeeds if the accumulated width
     * has stayed within budget. Used by ConditionalGroup so that an alternative whose
     * intended rendering is multi-line (e.g. a call whose last arg is a block-bodied
     * lambda) can be chosen based on whether its FIRST line fits — the "trailing lambda"
     * idiom `call(a, b, x -> { ... })` instead of wrapping every arg.
     *
     * Phase 2 logic is identical to fits — break-mode line markers in the surrounding
     * stack always end the line successfully.
     */
    private boolean fitsFirstLine(Frame candidate, Deque<Frame> rest) {
        int remaining = maxWidth - (pendingIndent >= 0 ? pendingIndent : currentLineWidth);

        Deque<Frame> work = new ArrayDeque<>();
        work.push(candidate);
        while (!work.isEmpty()) {
            if (remaining < 0) return false;
            Frame f = work.pop();
            switch (f.doc()) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line ignored -> remaining -= 1;
                case Doc.SoftLine ignored -> { /* zero-width when flat */ }
                case Doc.BoundaryLine ignored -> { /* zero-width when flat */ }
                case Doc.HardLine h -> { return true; }  // First line ends here — fit so far is OK.
                case Doc.Indent i -> work.push(new Frame(f.indent() + indentStep, true, i.doc()));
                case Doc.Group g -> work.push(new Frame(f.indent(), true, g.doc()));
                case Doc.ConditionalGroup cg -> work.push(new Frame(f.indent(), true, cg.alternatives().getFirst()));
                case Doc.Concat c -> pushReversed(work, f.indent(), true, c.parts());
            }
        }

        var snapshot = rest.toArray(Frame[]::new);
        for (int i = snapshot.length - 1; i >= 0; i--) work.push(snapshot[i]);

        while (!work.isEmpty()) {
            if (remaining < 0) return false;
            Frame f = work.pop();
            switch (f.doc()) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line ignored -> {
                    if (f.flat()) remaining -= 1;
                    else return true;
                }
                case Doc.SoftLine ignored -> {
                    if (!f.flat()) return true;
                }
                case Doc.BoundaryLine ignored -> { return true; }
                case Doc.HardLine h -> { return true; }
                case Doc.Indent i -> work.push(new Frame(f.indent() + indentStep, f.flat(), i.doc()));
                case Doc.Group g -> work.push(new Frame(f.indent(), true, g.doc()));
                case Doc.ConditionalGroup cg -> work.push(new Frame(f.indent(), f.flat(), cg.alternatives().getFirst()));
                case Doc.Concat c -> pushReversed(work, f.indent(), f.flat(), c.parts());
            }
        }
        return remaining >= 0;
    }
}
