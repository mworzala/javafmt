package black;

import java.util.ArrayDeque;
import java.util.Deque;

final class DocPrinter {
    private final int maxWidth;
    private final StringBuilder out = new StringBuilder();
    private int currentLineWidth = 0;
    private int pendingIndent = -1; // -1 means no pending newline

    // Stack frames: (indent level, breaking mode, doc to process)
    private record Frame(int indent, boolean flat, Doc doc) {}

    public DocPrinter(int maxWidth) {
        this.maxWidth = maxWidth;
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
                    stack.push(new Frame(frame.indent() + 4, frame.flat(), d.doc()));
                }

                case Doc.Line line -> {
                    if (frame.flat()) {
                        flushPendingIndent();

                        // Group didn't break — emit the flat alternative (usually a space).
                        out.append(line.flat());
                        currentLineWidth += line.flat().length();
                    } else {
                        emitNewline(frame.indent());
                    }
                }

                case Doc.HardLine h -> {
                    emitNewline(frame.indent());
                }

                case Doc.Group g -> {
                    // Try flat first. If the entire group fits, use flat mode.
                    if (fits(frame.indent(), g.doc())) {
                        stack.push(new Frame(frame.indent(), true, g.doc()));
                    } else {
                        stack.push(new Frame(frame.indent(), false, g.doc()));
                    }
                }

                case Doc.ConditionalGroup cg -> {
                    var alts = cg.alternatives();
                    boolean found = false;
                    for (Doc alt : alts) {
                        if (fits(frame.indent(), alt)) {
                            stack.push(new Frame(frame.indent(), true, alt));
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        stack.push(new Frame(frame.indent(), false, alts.getLast()));
                    }
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
     * Check if a doc fits on the remaining line in flat mode.
     * This is a quick pessimistic check — if we hit a HardLine, it doesn't fit.
     */
    private boolean fits(int indent, Doc doc) {
        int remaining = maxWidth - (pendingIndent >= 0 ? pendingIndent : currentLineWidth);
        Deque<Doc> work = new ArrayDeque<>();
        work.push(doc);

        while (!work.isEmpty() && remaining >= 0) {
            Doc d = work.pop();
            switch (d) {
                case Doc.Text t -> remaining -= t.value().length();
                case Doc.Line l -> remaining -= l.flat().length();
                case Doc.HardLine h -> {
                    return false;
                }
                case Doc.Indent i -> work.push(i.doc());
                case Doc.Group g -> work.push(g.doc());
                case Doc.ConditionalGroup cg -> work.push(cg.alternatives().getFirst());
                case Doc.Concat c -> {
                    for (int i = c.parts().size() - 1; i >= 0; i--) {
                        work.push(c.parts().get(i));
                    }
                }
            }
        }
        return remaining >= 0;
    }
}
