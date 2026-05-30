package dev.javafmt;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ChildPropertyDescriptor;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.LineComment;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.jspecify.annotations.NullUnmarked;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/// Decorates an AST with comment attachments computed once after parsing.
///
/// Each comment is classified relative to the smallest AST node that contains it and the
/// direct children of that container immediately before/after the comment:
///
/// - if the comment starts on the same line as the previous child's end → trailing of prev
/// - else if a next child exists → leading of next
/// - else → dangling of the enclosing node
///
/// `///` Markdown-style Javadoc lines are excluded from attachment; they are consumed by
/// the visitor that owns the following declaration.
///
/// Identity-keyed: lookups are by `ASTNode` reference, never by source position. Once built,
/// the map is read-only and pass-stable — re-parsing the formatted output yields the same
/// attachments because the rules use line-relative position only at build time.
final class CommentMap {
    private final IdentityHashMap<ASTNode, NodeComments> attachments = new IdentityHashMap<>();
    private int attachedCount = 0;

    static final class NodeComments {
        final List<Comment> leading = new ArrayList<>();
        final List<Comment> trailing = new ArrayList<>();
        final List<Comment> dangling = new ArrayList<>();
    }

    static CommentMap build(CompilationUnit cu, String source) {
        var map = new CommentMap();
        int eligible = 0;
        for (Comment c : commentsOf(cu)) {
            if (isMarkdownJavadoc(c, source)) continue;
            eligible++;
            map.attach(c, cu, source);
        }
        if (map.attachedCount != eligible) {
            throw new IllegalStateException(
                    "CommentMap dropped " + (eligible - map.attachedCount) + " of " + eligible + " comments");
        }
        return map;
    }

    List<Comment> leading(ASTNode node) {
        var nc = attachments.get(node);
        return nc != null ? nc.leading : Collections.emptyList();
    }

    List<Comment> trailing(ASTNode node) {
        var nc = attachments.get(node);
        return nc != null ? nc.trailing : Collections.emptyList();
    }

    List<Comment> dangling(ASTNode node) {
        var nc = attachments.get(node);
        return nc != null ? nc.dangling : Collections.emptyList();
    }

    private void attach(Comment c, CompilationUnit cu, String source) {
        var enclosing = findEnclosing(cu, c);
        var children = directChildren(enclosing);

        ASTNode prev = null;
        ASTNode next = null;
        for (var ch : children) {
            int chEnd = ch.getStartPosition() + ch.getLength();
            if (chEnd <= c.getStartPosition()) {
                prev = ch;
            } else if (ch.getStartPosition() >= c.getStartPosition() + c.getLength()) {
                next = ch;
                break;
            }
        }

        int prevEnd = prev != null ? prev.getStartPosition() + prev.getLength() : -1;
        // A node's range can extend past its visible content to include a trailing newline —
        // notably a Markdown `///` Javadoc, whose range ends at the start of the next line.
        // Left as-is, prevEnd would sit on the following line and a comment there would look
        // like a same-line trailing comment of prev (and, for a Javadoc, get silently
        // dropped). Back up over trailing whitespace so "same line" reflects the real content.
        while (prevEnd > 0 && Character.isWhitespace(source.charAt(prevEnd - 1))) prevEnd--;
        boolean sameLineAsPrev = prev != null && sameLine(prevEnd, c.getStartPosition(), source);

        if (sameLineAsPrev) {
            getOrCreate(prev).trailing.add(c);
        } else if (next != null) {
            getOrCreate(next).leading.add(c);
        } else {
            getOrCreate(enclosing).dangling.add(c);
        }
        attachedCount++;
    }

    private NodeComments getOrCreate(ASTNode node) {
        return attachments.computeIfAbsent(node, k -> new NodeComments());
    }

    /// Smallest AST node whose range fully contains the comment.
    private static ASTNode findEnclosing(CompilationUnit cu, Comment c) {
        ASTNode[] holder = {cu};
        cu.accept(new ASTVisitor() {
            @Override
            public boolean preVisit2(ASTNode node) {
                if (node == cu) return true;
                int start = node.getStartPosition();
                int end = start + node.getLength();
                int cStart = c.getStartPosition();
                int cEnd = cStart + c.getLength();
                if (start <= cStart && cEnd <= end) {
                    holder[0] = node;
                    return true;
                }
                return false;
            }
        });
        return holder[0];
    }

    /// Direct children of `parent` across all child and child-list properties, sorted by position.
    @NullUnmarked
    private static List<ASTNode> directChildren(ASTNode parent) {
        var children = new ArrayList<ASTNode>();
        for (StructuralPropertyDescriptor prop : propsOf(parent)) {
            if (prop instanceof ChildPropertyDescriptor cp) {
                var value = parent.getStructuralProperty(cp);
                if (value instanceof ASTNode node) children.add(node);
            } else if (prop instanceof ChildListPropertyDescriptor lp) {
                for (ASTNode node : listProperty(parent, lp)) children.add(node);
            }
        }
        children.sort(Comparator.comparingInt(ASTNode::getStartPosition));
        return children;
    }

    @SuppressWarnings("unchecked")
    private static List<StructuralPropertyDescriptor> propsOf(ASTNode node) {
        return (List<StructuralPropertyDescriptor>) node.structuralPropertiesForType();
    }

    @SuppressWarnings("unchecked")
    private static List<ASTNode> listProperty(ASTNode parent, ChildListPropertyDescriptor lp) {
        return (List<ASTNode>) parent.getStructuralProperty(lp);
    }

    @SuppressWarnings("unchecked")
    private static List<Comment> commentsOf(CompilationUnit cu) {
        return (List<Comment>) cu.getCommentList();
    }

    private static boolean sameLine(int from, int to, String source) {
        if (source == null) return false;
        int limit = Math.min(to, source.length());
        for (int i = from; i < limit; i++) {
            if (source.charAt(i) == '\n') return false;
        }
        return true;
    }

    private static boolean isMarkdownJavadoc(Comment c, String source) {
        if (!(c instanceof LineComment) || source == null) return false;
        int start = c.getStartPosition();
        if (start + 3 > source.length()) return false;
        return source.charAt(start) == '/'
                && source.charAt(start + 1) == '/'
                && source.charAt(start + 2) == '/';
    }
}
