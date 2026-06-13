package dev.javafmt;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.ExportsDirective;
import org.eclipse.jdt.core.dom.ModuleDeclaration;
import org.eclipse.jdt.core.dom.ModuleDirective;
import org.eclipse.jdt.core.dom.ModuleModifier;
import org.eclipse.jdt.core.dom.ModulePackageAccess;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.OpensDirective;
import org.eclipse.jdt.core.dom.ProvidesDirective;
import org.eclipse.jdt.core.dom.RequiresDirective;
import org.eclipse.jdt.core.dom.UsesDirective;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static dev.javafmt.Doc.*;

/// Renders a `module-info.java` module declaration to a {@link Doc}. Companion to
/// {@link AstToDoc}, which owns the compilation-unit shell (package/imports/header comments)
/// and dispatches the module declaration here from {@code visit(CompilationUnit)}.
///
/// This is not an {@link org.eclipse.jdt.core.dom.ASTVisitor}: module directives are an
/// expression-free sublanguage whose names are plain dotted identifiers, so they render
/// straight from the AST as text. The few constructs that genuinely share machinery — the
/// module's own Javadoc, its annotations, and every comment — are produced by calling back
/// into the owning {@code AstToDoc}, so the single emission ledger still records them and the
/// comment-conservation law holds across both classes.
///
/// Layout: one directive per line at one indent; directives bucketed by kind into
/// `exports`/`opens`/`requires`/`uses`/`provides` blocks (see {@link ModuleDirectiveOrderer}),
/// one blank line between blocks. A `to`/`with` target list stays flat when it fits and breaks
/// one-per-line otherwise — without a trailing comma, which the module grammar forbids.
final class ModuleInfoToDoc {

    private final AstToDoc owner;
    private final CommentMap comments;

    ModuleInfoToDoc(AstToDoc owner, CommentMap comments) {
        this.owner = owner;
        this.comments = comments;
    }

    Doc render(ModuleDeclaration node) {
        var parts = new ArrayList<Doc>();

        owner.visitModifiers(parts, node, ModuleDeclaration.ANNOTATIONS_PROPERTY);

        // JDT never populates ModuleDeclaration.getJavadoc() — it stays null even for a leading
        // `/** */` or `///` doc comment. Such a comment (and any other comment sitting between
        // the module's start and its name) instead attaches as a leading comment of the module
        // name; when annotations are present, the doc comment attaches to the first annotation
        // and was already emitted by visitModifiers above. Emit whatever attached to the name
        // here, just before the `module` keyword.
        for (var lc : leadingOf(node.getName())) {
            parts.add(owner.renderComment(lc));
            parts.add(hardLine());
        }

        if (node.isOpen()) parts.add(text("open "));
        parts.add(text("module "));
        parts.add(text(node.getName().getFullyQualifiedName()));

        // Each body item is one rendered line (a leading comment, or a directive with its
        // trailing comments). groupStarts marks the item index that begins each directive
        // block, so a blank line can be inserted before it during assembly.
        var items = new ArrayList<Doc>();
        var groupStarts = new HashSet<Integer>();
        for (var group : ModuleDirectiveOrderer.group(moduleStatements(node))) {
            groupStarts.add(items.size());
            for (var directive : group) {
                for (var lc : leadingOf(directive)) items.add(owner.renderComment(lc));
                items.add(directiveWithTrailing(directive));
            }
        }
        // Comments inside the braces after the last directive (or in an otherwise empty body)
        // attach as dangling of the module.
        for (var dc : danglingOf(node)) items.add(owner.renderComment(dc));
        // Safety net: a comment that attached to a directive's sub-node (e.g. trailing a
        // package name in `exports a.b /* c */ to x`) is reached by none of the emission above,
        // and a ModuleDirective is neither a Statement nor a BodyDeclaration so AstToDoc's
        // postVisit orphan mop-up skips it too. Sweep the whole module range and append any
        // still-unemitted comment so the conservation law holds. Placement is coarse, but the
        // comment is never lost and reparses as an ordinary body comment, so a second format is
        // a no-op.
        for (var c : owner.orphansWithin(node)) items.add(owner.renderComment(c));

        if (items.isEmpty()) {
            parts.add(text(" {}"));
            return concat(parts);
        }

        parts.add(text(" {"));
        var body = new ArrayList<Doc>();
        for (int i = 0; i < items.size(); i++) {
            body.add(hardLine());
            if (i != 0 && groupStarts.contains(i)) body.add(hardLine()); // blank line between blocks
            body.add(items.get(i));
        }
        parts.add(indent(concat(body)));
        parts.add(hardLine());
        parts.add(text("}"));
        return concat(parts);
    }

    private Doc directiveWithTrailing(ModuleDirective directive) {
        Doc doc = renderDirective(directive);
        for (var tc : trailingOf(directive)) {
            doc = concat(doc, text(" "), owner.renderComment(tc));
        }
        return doc;
    }

    private Doc renderDirective(ModuleDirective directive) {
        if (directive instanceof RequiresDirective r) {
            var parts = new ArrayList<Doc>();
            parts.add(text("requires "));
            // Modifiers (`transitive`, `static`) are kept in source order, as written.
            for (var modifier : modifiersOf(r)) {
                parts.add(text(modifier.getKeyword().toString()));
                parts.add(space());
            }
            parts.add(text(r.getName().getFullyQualifiedName()));
            parts.add(text(";"));
            return concat(parts);
        }
        if (directive instanceof ExportsDirective e) {
            return targetList("exports", e.getName().getFullyQualifiedName(), "to", modulesOf(e));
        }
        if (directive instanceof OpensDirective o) {
            return targetList("opens", o.getName().getFullyQualifiedName(), "to", modulesOf(o));
        }
        if (directive instanceof UsesDirective u) {
            return text("uses " + u.getName().getFullyQualifiedName() + ";");
        }
        if (directive instanceof ProvidesDirective p) {
            return targetList("provides", p.getName().getFullyQualifiedName(), "with", implementationsOf(p));
        }
        throw new IllegalStateException(
                "unknown module directive: " + directive.getClass().getSimpleName());
    }

    /// `keyword name;` when {@code targets} is empty (an unqualified `exports`/`opens`),
    /// otherwise `keyword name <connector> a, b, c;` — flat when it fits, else one target per
    /// line. No trailing comma: the `to`/`with` grammar does not permit one.
    private Doc targetList(String keyword, String name, String connector, List<Name> targets) {
        if (targets.isEmpty()) {
            return text(keyword + " " + name + ";");
        }
        var rendered = new ArrayList<Doc>(targets.size());
        for (var target : targets) rendered.add(text(target.getFullyQualifiedName()));
        return group(concat(
                text(keyword + " " + name + " " + connector),
                indent(concat(line(), join(concat(text(","), line()), rendered))),
                text(";")));
    }

    private List<Comment> leadingOf(ASTNode node) {
        return comments != null ? comments.leading(node) : List.of();
    }

    private List<Comment> trailingOf(ASTNode node) {
        return comments != null ? comments.trailing(node) : List.of();
    }

    private List<Comment> danglingOf(ASTNode node) {
        return comments != null ? comments.dangling(node) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<ModuleDirective> moduleStatements(ModuleDeclaration node) {
        return node.moduleStatements();
    }

    @SuppressWarnings("unchecked")
    private static List<ModuleModifier> modifiersOf(RequiresDirective r) {
        return r.modifiers();
    }

    @SuppressWarnings("unchecked")
    private static List<Name> modulesOf(ModulePackageAccess pa) {
        return pa.modules();
    }

    @SuppressWarnings("unchecked")
    private static List<Name> implementationsOf(ProvidesDirective p) {
        return p.implementations();
    }
}
