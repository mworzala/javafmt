package dev.javafmt;

import org.eclipse.jdt.core.dom.*;
import org.jspecify.annotations.NullUnmarked;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static dev.javafmt.Doc.*;

final class AstToDoc extends ASTVisitor {

    private final String source;
    private final CommentMap comments;
    private Doc result;
    private CompilationUnit compilationUnit;

    // The "emitted" side of the emission conservation law (see CommentLedger). Every comment
    // that reaches the output is recorded here: ordinary leading/trailing/dangling comments
    // via renderComment, and in-position `/** */` Javadoc via visitJavadoc (which renders
    // through appendCommentLines, bypassing renderComment). A comment in CommentMap's attached
    // set but absent here was silently dropped.
    private final Set<Comment> emitted = Collections.newSetFromMap(new IdentityHashMap<>());

    /// True while rendering a position where an array access must stay glued: the receiver
    /// spine of a `recv.method(...)` call, or the left-hand side of an assignment. Consumed
    /// by visit(ArrayAccess) to suppress its bracket-break alternative — `arr[i].m(...)` and
    /// `arr[i] = ...` keep the access glued and let the trailing call's args / the RHS wrap,
    /// instead of exploding the brackets into the orphan `arr[\n i\n] = ...`. (Without this,
    /// the rest-aware fit check measures the long trailing RHS flat and wrongly concludes even
    /// `arr[i]` doesn't fit.) The bracket break stays available for a standalone access
    /// (`int x = arr[longIdx];`), the last resort when nothing else on the line can wrap.
    /// Saved/restored around the set in visit(MethodInvocation) and visit(Assignment), and
    /// cleared before an array index, so it never leaks into siblings, arguments, RHS values,
    /// or index subexpressions.
    private boolean flatReceiver = false;

    // When true, any comment still unemitted after the whole tree is visited is force-appended
    // at the end of the compilation unit so it is never lost (the production safety net). Off
    // under the STRICT ledger so CI surfaces an un-handled position instead of papering over it.
    private final boolean appendOrphansAtCu;

    public AstToDoc(String source, CommentMap comments) {
        this(source, comments, false);
    }

    public AstToDoc(String source, CommentMap comments, boolean appendOrphansAtCu) {
        this.source = source;
        this.comments = comments;
        this.appendOrphansAtCu = appendOrphansAtCu;
    }

    public AstToDoc(String source) {
        this(source, null, false);
    }

    public AstToDoc() {
        this(null, null, false);
    }

    public Doc result() {
        return result;
    }

    /// The set of comments this visitor rendered into the Doc — the "emitted" side of the
    /// emission conservation law. Compare against {@link CommentMap#attachedComments()} via
    /// {@link CommentLedger} to detect silently dropped comments.
    Set<Comment> emittedComments() {
        return emitted;
    }

    // Attached comments sorted by start position, for range queries in the orphan mop-up.
    // Built lazily on first use because it is only needed when a node actually contains an
    // un-emitted comment.
    private Comment[] sortedComments;

    /// Generic safety net for comments that no construct-specific handler emitted.
    ///
    /// A comment can attach to a deep expression or type node (`( /*c*/ x )`, `(int) /*c*/ y`,
    /// `return /*c*/ z`) whose visitor — and every ancestor's visitor — never consults the
    /// CommentMap. Rather than add an emission site for each such position (the losing game),
    /// every statement and body declaration sweeps the comments that fall strictly inside its
    /// source range and were not emitted by any inner handler, appending them as trailing
    /// comments. Because postVisit runs bottom-up and renderComment records each comment as
    /// emitted, the innermost enclosing statement/declaration claims an orphan and outer ones
    /// skip it. Placement is coarse (end of the enclosing statement) but the comment is never
    /// lost, and on reparse it is an ordinary trailing comment, so a second format is a no-op.
    @Override
    public void postVisit(ASTNode node) {
        if (comments == null) return;
        if (!(node instanceof Statement || node instanceof BodyDeclaration)) return;
        var orphans = orphansWithin(node);
        if (orphans.isEmpty()) return;
        Doc combined = result;
        boolean lastIsLine = false;
        for (var c : orphans) {
            combined = concat(combined, text(" "), renderComment(c));
            lastIsLine = c instanceof LineComment;
        }
        if (lastIsLine) combined = concat(combined, commentBreak());
        result = combined;
    }

    /// Attached comments that start strictly within {@code node}'s source range and have not
    /// yet been emitted, in source order.
    List<Comment> orphansWithin(ASTNode node) {
        if (sortedComments == null) {
            sortedComments = comments.attachedComments().toArray(new Comment[0]);
            java.util.Arrays.sort(sortedComments, Comparator.comparingInt(Comment::getStartPosition));
        }
        int start = node.getStartPosition();
        int end = start + node.getLength();
        List<Comment> res = null;
        for (int i = lowerBound(start); i < sortedComments.length; i++) {
            var c = sortedComments[i];
            int cs = c.getStartPosition();
            if (cs >= end) break;
            if (emitted.contains(c)) continue;
            if (res == null) res = new ArrayList<>();
            res.add(c);
        }
        return res == null ? List.of() : res;
    }

    /// Index of the first comment whose start position is >= {@code pos}.
    private int lowerBound(int pos) {
        int lo = 0;
        int hi = sortedComments.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedComments[mid].getStartPosition() < pos) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    @Override
    public boolean visit(CompilationUnit node) {
        for (var problem : node.getProblems()) {
            throw new RuntimeException("AST has problems: " + problem.getMessage());
        }

        this.compilationUnit = node;
        var parts = new ArrayList<Doc>();

        var package_ = getProperty(node, CompilationUnit.PACKAGE_PROPERTY);
        if (package_ != null) {
            // Leading comments here are the file's license/copyright header (or any
            // block/line comment before `package`); trailing comments sit after the `;`.
            // Both attach to the package declaration in the CommentMap and would be
            // dropped if not emitted — the package's own javadoc lives inside its range
            // and is rendered separately by visit(PackageDeclaration).
            emitLeadingComments(parts, package_);
            package_.accept(this);
            parts.add(result);
            emitTrailingComments(parts, package_);
            parts.add(hardLine());
            parts.add(hardLine());
        }

        var imports = getProperty(node, CompilationUnit.IMPORTS_PROPERTY);
        if (!imports.isEmpty()) {
            var importDecls = new ArrayList<ImportDeclaration>(imports.size());
            for (var imp : imports) importDecls.add((ImportDeclaration) imp);
            var blocks = ImportOrderer.group(importDecls);
            for (int b = 0; b < blocks.size(); b++) {
                if (b > 0) parts.add(hardLine()); // blank line between visual blocks
                for (var imp : blocks.get(b)) {
                    emitLeadingComments(parts, imp);
                    imp.accept(this);
                    parts.add(result);
                    emitTrailingComments(parts, imp);
                    parts.add(hardLine());
                }
            }
            parts.add(hardLine());
        }

        // A `module-info.java` carries a module declaration instead of type declarations.
        // It is rendered by the companion ModuleInfoToDoc, which shares this visitor's
        // CommentMap and emission ledger (so the comment-conservation law sees one set).
        var module = (ModuleDeclaration) getProperty(node, CompilationUnit.MODULE_PROPERTY);
        if (module != null) {
            emitLeadingComments(parts, module);
            parts.add(new ModuleInfoToDoc(this, comments).render(module));
            emitTrailingComments(parts, module);

            // Comments after the module's closing `}` but before EOF attach as dangling of the
            // compilation unit — the module-info analogue of the after-last-type case below.
            var dangling = comments != null ? comments.dangling(node) : List.<Comment>of();
            if (dangling.isEmpty()) {
                parts.add(hardLine());
                parts.add(hardLine());
            } else {
                ASTNode prev = module;
                for (var dc : dangling) {
                    parts.add(hardLine());
                    if (blankLinesBetween(prev, dc) > 0) parts.add(hardLine());
                    parts.add(renderComment(dc));
                    prev = dc;
                }
                parts.add(hardLine());
            }
        }

        var types = getProperty(node, CompilationUnit.TYPES_PROPERTY);
        for (int i = 0; i < types.size(); i++) {
            var type = types.get(i);
            emitLeadingComments(parts, type);

            type.accept(this);
            parts.add(result);

            emitTrailingComments(parts, type);

            boolean lastType = (i == types.size() - 1);
            if (lastType && comments != null) {
                // Comments after the last type but before EOF attach as dangling of the
                // compilation unit. Render them with the same separator pattern the old
                // "trailing-of-last-type" loop used.
                var dangling = comments.dangling(node);
                if (dangling.isEmpty()) {
                    parts.add(hardLine());
                    parts.add(hardLine());
                } else {
                    ASTNode prev = type;
                    for (var dc : dangling) {
                        parts.add(hardLine());
                        if (blankLinesBetween(prev, dc) > 0) parts.add(hardLine());
                        parts.add(renderComment(dc));
                        prev = dc;
                    }
                    parts.add(hardLine());
                }
            } else {
                parts.add(hardLine());
                parts.add(hardLine());
            }
        }

        // A file with no type declarations (e.g. a bare license header) still carries
        // its comments as dangling on the compilation unit. The types loop above never
        // ran, so emit them here — otherwise they'd be silently dropped.
        if (types.isEmpty() && module == null && comments != null) {
            ASTNode prev = null;
            for (var dc : comments.dangling(node)) {
                if (prev != null && blankLinesBetween(prev, dc) > 0) parts.add(hardLine());
                parts.add(renderComment(dc));
                parts.add(hardLine());
                prev = dc;
            }
        }

        // Production safety net: a comment in a position no handler and not even the
        // statement-level mop-up reaches (e.g. inside an import) is force-appended here so it is
        // never lost. orphansWithin(node) over the whole compilation unit returns every comment
        // still unemitted. Off under STRICT so the ledger reports such a gap to CI instead.
        if (appendOrphansAtCu && comments != null) {
            for (var c : orphansWithin(node)) {
                parts.add(hardLine());
                parts.add(renderComment(c));
            }
        }

        result = concat(parts);
        return false;
    }

    /// Emit the leading comments attached to a top-level compilation-unit child (the
    /// package declaration, an import, or a type), each on its own line, preserving
    /// blank lines between consecutive comments and before the node itself.
    private void emitLeadingComments(List<Doc> parts, ASTNode node) {
        var leading = comments != null ? comments.leading(node) : List.<Comment>of();
        if (leading.isEmpty()) return;
        ASTNode prev = null;
        for (var c : leading) {
            if (prev != null && blankLinesBetween(prev, c) > 0) parts.add(hardLine());
            parts.add(renderComment(c));
            parts.add(hardLine());
            prev = c;
        }
        if (blankLinesBetween(leading.getLast(), node) > 0) parts.add(hardLine());
    }

    /// Append the trailing comments of a top-level compilation-unit child to the doc
    /// just emitted for that node (separated by a space), so `import a.B; // note`
    /// keeps its note. Assumes the node's own doc is the last element of `parts`.
    private void emitTrailingComments(List<Doc> parts, ASTNode node) {
        if (!hasTrailingComment(node)) return;
        parts.add(withTrailingComments(parts.removeLast(), node));
    }

    private boolean hasTrailingComment(ASTNode node) {
        return comments != null && !comments.trailing(node).isEmpty();
    }

    /// Return {@code doc} with the node's trailing comments appended, each preceded by a
    /// space. A line comment must be followed by a newline in the rendered output, so a
    /// caller that appends one is responsible for forcing a break after this doc.
    private Doc withTrailingComments(Doc doc, ASTNode node) {
        var trailing = comments != null ? comments.trailing(node) : List.<Comment>of();
        Doc combined = doc;
        for (var tc : trailing) {
            combined = concat(combined, text(" "), renderComment(tc));
        }
        return combined;
    }

    @Override
    public boolean visit(ImportDeclaration node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("import "));

        if (node.isStatic()) {
            parts.add(text("static "));
        }

        parts.add(text(node.getName().getFullyQualifiedName()));

        if (node.isOnDemand()) {
            parts.add(text(".*"));
        }

        parts.add(text(";"));

        result = concat(parts);
        return false;
    }

    // types

    @Override
    public boolean visit(PackageDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, PackageDeclaration.JAVADOC_PROPERTY);

        visitAnnotations(parts, node, PackageDeclaration.ANNOTATIONS_PROPERTY, true);

        parts.add(text("package "));

        var name = getProperty(node, PackageDeclaration.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        parts.add(text(";"));
        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, TypeDeclaration.JAVADOC_PROPERTY);

        boolean atLineStart = visitModifiers(parts, node, TypeDeclaration.MODIFIERS2_PROPERTY);

        // Own-line comments between the modifiers and the `class`/`interface` keyword
        // (`static\n// c\nclass X`) attach as leading comments of the type name.
        emitTypeLeadingComments(parts, node.getName(), atLineStart);

        boolean isInterface = node.isInterface();
        parts.add(text(isInterface ? "interface" : "class"));
        parts.add(space());

        parts.add(text(node.getName().getIdentifier()));

        visitTypeArguments(parts, node, TypeDeclaration.TYPE_PARAMETERS_PROPERTY);

        parts.add(space());

        var superclass = getProperty(node, TypeDeclaration.SUPERCLASS_TYPE_PROPERTY);
        if (superclass != null) {
            parts.add(text("extends "));
            superclass.accept(this);
            parts.add(result);
            parts.add(space());
        }

        // A comment written after the body's `{` on the header line attaches to the last
        // header node (name / superclass / interface / permit) as trailing; collect those so
        // visitSuperInterfaces skips them and visitBodyDeclarations glues them to the `{`.
        var headerNodes = new ArrayList<ASTNode>();
        headerNodes.add(node.getName());
        headerNodes.addAll(getProperty(node, TypeDeclaration.TYPE_PARAMETERS_PROPERTY));
        if (superclass != null) headerNodes.add(superclass);
        headerNodes.addAll(getProperty(node, TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY));
        headerNodes.addAll(getProperty(node, TypeDeclaration.PERMITS_TYPES_PROPERTY));
        var braceTrailing = braceTrailingComments(headerNodes);

        visitSuperInterfaces(parts, node, TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY, braceTrailing);

        visitPermits(parts, node, TypeDeclaration.PERMITS_TYPES_PROPERTY);

        visitBodyDeclarations(parts, node, TypeDeclaration.BODY_DECLARATIONS_PROPERTY, true, braceTrailing);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ImplicitTypeDeclaration node) {
        var parts = new ArrayList<Doc>();

        // An implicit (unnamed) class has no declaration in source: its NAME is synthesized
        // (zero-length, position 0) and MODIFIERS2/JAVADOC are always empty/null — a doc
        // comment before the first member attaches to that member, not the class. So there is
        // no metadata to render; emitting the synthetic name would inject a spurious token.
        visitBodyDeclarations(parts, node, ImplicitTypeDeclaration.BODY_DECLARATIONS_PROPERTY, false);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitBodyDeclarations(parts, node, AnonymousClassDeclaration.BODY_DECLARATIONS_PROPERTY, true);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, RecordDeclaration.JAVADOC_PROPERTY);

        boolean atLineStart = visitModifiers(parts, node, RecordDeclaration.MODIFIERS2_PROPERTY);
        emitTypeLeadingComments(parts, node.getName(), atLineStart);

        parts.add(text("record"));
        parts.add(space());
        parts.add(text(node.getName().getIdentifier()));

        visitTypeArguments(parts, node, RecordDeclaration.TYPE_PARAMETERS_PROPERTY);

        var components = getProperty(node, RecordDeclaration.RECORD_COMPONENTS_PROPERTY);
        if (components.isEmpty()) {
            parts.add(text("()"));
        } else {
            // The header formats like a method's parameter list, including comments: a
            // comment after `(` attaches to the record name, one before `)` is dangling,
            // and a component carries its own comments (`int a, // note`).
            var afterOpen = comments != null ? comments.trailing(node.getName()) : List.<Comment>of();
            // dangling(node) lumps a header-paren comment together with comments inside the
            // body braces — a record's body declarations hang directly off the node, with no
            // Block to enclose them. Keep only the ones before the body's `{` for the header;
            // the rest are emitted by visitBodyDeclarations, so they aren't duplicated.
            var beforeClose = recordHeaderDangling(node, components);

            var componentDocs = new ArrayList<Doc>();
            for (var component : components) {
                component.accept(this);
                componentDocs.add(result);
            }

            if (!afterOpen.isEmpty() || !beforeClose.isEmpty() || argumentsHaveComments(components)) {
                parts.add(argsWithComments(components, componentDocs, afterOpen, beforeClose));
            } else {
                parts.add(group(concat(
                        text("("),
                        indent(concat(
                                softLine(),
                                join(concat(text(","), line()), componentDocs)
                        )),
                        softLine(),
                        text(")")
                )));
            }
        }

        parts.add(space());

        visitSuperInterfaces(parts, node, RecordDeclaration.SUPER_INTERFACE_TYPES_PROPERTY);

        visitBodyDeclarations(parts, node, RecordDeclaration.BODY_DECLARATIONS_PROPERTY, true);

        result = concat(parts);
        return false;
    }

    /// Dangling comments of a record that belong to the header — i.e. those before the body's
    /// opening `{`. A record's body declarations attach directly to the RecordDeclaration (no
    /// enclosing Block), so {@code comments.dangling(node)} conflates comments inside the body
    /// braces with a comment before the header's `)`. Splitting on the brace position keeps the
    /// header from re-emitting (and thus duplicating) the body's dangling comments.
    private List<Comment> recordHeaderDangling(RecordDeclaration node, List<ASTNode> components) {
        if (comments == null) return List.of();
        var dangling = comments.dangling(node);
        if (dangling.isEmpty()) return dangling;

        int headerEnd = node.getName().getStartPosition() + node.getName().getLength();
        for (var tp : getProperty(node, RecordDeclaration.TYPE_PARAMETERS_PROPERTY)) {
            headerEnd = Math.max(headerEnd, tp.getStartPosition() + tp.getLength());
        }
        for (var comp : components) {
            headerEnd = Math.max(headerEnd, comp.getStartPosition() + comp.getLength());
        }
        for (var si : getProperty(node, RecordDeclaration.SUPER_INTERFACE_TYPES_PROPERTY)) {
            headerEnd = Math.max(headerEnd, si.getStartPosition() + si.getLength());
        }
        int bodyBrace = source.indexOf('{', headerEnd);
        if (bodyBrace < 0) return dangling;

        var header = new ArrayList<Comment>();
        for (var c : dangling) {
            if (c.getStartPosition() < bodyBrace) header.add(c);
        }
        return header;
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, EnumDeclaration.JAVADOC_PROPERTY);

        boolean atLineStart = visitModifiers(parts, node, EnumDeclaration.MODIFIERS2_PROPERTY);
        emitTypeLeadingComments(parts, node.getName(), atLineStart);

        parts.add(text("enum"));
        parts.add(space());
        parts.add(text(node.getName().getIdentifier()));
        parts.add(space());

        visitSuperInterfaces(parts, node, EnumDeclaration.SUPER_INTERFACE_TYPES_PROPERTY);

        var constants = getProperty(node, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        var body = getProperty(node, EnumDeclaration.BODY_DECLARATIONS_PROPERTY);

        if (constants.isEmpty() && body.isEmpty()) {
            parts.add(text("{}"));
            result = concat(parts);
            return false;
        }

        parts.add(text("{"));

        var innerParts = new ArrayList<Doc>();
        ASTNode prevItem = null;

        // Comments after the last constant but before the `;` separator attach as leading
        // comments of the first body member (or, with no body, as dangling on the enum) yet
        // sit above the `;` in source — render them in the constant section so they stay there.
        var preSemi = enumPreSemicolonComments(node, constants, body);

        if (!constants.isEmpty()) {
            innerParts.add(hardLine());
            for (var constant : constants) {
                // Own-line comments before the constant attach as its leading comments.
                var leading = comments != null ? comments.leading(constant) : List.<Comment>of();
                for (var lc : leading) {
                    if (prevItem != null) {
                        innerParts.add(hardLine());
                        if (blankLinesBetween(prevItem, lc) > 0) innerParts.add(hardLine());
                    }
                    innerParts.add(renderComment(lc));
                    prevItem = lc;
                }
                if (prevItem != null) {
                    innerParts.add(hardLine());
                    if (blankLinesBetween(prevItem, constant) > 0) innerParts.add(hardLine());
                }

                constant.accept(this);
                // Every constant gets a trailing comma, including the last: adding a constant
                // stays a one-line diff, and the `;` that may follow the constants sits on its
                // own line rather than gluing to the last constant.
                var constantDoc = concat(result, text(","));
                prevItem = constant;

                // A trailing comment sits after the comma (`TROLL, // not actually used`).
                var trailing = comments != null ? comments.trailing(constant) : List.<Comment>of();
                for (var tc : trailing) {
                    constantDoc = concat(constantDoc, text(" "), renderComment(tc));
                    prevItem = tc;
                }
                innerParts.add(constantDoc);
            }

            // Comments between the last constant and the `;` (e.g. `// ADD TAGS HERE`).
            for (var c : preSemi) {
                innerParts.add(hardLine());
                if (prevItem != null && blankLinesBetween(prevItem, c) > 0) innerParts.add(hardLine());
                innerParts.add(renderComment(c));
                prevItem = c;
            }

            // A body after the constants is separated by a `;` on its own line.
            if (!body.isEmpty()) {
                innerParts.add(hardLine());
                innerParts.add(text(";"));
            }
        }

        if (!body.isEmpty()) {
            if (!constants.isEmpty()) {
                innerParts.add(hardLine());
            }
            for (int i = 0; i < body.size(); i++) {
                var member = body.get(i);
                // Own-line comments before a member attach as leading comments (skipping any
                // already rendered above the `;`).
                for (var lc : (comments != null ? comments.leading(member) : List.<Comment>of())) {
                    if (preSemi.contains(lc)) continue;
                    innerParts.add(hardLine());
                    innerParts.add(renderComment(lc));
                }
                innerParts.add(hardLine());
                if (i > 0 && blankLinesBetween(body.get(i - 1), member) > 0) {
                    innerParts.add(hardLine());
                }
                member.accept(this);
                var memberDoc = result;
                for (var tc : (comments != null ? comments.trailing(member) : List.<Comment>of())) {
                    memberDoc = concat(memberDoc, text(" "), renderComment(tc));
                }
                innerParts.add(memberDoc);
                prevItem = member;
            }
        }

        // Dangling comments at the end of the body — commented-out code before the `}`.
        if (comments != null) {
            for (var dc : comments.dangling(node)) {
                if (preSemi.contains(dc)) continue;
                innerParts.add(hardLine());
                if (prevItem != null && blankLinesBetween(prevItem, dc) > 0) innerParts.add(hardLine());
                innerParts.add(renderComment(dc));
                prevItem = dc;
            }
        }

        parts.add(indent(concat(innerParts)));
        parts.add(hardLine());
        parts.add(text("}"));

        result = concat(parts);
        return false;
    }

    /// Comments sitting between the last enum constant and the `;` separator. They attach as
    /// leading comments of the first body member (or, with an empty body, as dangling on the
    /// enum), but in source they're above the `;`, so they're rendered in the constant section.
    private List<Comment> enumPreSemicolonComments(EnumDeclaration node, List<ASTNode> constants,
            List<ASTNode> body) {
        if (comments == null || constants.isEmpty()) return List.of();
        var lastConstant = constants.get(constants.size() - 1);
        int lastEnd = lastConstant.getStartPosition() + lastConstant.getLength();
        int semiPos = source.indexOf(';', lastEnd);
        if (semiPos < 0) return List.of();
        var candidates = body.isEmpty() ? comments.dangling(node) : comments.leading(body.get(0));
        var res = new ArrayList<Comment>();
        for (var c : candidates) {
            if (c.getStartPosition() < semiPos) res.add(c);
        }
        return res;
    }

    @Override
    public boolean visit(EnumConstantDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, EnumConstantDeclaration.JAVADOC_PROPERTY);

        visitModifiers(parts, node, EnumConstantDeclaration.MODIFIERS2_PROPERTY);

        parts.add(text(node.getName().getIdentifier()));

        var arguments = getProperty(node, EnumConstantDeclaration.ARGUMENTS_PROPERTY);
        if (!arguments.isEmpty()) {
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
            }
            parts.add(group(concat(
                    text("("),
                    indent(concat(
                            softLine(),
                            join(concat(text(","), line()), argDocs)
                    )),
                    softLine(),
                    text(")")
            )));
        }

        var anonymousClass = getProperty(node, EnumConstantDeclaration.ANONYMOUS_CLASS_DECLARATION_PROPERTY);
        if (anonymousClass != null) {
            parts.add(space());
            anonymousClass.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, AnnotationTypeDeclaration.JAVADOC_PROPERTY);

        boolean atLineStart = visitModifiers(parts, node, AnnotationTypeDeclaration.MODIFIERS2_PROPERTY);
        emitTypeLeadingComments(parts, node.getName(), atLineStart);

        parts.add(text("@interface"));
        parts.add(space());
        parts.add(text(node.getName().getIdentifier()));
        parts.add(space());

        visitBodyDeclarations(parts, node, AnnotationTypeDeclaration.BODY_DECLARATIONS_PROPERTY, true);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(AnnotationTypeMemberDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, AnnotationTypeMemberDeclaration.JAVADOC_PROPERTY);

        boolean atLineStart = visitModifiers(parts, node, AnnotationTypeMemberDeclaration.MODIFIERS2_PROPERTY);

        var type = getProperty(node, AnnotationTypeMemberDeclaration.TYPE_PROPERTY);
        // Own-line comments between the member's javadoc/modifiers and its type (e.g. an
        // implementation-notes block) attach as leading comments of the type.
        emitTypeLeadingComments(parts, type, atLineStart);
        type.accept(this);
        parts.add(result);
        parts.add(space());

        parts.add(text(node.getName().getIdentifier()));
        parts.add(text("()"));

        var defaultValue = getProperty(node, AnnotationTypeMemberDeclaration.DEFAULT_PROPERTY);
        if (defaultValue != null) {
            parts.add(text(" default "));
            defaultValue.accept(this);
            parts.add(result);
        }

        parts.add(text(";"));

        result = concat(parts);
        return false;
    }

    private void visitSuperInterfaces(List<Doc> parts, ASTNode node, ChildListPropertyDescriptor property) {
        visitSuperInterfaces(parts, node, property, List.of());
    }

    private void visitSuperInterfaces(List<Doc> parts, ASTNode node, ChildListPropertyDescriptor property,
            List<Comment> braceTrailing) {
        var interfaces = getProperty(node, property);
        if (interfaces.isEmpty()) return;

        var keyword = node instanceof TypeDeclaration td && td.isInterface()
                ? "extends" : "implements";
        var ifaceDocs = new ArrayList<Doc>();
        for (var iface : interfaces) {
            iface.accept(this);
            ifaceDocs.add(result);
        }
        // Comments among the interface list (section markers like `// Lifecycle Callbacks`,
        // or a comment before the keyword) attach as leading comments of the interfaces and
        // would otherwise be dropped. Render one per line, emitting them. A comment that
        // actually sits after the body's `{` (`implements Foo { // note`) attaches to the
        // last interface as trailing but belongs on the brace — it's in braceTrailing and is
        // emitted there, so it's excluded here.
        if (interfaceListHasComments(interfaces, braceTrailing)) {
            parts.add(text(keyword));
            var inner = new ArrayList<Doc>();
            int n = interfaces.size();
            for (int i = 0; i < n; i++) {
                var iface = interfaces.get(i);
                for (var lc : comments.leading(iface)) {
                    inner.add(hardLine());
                    inner.add(renderComment(lc));
                }
                inner.add(hardLine());
                inner.add(ifaceDocs.get(i));
                if (i < n - 1) inner.add(text(","));
                for (var tc : comments.trailing(iface)) {
                    if (braceTrailing.contains(tc)) continue;
                    inner.add(text(" "));
                    inner.add(renderComment(tc));
                }
            }
            parts.add(indent(concat(inner)));
            parts.add(space());
        } else {
            // Overlong list breaks vertically, one interface per line, like `throws`.
            parts.add(group(concat(
                    text(keyword),
                    indent(concat(
                            line(),
                            join(concat(text(","), line()), ifaceDocs)
                    ))
            )));
            parts.add(space());
        }
    }

    private void visitPermits(List<Doc> parts, ASTNode node, ChildListPropertyDescriptor property) {
        var permits = getProperty(node, property);
        if (permits.isEmpty()) return;

        var permitDocs = new ArrayList<Doc>();
        for (var permit : permits) {
            permit.accept(this);
            permitDocs.add(result);
        }
        // Permits entries are bare class names, and sealed hierarchies force the full
        // enumeration — so an overlong list fill-wraps (as many per line as fit) rather
        // than one-per-line, which would bloat the header for no per-entry structure.
        parts.add(group(concat(
                text("permits"),
                indent(concat(
                        line(),
                        fillJoin(concat(text(","), line()), permitDocs)
                ))
        )));
        parts.add(space());
    }

    private void visitBodyDeclarations(
            List<Doc> parts,
            ASTNode node,
            ChildListPropertyDescriptor property,
            boolean withBraces
    ) {
        visitBodyDeclarations(parts, node, property, withBraces, List.of());
    }

    /// @param openBraceTrailing comments written after the opening `{` on the same line
    ///        (`class X { // note`); rendered glued to the brace.
    private void visitBodyDeclarations(
            List<Doc> parts,
            ASTNode node,
            ChildListPropertyDescriptor property,
            boolean withBraces,
            List<Comment> openBraceTrailing
    ) {
        var body = getProperty(node, property);
        var dangling = comments != null ? comments.dangling(node) : List.<Comment>of();

        if (body.isEmpty() && dangling.isEmpty() && openBraceTrailing.isEmpty()) {
            if (withBraces) parts.add(text("{}"));
            return;
        }

        if (withBraces) {
            Doc openBrace = text("{");
            for (var c : openBraceTrailing) openBrace = concat(openBrace, text(" "), renderComment(c));
            parts.add(openBrace);
        }

        // First thing to be rendered after the open brace — used to preserve a blank line
        // that existed between `{` and the first body content in the source. Accounts for
        // a leading comment on the first declaration sitting before the declaration itself.
        ASTNode firstItem = null;
        int firstStart = Integer.MAX_VALUE;
        if (!body.isEmpty()) {
            var firstDecl = body.getFirst();
            var firstDeclLeading = comments != null ? comments.leading(firstDecl) : List.<Comment>of();
            ASTNode candidate = firstDeclLeading.isEmpty() ? firstDecl : firstDeclLeading.getFirst();
            firstItem = candidate;
            firstStart = candidate.getStartPosition();
        }
        if (!dangling.isEmpty() && dangling.getFirst().getStartPosition() < firstStart) {
            firstItem = dangling.getFirst();
        }
        ASTNode lastItem;

        var bodyParts = new ArrayList<Doc>();
        if (withBraces) {
            bodyParts.add(hardLine());
            if (firstItem != null && blankLinesAfterOpenBrace(node, firstItem) > 0) {
                bodyParts.add(hardLine());
            }
        }

        ASTNode prevItem = null;
        for (var decl : body) {
            var leading = comments != null ? comments.leading(decl) : List.<Comment>of();
            for (var lc : leading) {
                if (prevItem != null) {
                    bodyParts.add(hardLine());
                    if (blankLinesBetween(prevItem, lc) > 0) bodyParts.add(hardLine());
                }
                bodyParts.add(renderComment(lc));
                prevItem = lc;
            }
            if (prevItem != null) {
                bodyParts.add(hardLine());
                if (blankLinesBetween(prevItem, decl) > 0) bodyParts.add(hardLine());
            }
            decl.accept(this);
            bodyParts.add(result);
            prevItem = decl;

            var trailing = comments != null ? comments.trailing(decl) : List.<Comment>of();
            if (!trailing.isEmpty()) {
                var prevDoc = bodyParts.removeLast();
                Doc combined = prevDoc;
                for (var tc : trailing) {
                    combined = concat(combined, text(" "), renderComment(tc));
                    prevItem = tc;
                }
                bodyParts.add(combined);
            }
        }
        for (var dc : dangling) {
            if (prevItem != null) {
                bodyParts.add(hardLine());
                if (blankLinesBetween(prevItem, dc) > 0) bodyParts.add(hardLine());
            }
            bodyParts.add(renderComment(dc));
            prevItem = dc;
        }
        lastItem = prevItem;

        var bodyDoc = concat(bodyParts);
        parts.add(withBraces ? indent(bodyDoc) : bodyDoc);

        if (withBraces) {
            parts.add(hardLine());
            if (lastItem != null && blankLinesBeforeCloseBrace(node, lastItem) > 0) {
                parts.add(hardLine());
            }
            parts.add(text("}"));
        }
    }


    private void visitAnnotations(List<Doc> parts, ASTNode node, ChildListPropertyDescriptor property, boolean forceNewline) {
        var annotations = getProperty(node, property);
        for (var annotation : annotations) {
            annotation.accept(this);
            parts.add(result);
            parts.add(forceNewline ? hardLine() : space());
        }
    }

    // type members

    @Override
    public boolean visit(SingleVariableDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitModifiers(parts, node, SingleVariableDeclaration.MODIFIERS2_PROPERTY);

        var type = getProperty(node, SingleVariableDeclaration.TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);

        if (node.isVarargs()) {
            // Annotations on the varargs dimension sit between the element type and `...`
            // (`String @Nullable ... args`) and need a space separating them from the type.
            if (!getProperty(node, SingleVariableDeclaration.VARARGS_ANNOTATIONS_PROPERTY).isEmpty()) {
                parts.add(space());
            }
            visitAnnotations(parts, node, SingleVariableDeclaration.VARARGS_ANNOTATIONS_PROPERTY, false);
            parts.add(text("..."));
        }

        parts.add(space());
        parts.add(text(node.getName().getIdentifier()));

        // C-style trailing dimensions on a parameter (`void m(int a[])`).
        appendExtraDimensions(parts, node, SingleVariableDeclaration.EXTRA_DIMENSIONS2_PROPERTY);

        // This is currently never used in java
        var initializer = getProperty(node, SingleVariableDeclaration.INITIALIZER_PROPERTY);
        if (initializer != null) throw new UnsupportedOperationException("initializer on SingleVariableDeclaration");

        // Wrap in a group so the breakable separators visitModifiers emits after leading
        // declaration annotations (see there) resolve per-parameter: inline when the
        // parameter fits, each annotation on its own line when it doesn't.
        result = group(concat(parts));
        return false;
    }

    @Override
    public boolean visit(Initializer node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, Initializer.JAVADOC_PROPERTY);

        visitModifiers(parts, node, Initializer.MODIFIERS2_PROPERTY);

        // TODO: if the body is empty, should we just delete this entire node?
        var body = getProperty(node, Initializer.BODY_PROPERTY);
        if (body != null) {
            body.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, FieldDeclaration.JAVADOC_PROPERTY);

        boolean atLineStart = visitModifiers(parts, node, FieldDeclaration.MODIFIERS2_PROPERTY);

        var type = getProperty(node, FieldDeclaration.TYPE_PROPERTY);
        // Own-line comments between the modifiers and the field type attach as leading
        // comments of the type.
        emitTypeLeadingComments(parts, type, atLineStart);
        type.accept(this);
        parts.add(result);
        parts.add(space());

        var fragments = getProperty(node, FieldDeclaration.FRAGMENTS_PROPERTY);
        var fragDocs = new ArrayList<Doc>();
        for (var frag : fragments) {
            frag.accept(this);
            fragDocs.add(result);
        }
        parts.add(join(concat(text(","), space()), fragDocs));
        parts.add(text(";"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, MethodDeclaration.JAVADOC_PROPERTY);

        boolean atLineStart = visitModifiers(parts, node, MethodDeclaration.MODIFIERS2_PROPERTY);

        boolean hasTypeParams = !getProperty(node, MethodDeclaration.TYPE_PARAMETERS_PROPERTY).isEmpty();
        visitTypeArguments(parts, node, MethodDeclaration.TYPE_PARAMETERS_PROPERTY);
        if (hasTypeParams) {
            parts.add(space());
            atLineStart = false;
        }

        if (!node.isConstructor()) {
            var returnType = getProperty(node, MethodDeclaration.RETURN_TYPE2_PROPERTY);
            // Own-line comments between the modifiers and the return type (e.g. a
            // commented-out alternative annotation) attach as leading comments of the type.
            emitTypeLeadingComments(parts, returnType, atLineStart);
            returnType.accept(this);
            parts.add(result);
            parts.add(space());
            atLineStart = false;
        }

        // Own-line comments between the return type (or modifiers, for a constructor) and the
        // method name attach as leading comments of the name.
        emitTypeLeadingComments(parts, node.getName(), atLineStart);
        parts.add(text(node.getName().getIdentifier()));

        // A compact canonical constructor (`Range { ... }`) has no parameter list at all —
        // its parameters are implied by the record header — so skip the parens entirely.
        if (!node.isCompactConstructor()) {
            // The parameter list formats like a call's argument list, including comments: a
            // comment after `(` attaches to the method name, one before `)` (an abstract
            // method) is dangling, and a parameter carries its own comments (`m(int a, //\n b)`).
            var params = getProperty(node, MethodDeclaration.PARAMETERS_PROPERTY);
            var afterOpen = comments != null ? comments.trailing(node.getName()) : List.<Comment>of();
            var beforeClose = comments != null ? comments.dangling(node) : List.<Comment>of();

            // An explicit receiver parameter (`void m(Outer Outer.this)`) is the first thing
            // inside the parens but is not part of PARAMETERS; render it as a leading entry.
            var receiverDoc = receiverParamDoc(node);

            var paramDocs = new ArrayList<Doc>();
            if (receiverDoc != null) paramDocs.add(receiverDoc);
            for (var param : params) {
                param.accept(this);
                paramDocs.add(result);
            }

            if (paramDocs.isEmpty()) {
                formatArguments(parts, List.of(), afterOpen, beforeClose);
            } else if (receiverDoc == null
                    && (!afterOpen.isEmpty() || !beforeClose.isEmpty() || argumentsHaveComments(params))) {
                parts.add(argsWithComments(params, paramDocs, afterOpen, beforeClose));
            } else {
                parts.add(group(concat(
                        text("("),
                        indent(concat(
                                softLine(),
                                join(concat(text(","), line()), paramDocs)
                        )),
                        softLine(),
                        text(")")
                )));
            }
        }

        // C-style trailing dimensions on the return type (`int m()[]`), after the parens.
        appendExtraDimensions(parts, node, MethodDeclaration.EXTRA_DIMENSIONS2_PROPERTY);

        var thrownTypes = getProperty(node, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
        if (!thrownTypes.isEmpty()) {
            parts.add(space());
            var throwDocs = new ArrayList<Doc>();
            for (var thrown : thrownTypes) {
                thrown.accept(this);
                throwDocs.add(result);
            }
            parts.add(group(concat(
                    text("throws"),
                    indent(concat(
                            line(),
                            join(concat(text(","), line()), throwDocs)
                    ))
            )));
        }

        var body = getProperty(node, MethodDeclaration.BODY_PROPERTY);
        if (body != null) {
            parts.add(space());

            body.accept(this);
            parts.add(result);
        } else {
            parts.add(text(";"));
        }

        result = concat(parts);
        return false;
    }

    /// Append C-style trailing array dimensions that sit after a variable name, parameter, or
    /// a method's parameter list (`int a[]`, `void m(int a[])`, `int m()[]`). Each Dimension
    /// renders as `[]` (carrying any type annotations); an annotated dimension gets a leading
    /// space so `a @Foo []` reads correctly, while the common unannotated `a[]` stays glued.
    private void appendExtraDimensions(List<Doc> parts, ASTNode node, ChildListPropertyDescriptor property) {
        for (var dim : getProperty(node, property)) {
            if (!getProperty(dim, Dimension.ANNOTATIONS_PROPERTY).isEmpty()) parts.add(space());
            dim.accept(this);
            parts.add(result);
        }
    }

    /// Render an explicit receiver parameter (`Outer Outer.this`, or `Foo this` for a
    /// top-level method) that leads the parameter list, or null when the method has none.
    /// The qualifier is present only for an inner-class receiver.
    private Doc receiverParamDoc(MethodDeclaration node) {
        var receiverType = getProperty(node, MethodDeclaration.RECEIVER_TYPE_PROPERTY);
        if (receiverType == null) return null;
        var parts = new ArrayList<Doc>();
        receiverType.accept(this);
        parts.add(result);
        parts.add(space());
        var qualifier = getProperty(node, MethodDeclaration.RECEIVER_QUALIFIER_PROPERTY);
        if (qualifier != null) {
            qualifier.accept(this);
            parts.add(result);
            parts.add(text("."));
        }
        parts.add(text("this"));
        return concat(parts);
    }

    /// Returns whether rendering ended at the start of a line (true when there are no
    /// modifiers, or the last one was a declaration annotation / a modifier with a trailing
    /// line comment). Callers use this to place a comment that leads the declaration's type.
    boolean visitModifiers(List<Doc> doc, ASTNode node, ChildListPropertyDescriptor property) {
        var modifiers = getProperty(node, property);
        if (modifiers.isEmpty()) return true;

        boolean inline = node instanceof SingleVariableDeclaration
                || node instanceof VariableDeclarationExpression
                || node instanceof VariableDeclarationStatement;

        // A parameter or record component (SingleVariableDeclaration) keeps its leading
        // annotations on the same line when it fits, but lets them break onto their own
        // lines when the declaration is too wide. The declaration is wrapped in a group (see
        // visit(SingleVariableDeclaration)), so the line() separator emitted below resolves
        // per-declaration: a flat group renders the line() as a space, a broken one as a
        // newline. This avoids splitting inside an annotation's own argument list (`@Foo(\n
        // ...\n) @Bar long x`) when all that was needed was a break between annotations.
        boolean breakableAnnotations = node instanceof SingleVariableDeclaration;

        boolean seenKeyword = false;
        boolean atLineStart = true; // the container starts us on a fresh line
        for (int i = 0; i < modifiers.size(); i++) {
            var modifier = modifiers.get(i);

            // Own-line comments among the modifier list (`@Tag\n// c\n@Test`,
            // `static\n// c\npublic class`) attach as leading comments of this modifier and
            // would otherwise be dropped. Emit them each on their own line. A leading comment
            // of the FIRST modifier can't occur — it would fall outside the declaration and
            // attach to the enclosing context instead — so there is always a modifier before
            // us to break away from.
            var leading = comments != null ? comments.leading(modifier) : List.<Comment>of();
            if (!leading.isEmpty()) {
                if (!atLineStart) doc.add(hardLine());
                for (var lc : leading) {
                    doc.add(renderComment(lc));
                    doc.add(hardLine());
                }
                atLineStart = true;
            }

            if (modifier instanceof Modifier) {
                seenKeyword = true;
            }
            modifier.accept(this);
            doc.add(result);

            // Trailing comments (`@AutoClose // <1>`, `@Deprecated //`, `private /* final */`).
            var trailing = comments != null ? comments.trailing(modifier) : List.<Comment>of();
            boolean trailingLine = false;
            for (var tc : trailing) {
                doc.add(space());
                doc.add(renderComment(tc));
                if (tc instanceof LineComment) trailingLine = true;
            }

            // Separator to the next modifier (or the type/body that follows). A declaration
            // annotation — one applied before any keyword modifier — goes on its own line;
            // a type-use annotation (after a keyword, e.g. `private static @Nullable`),
            // an inline annotation, and keyword modifiers are space-separated. A trailing
            // line comment forces the next item onto the next line so it isn't swallowed.
            // On a parameter/record component a leading declaration annotation gets a
            // breakable separator instead of a hard one: inline while the declaration fits,
            // its own line once it overflows.
            boolean leadingAnnotation = modifier instanceof Annotation && !seenKeyword;
            boolean declAnnotation = leadingAnnotation && !inline;
            if (trailingLine || declAnnotation) {
                doc.add(hardLine());
                atLineStart = true;
            } else if (leadingAnnotation && breakableAnnotations) {
                doc.add(line());
                atLineStart = false;
            } else {
                doc.add(space());
                atLineStart = false;
            }
        }
        return atLineStart;
    }

    /// Emit own-line comments that sit between a declaration's modifier list and its type
    /// (`@EnabledOnJre(...)\n// alt:\n// @EnabledOnJre(value = ...)\nvoid m()`). They attach
    /// as leading comments of the type and are otherwise dropped. {@code atLineStart} is the
    /// line state left by visitModifiers; a hardLine is inserted first only when needed.
    private void emitTypeLeadingComments(List<Doc> parts, ASTNode type, boolean atLineStart) {
        var leading = comments != null ? comments.leading(type) : List.<Comment>of();
        if (leading.isEmpty()) return;
        if (!atLineStart) parts.add(hardLine());
        for (var lc : leading) {
            parts.add(renderComment(lc));
            parts.add(hardLine());
        }
    }

    @Override
    public boolean visit(Modifier node) {
        // Bare keyword; visitModifiers adds the separating space (so it can swap in a
        // line break when a comment follows).
        result = text(node.getKeyword().toString());
        return false;
    }

    @Override
    public boolean visit(TypeParameter node) {
        var parts = new ArrayList<Doc>();

        // A type parameter's only modifiers are annotations (`<@NonNull T>`).
        visitAnnotations(parts, node, TypeParameter.MODIFIERS_PROPERTY, false);

        parts.add(text(node.getName().getIdentifier()));

        var bounds = getProperty(node, TypeParameter.TYPE_BOUNDS_PROPERTY);
        if (!bounds.isEmpty()) {
            parts.add(text(" extends "));
            var boundDocs = new ArrayList<Doc>();
            for (var bound : bounds) {
                bound.accept(this);
                boundDocs.add(result);
            }
            parts.add(join(text(" & "), boundDocs));
        }

        result = concat(parts);
        return false;
    }

    // statements

    @Override
    public boolean visit(EmptyStatement node) {
        // Blocks remove redundant empty statements, but a control-flow or labeled body
        // must retain its semicolon so the following statement does not become its body.
        result = text(";");
        return false;
    }

    @Override
    public boolean visit(Block node) {
        var stmts = new ArrayList<>(getProperty(node, Block.STATEMENTS_PROPERTY));
        stmts.removeIf(stmt -> stmt instanceof EmptyStatement);

        var dangling = comments != null ? comments.dangling(node) : List.<Comment>of();

        if (stmts.isEmpty() && dangling.isEmpty()) {
            result = text("{}");
            return false;
        }

        // A leading comment of the first statement that originally sat on the same line
        // as the opening brace stays glued to the brace ("{ // ...").
        Doc openBraceTrailing = null;
        Comment skipFirstLeading = null;
        if (!stmts.isEmpty() && comments != null) {
            var firstLeading = comments.leading(stmts.getFirst());
            if (!firstLeading.isEmpty() && isOnSameLineAsOpenBrace(node, firstLeading.getFirst())) {
                openBraceTrailing = renderComment(firstLeading.getFirst());
                skipFirstLeading = firstLeading.getFirst();
            }
        }

        var parts = new ArrayList<Doc>();
        ASTNode prevItem = null;
        for (var stmt : stmts) {
            var leading = comments != null ? comments.leading(stmt) : List.<Comment>of();
            for (var lc : leading) {
                if (lc == skipFirstLeading) continue;
                if (prevItem != null && blankLinesBetween(prevItem, lc) > 0) parts.add(hardLine());
                parts.add(hardLine());
                parts.add(renderComment(lc));
                prevItem = lc;
            }
            if (prevItem != null && blankLinesBetween(prevItem, stmt) > 0) parts.add(hardLine());
            parts.add(hardLine());
            stmt.accept(this);
            parts.add(result);
            prevItem = stmt;

            var trailing = comments != null ? comments.trailing(stmt) : List.<Comment>of();
            if (!trailing.isEmpty()) {
                var prevDoc = parts.removeLast();
                Doc combined = prevDoc;
                boolean lastIsLine = false;
                for (var tc : trailing) {
                    combined = concat(combined, text(" "), renderComment(tc));
                    lastIsLine = tc instanceof LineComment;
                    prevItem = tc;
                }
                // A trailing line comment must end its line. CommentBreak forces that and
                // absorbs the loop's own separator hardLine (or the closing brace's), so the
                // comment ends exactly one line without stacking into a blank line.
                if (lastIsLine) combined = concat(combined, commentBreak());
                parts.add(combined);
            }
        }
        for (var dc : dangling) {
            if (prevItem != null && blankLinesBetween(prevItem, dc) > 0) parts.add(hardLine());
            parts.add(hardLine());
            parts.add(renderComment(dc));
            prevItem = dc;
        }

        var openBrace = openBraceTrailing != null
                ? concat(text("{"), text(" "), openBraceTrailing)
                : text("{");
        result = concat(openBrace,
                        indent(concat(parts)),
                        hardLine(),
                        text("}"));
        return false;
    }

    @Override
    public boolean visit(TypeDeclarationStatement node) {
        var declaration = getProperty(node, TypeDeclarationStatement.DECLARATION_PROPERTY);
        declaration.accept(this);
        return false;
    }

    @Override
    public boolean visit(IfStatement node) {
        var expression = getProperty(node, IfStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        var condDoc = result;

        var thenStmt = getProperty(node, IfStatement.THEN_STATEMENT_PROPERTY);
        var ifAndThen = headerWithBody(concat(text("if ("), condDoc, text(")")), expression, thenStmt);

        var parts = new ArrayList<Doc>();
        parts.add(ifAndThen);

        var elseStmt = getProperty(node, IfStatement.ELSE_STATEMENT_PROPERTY);
        if (elseStmt != null) {
            // An own-line comment before `else` attaches as a leading comment of the else
            // statement. When present it forces `else` onto its own line (after the comment)
            // rather than gluing `} else`; otherwise `} else` stays on the same line when the
            // then-body uses braces, and `else` moves to its own line for a non-braced then.
            var elseLeading = comments != null ? comments.leading(elseStmt) : List.<Comment>of();
            if (!elseLeading.isEmpty()) {
                for (var lc : elseLeading) {
                    parts.add(hardLine());
                    parts.add(renderComment(lc));
                }
                parts.add(hardLine());
                parts.add(text("else "));
            } else if (thenStmt instanceof Block) {
                parts.add(text(" else "));
            } else {
                parts.add(hardLine());
                parts.add(text("else "));
            }
            elseStmt.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    /// Combines a statement header (e.g. `if (cond)`, `while (cond)`, `for (...)`)
    /// with its body, preferring to break BEFORE the body rather than inside the
    /// header when the whole construct doesn't fit on a line.
    ///
    /// For braced bodies (`Block`), `header { ... }` always glues because the
    /// opening brace and the `)` belong on the same line by convention.
    ///
    /// For braceless single-statement bodies, the whole thing is wrapped in one
    /// group with a soft `line()` between the header and the body. The printer
    /// then picks, in priority order:
    ///   1. flat — `header body;` on one line, if it fits;
    ///   2. body break — `header\n    body;`, leaving the header intact;
    ///   3. (last resort) the header's own inner groups break too, if the
    ///      header alone is still too long for one line.
    /// Without this group, the header's nested groups (e.g. an `instanceof` or
    /// `&&` chain) would try to break first and produce a hybrid where the
    /// header chops and the body stays glued to a now-broken `)`.
    private Doc headerWithBody(Doc header, ASTNode body) {
        return headerWithBody(header, null, body);
    }

    /// @param headerNode the node whose trailing comment sits between the header and the body
    ///                   (e.g. the condition of an `if`/`while`), or null if none applies.
    private Doc headerWithBody(Doc header, ASTNode headerNode, ASTNode body) {
        body.accept(this);
        var bodyDoc = result;
        if (body instanceof Block) {
            return concat(header, text(" "), bodyDoc);
        }

        // A braceless body can carry comments in the gap after the `)`: a trailing comment on
        // the condition (`if (cond) // note`) stays on the header line, and an own-line
        // comment before the body (`if (cond)\n // note\n stmt;`) sits on its own line above
        // it. Either forces the body onto its own line — a line comment must end its line.
        var headerTrailing = (comments != null && headerNode != null)
                ? comments.trailing(headerNode) : List.<Comment>of();
        var bodyLeading = comments != null ? comments.leading(body) : List.<Comment>of();

        Doc head = header;
        for (var tc : headerTrailing) head = concat(head, text(" "), renderComment(tc));

        if (headerTrailing.isEmpty() && bodyLeading.isEmpty()) {
            return group(concat(head, indent(concat(line(), bodyDoc))));
        }

        var inner = new ArrayList<Doc>();
        for (var lc : bodyLeading) {
            inner.add(hardLine());
            inner.add(renderComment(lc));
        }
        inner.add(hardLine());
        inner.add(bodyDoc);
        return concat(head, indent(concat(inner)));
    }

    @Override
    public boolean visit(WhileStatement node) {
        var expression = getProperty(node, WhileStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        var condDoc = result;

        var body = getProperty(node, WhileStatement.BODY_PROPERTY);
        result = headerWithBody(concat(text("while ("), condDoc, text(")")), expression, body);
        return false;
    }

    @Override
    public boolean visit(DoStatement node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("do "));

        var body = getProperty(node, DoStatement.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        parts.add(text(" while ("));

        var expression = getProperty(node, DoStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(");"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ForStatement node) {
        var headerParts = new ArrayList<Doc>();

        headerParts.add(text("for ("));

        // Space only goes BEFORE a present clause: `for (init; cond; update)`,
        // `for (;; update)`, `for (init;;)`, `for (;;)`, etc.
        var initializers = getProperty(node, ForStatement.INITIALIZERS_PROPERTY);
        if (!initializers.isEmpty()) {
            var initDocs = new ArrayList<Doc>();
            for (var init : initializers) {
                init.accept(this);
                initDocs.add(result);
            }
            headerParts.add(join(concat(text(","), space()), initDocs));
        }
        headerParts.add(text(";"));

        var condition = getProperty(node, ForStatement.EXPRESSION_PROPERTY);
        if (condition != null) {
            headerParts.add(space());
            condition.accept(this);
            headerParts.add(result);
        }
        headerParts.add(text(";"));

        var updaters = getProperty(node, ForStatement.UPDATERS_PROPERTY);
        if (!updaters.isEmpty()) {
            headerParts.add(space());
            var updateDocs = new ArrayList<Doc>();
            for (var updater : updaters) {
                updater.accept(this);
                updateDocs.add(result);
            }
            headerParts.add(join(concat(text(","), space()), updateDocs));
        }

        headerParts.add(text(")"));

        var body = getProperty(node, ForStatement.BODY_PROPERTY);
        result = headerWithBody(concat(headerParts), body);
        return false;
    }

    @Override
    public boolean visit(EnhancedForStatement node) {
        var headerParts = new ArrayList<Doc>();

        headerParts.add(text("for ("));

        var parameter = getProperty(node, EnhancedForStatement.PARAMETER_PROPERTY);
        parameter.accept(this);
        headerParts.add(result);

        headerParts.add(text(" : "));

        var expression = getProperty(node, EnhancedForStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        headerParts.add(result);

        headerParts.add(text(")"));

        var body = getProperty(node, EnhancedForStatement.BODY_PROPERTY);
        result = headerWithBody(concat(headerParts), body);
        return false;
    }

    @Override
    public boolean visit(SwitchStatement node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("switch ("));

        var expression = getProperty(node, SwitchStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(") {"));

var statements = getProperty(node, SwitchStatement.STATEMENTS_PROPERTY);

        var stmtParts = new ArrayList<Doc>();
        Statement prevStmt = null;
        boolean prevTrailingLine = false;
        for (var stmt : statements) {
            var leading = comments != null ? comments.leading(stmt) : List.<Comment>of();
            for (var lc : leading) {
                // A comment leading a case/default aligns with it (no extra indent); one
                // leading a body statement is indented like the body.
                Doc lcDoc = concat(hardLine(), renderComment(lc));
                if (!(stmt instanceof SwitchCase)) lcDoc = indent(lcDoc);
                stmtParts.add(lcDoc);
            }

            stmt.accept(this);
            Doc stmtDoc;
            // A labeled rule's body normally joins on the same line (`case X -> body`), but a
            // line comment trailing the `->` (`case X -> // note`) would swallow the joined
            // body — so in that case the body drops to its own line instead.
            boolean joinToRule = leading.isEmpty()
                    && prevStmt instanceof SwitchCase sc && sc.isSwitchLabeledRule()
                    && !prevTrailingLine;
            if (joinToRule) {
                stmtDoc = concat(space(), result);
            } else {
                stmtDoc = concat(hardLine(), result);
                if (!(stmt instanceof SwitchCase)) stmtDoc = indent(stmtDoc);
            }

            boolean trailingLine = false;
            if (comments != null) {
                for (var tc : comments.trailing(stmt)) {
                    stmtDoc = concat(stmtDoc, text(" "), renderComment(tc));
                    if (tc instanceof LineComment) trailingLine = true;
                }
            }

            stmtParts.add(stmtDoc);
            prevStmt = (Statement) stmt;
            prevTrailingLine = trailingLine;
        }

        // Comments after the last statement (before the closing brace) attach as dangling.
        if (comments != null) {
            for (var dc : comments.dangling(node)) {
                stmtParts.add(indent(concat(hardLine(), renderComment(dc))));
            }
        }

        parts.add(indent(concat(stmtParts)));
        parts.add(hardLine());
        parts.add(text("}"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(SwitchCase node) {
        var parts = new ArrayList<Doc>();

        var expressions = getProperty(node, SwitchCase.EXPRESSIONS2_PROPERTY);
        if (expressions.isEmpty()) {
            parts.add(text("default"));
        } else {
            parts.add(text("case "));
            for (int i = 0; i < expressions.size(); i++) {
                var expression = expressions.get(i);
                expression.accept(this);
                parts.add(result);

                if (i < expressions.size() - 1) {
                    parts.add(text(", "));
                }
            }
        }

        if (node.isSwitchLabeledRule()) {
            parts.add(text(" ->"));
        } else {
            parts.add(text(":"));
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(GuardedPattern node) {
        var parts = new ArrayList<Doc>();

        var pattern = getProperty(node, GuardedPattern.PATTERN_PROPERTY);
        pattern.accept(this);
        parts.add(result);

        parts.add(text(" when "));

        var expression = getProperty(node, GuardedPattern.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(EitherOrMultiPattern node) {
        var parts = new ArrayList<Doc>();

        var patterns = getProperty(node, EitherOrMultiPattern.PATTERNS_PROPERTY);
        for (int i = 0; i < patterns.size(); i++) {
            patterns.get(i).accept(this);
            parts.add(result);

            if (i < patterns.size() - 1) {
                parts.add(text(", "));
            }
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(RecordPattern node) {
        var parts = new ArrayList<Doc>();

        var type = getProperty(node, RecordPattern.PATTERN_TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);

        var patterns = getProperty(node, RecordPattern.PATTERNS_PROPERTY);
        if (patterns.isEmpty()) {
            parts.add(text("()"));
        } else {
            var patternDocs = new ArrayList<Doc>();
            for (var pattern : patterns) {
                pattern.accept(this);
                patternDocs.add(result);
            }
            parts.add(group(concat(
                    text("("),
                    indent(concat(
                            softLine(),
                            join(concat(text(","), line()), patternDocs)
                    )),
                    softLine(),
                    text(")")
            )));
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(LabeledStatement node) {
        var parts = new ArrayList<Doc>();

        var label = getProperty(node, LabeledStatement.LABEL_PROPERTY);
        label.accept(this);
        parts.add(result);

        parts.add(text(":"));
        parts.add(hardLine());

        var body = getProperty(node, LabeledStatement.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(VariableDeclarationStatement node) {
        var parts = new ArrayList<Doc>();

        boolean atLineStart = visitModifiers(parts, node, VariableDeclarationStatement.MODIFIERS2_PROPERTY);

        var type = getProperty(node, VariableDeclarationStatement.TYPE_PROPERTY);
        // Own-line comment between a local variable's modifiers and its type
        // (`@SuppressWarnings("resource")\n// c\nWebClient w = ...`).
        emitTypeLeadingComments(parts, type, atLineStart);
        type.accept(this);
        parts.add(result);
        parts.add(space());

        var fragments = getProperty(node, VariableDeclarationStatement.FRAGMENTS_PROPERTY);
        var fragDocs = new ArrayList<Doc>();
        for (var frag : fragments) {
            frag.accept(this);
            fragDocs.add(result);
        }

        if (fragDocs.size() == 1) {
            parts.add(fragDocs.getFirst());
        } else {
            parts.add(group(concat(
                    fragDocs.getFirst(),
                    text(","),
                    indent(concat(
                            line(),
                            join(concat(text(","), line()),
                                 fragDocs.subList(1, fragDocs.size()))
                    ))
            )));
        }

        parts.add(text(";"));
        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(VariableDeclarationFragment node) {
        // C-style trailing dimensions stay glued to the name (`int a[], b[][]`).
        var nameParts = new ArrayList<Doc>();
        nameParts.add(text(node.getName().getIdentifier()));
        appendExtraDimensions(nameParts, node, VariableDeclarationFragment.EXTRA_DIMENSIONS2_PROPERTY);
        var name = concat(nameParts);
        var init = node.getInitializer();
        if (init == null) {
            result = name;
            return false;
        }
        init.accept(this);
        var initDoc = result;

        // A comment after `=` attaches either as a trailing comment of the name (`x = // c`,
        // same line) or as a leading comment of the initializer (`x =\n // c\n v`, own line).
        // Neither the name nor the initializer visitor emits it, so emit it after `=` with
        // the initializer dropped onto its own indented line.
        var nameTrailing = comments != null ? comments.trailing(node.getName()) : List.<Comment>of();
        var initLeading = comments != null ? comments.leading(init) : List.<Comment>of();
        if (!nameTrailing.isEmpty() || !initLeading.isEmpty()) {
            result = initializerWithComments(concat(name, text(" =")), nameTrailing, initLeading, initDoc);
            return false;
        }

        result = group(concat(name, text(" = "), initDoc));
        return false;
    }

    /// Render `<head> <comments> <newline> <init>` for an assignment-like construct whose
    /// `=` is followed by a comment. {@code head} ends with the operator (no trailing
    /// space); its trailing comments render inline after the operator (`x = // c`), any
    /// leading comments of the initializer render on their own lines, and the initializer
    /// drops to its own indented line so a line comment can't swallow it. Used by variable
    /// fragments and assignments.
    private Doc initializerWithComments(Doc head, List<Comment> headTrailing, List<Comment> initLeading, Doc initDoc) {
        Doc h = head;
        for (var tc : headTrailing) h = concat(h, text(" "), renderComment(tc));
        var body = new ArrayList<Doc>();
        for (var lc : initLeading) {
            body.add(hardLine());
            body.add(renderComment(lc));
        }
        body.add(hardLine());
        body.add(initDoc);
        return concat(h, indent(concat(body)));
    }

    @Override
    public boolean visit(TypePattern node) {
        var variable = getProperty(node, TypePattern.PATTERN_VARIABLE_PROPERTY2);
        variable.accept(this);
        // result is already set by SingleVariableDeclaration
        return false;
    }

    @Override
    public boolean visit(NullPattern node) {
        result = text("null");
        return false;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        var parts = new ArrayList<Doc>();

        visitTypeArguments(parts, node, ConstructorInvocation.TYPE_ARGUMENTS_PROPERTY);

        parts.add(text("this"));

        var arguments = getProperty(node, ConstructorInvocation.ARGUMENTS_PROPERTY);
        if (arguments.isEmpty()) {
            parts.add(text("()"));
        } else {
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
            }
            parts.add(group(concat(
                    text("("),
                    indent(concat(
                            softLine(),
                            join(concat(text(","), line()), argDocs)
                    )),
                    softLine(),
                    text(")")
            )));
        }

        parts.add(text(";"));
        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, SuperConstructorInvocation.EXPRESSION_PROPERTY);
        if (expression != null) {
            expression.accept(this);
            parts.add(result);
            parts.add(text("."));
        }

        visitTypeArguments(parts, node, SuperConstructorInvocation.TYPE_ARGUMENTS_PROPERTY);

        parts.add(text("super"));

        var arguments = getProperty(node, SuperConstructorInvocation.ARGUMENTS_PROPERTY);
        if (arguments.isEmpty()) {
            parts.add(text("()"));
        } else {
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
            }
            parts.add(group(concat(
                    text("("),
                    indent(concat(
                            softLine(),
                            join(concat(text(","), line()), argDocs)
                    )),
                    softLine(),
                    text(")")
            )));
        }

        parts.add(text(";"));
        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(TryStatement node) {
        var parts = new ArrayList<Doc>();
        var body = getProperty(node, TryStatement.BODY_PROPERTY);
        var resources = getProperty(node, TryStatement.RESOURCES2_PROPERTY);
        if ((node.getFlags() & ASTNode.MALFORMED) != 0) {
            // JDT 3.45 accepts `try (this)` but its DOM converter drops `this` and
            // every subsequent resource, marking only the try node MALFORMED.
            // Preserve the entire header: even a nonempty resource list is incomplete.
            if (source == null) throw new IllegalStateException("Malformed try statement requires source");
            int start = node.getStartPosition();
            int end = body.getStartPosition();
            var header = source.substring(start, end);
            int length = header.stripTrailing().length();
            parts.add(renderSourceRange(start, length));
            parts.add(header.substring(length).contains("\n") ? hardLine() : space());
            if (comments != null) {
                for (var comment : comments.attachedComments()) {
                    int position = comment.getStartPosition();
                    if (position >= start && position < end) emitted.add(comment);
                }
            }
        } else {
            parts.add(text("try "));
        }
        if ((node.getFlags() & ASTNode.MALFORMED) == 0 && !resources.isEmpty()) {
            var resourceParts = new ArrayList<Doc>();
            for (var resource : resources) {
                resource.accept(this);
                resourceParts.add(result);
            }
            // A comment on a resource (`try ( //\n var a = ...; //\n var b = ...)`) would be
            // dropped; render one-per-line emitting them when present (a comment after `(` is
            // a leading comment of the first resource).
            if (elementsHaveComments(resources)) {
                parts.add(resourcesWithComments(resources, resourceParts));
            } else {
                parts.add(group(concat(
                        text("("),
                        indent(concat(
                                softLine(),
                                join(concat(text(";"), line()), resourceParts)
                        )),
                        softLine(),
                        text(")")
                )));
            }
            parts.add(text(" "));
        }

        body.accept(this);
        parts.add(result);

        // A comment after the try block's `}` (`} // @formatter:on\n catch`) attaches as a
        // trailing comment of the body; emit it and push `catch`/`finally` onto its own line
        // (instead of gluing `} catch`) so a line comment can't swallow it.
        var bodyTrailing = comments != null ? comments.trailing(body) : List.<Comment>of();
        boolean breakAfterBody = false;
        for (var tc : bodyTrailing) {
            parts.add(text(" "));
            parts.add(renderComment(tc));
            if (tc instanceof LineComment) breakAfterBody = true;
        }

        var catches = getProperty(node, TryStatement.CATCH_CLAUSES_PROPERTY);
        for (var catchClause : catches) {
            parts.add(breakAfterBody ? hardLine() : text(" "));
            breakAfterBody = false;
            catchClause.accept(this);
            parts.add(result);
        }

        var finallyBlock = getProperty(node, TryStatement.FINALLY_PROPERTY);
        if (finallyBlock != null) {
            parts.add(breakAfterBody ? hardLine() : text(" "));
            parts.add(text("finally "));
            finallyBlock.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(CatchClause node) {
        // The separator before `catch` (a space gluing `} catch`, or a line break when a
        // comment follows the try block) is emitted by visit(TryStatement).
        var parts = new ArrayList<Doc>();
        parts.add(text("catch ("));

        var exception = getProperty(node, CatchClause.EXCEPTION_PROPERTY);
        exception.accept(this);
        parts.add(result);

        parts.add(text(") "));

        var body = getProperty(node, CatchClause.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ThrowStatement node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("throw "));

        var expression = getProperty(node, ThrowStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(";"));
        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ReturnStatement node) {
        var parts = new ArrayList<Doc>();
        parts.add(text("return"));

        var expression = getProperty(node, ReturnStatement.EXPRESSION_PROPERTY);
        if (expression != null) {
            parts.add(space());
            expression.accept(this);
            parts.add(result);
        }

        parts.add(text(";"));
        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(YieldStatement node) {
        var parts = new ArrayList<Doc>();

        if (!node.isImplicit()) {
            parts.add(text("yield"));
            parts.add(space());
        }

        var expression = getProperty(node, YieldStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(";"));
        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(BreakStatement node) {
        var label = node.getLabel();
        result = label != null
                ? concat(text("break"), space(), text(label.getIdentifier()), text(";"))
                : text("break;");
        return false;
    }

    @Override
    public boolean visit(ContinueStatement node) {
        var label = node.getLabel();
        result = label != null
                ? concat(text("continue"), space(), text(label.getIdentifier()), text(";"))
                : text("continue;");
        return false;
    }

    @Override
    public boolean visit(SynchronizedStatement node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("synchronized ("));

        var expression = getProperty(node, SynchronizedStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(") "));

        var body = getProperty(node, SynchronizedStatement.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ExpressionStatement node) {
        var expr = getProperty(node, ExpressionStatement.EXPRESSION_PROPERTY);
        expr.accept(this);
        var exprDoc = result;
        result = concat(exprDoc, text(";"));
        return false;
    }

    @Override
    public boolean visit(AssertStatement node) {
        var expression = getProperty(node, AssertStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        var exprDoc = result;

        var message = getProperty(node, AssertStatement.MESSAGE_PROPERTY);
        if (message == null) {
            // No message: nothing to wrap at, so the expression breaks internally if needed.
            result = concat(text("assert "), exprDoc, text(";"));
            return false;
        }

        message.accept(this);
        var messageDoc = result;

        // With a message, prefer to wrap at the `:` over breaking inside the asserted
        // expression: `assert <expr>\n    : <message>;`. The separator line lives in this
        // outer group so it breaks first; the expression keeps its own group and stays flat
        // unless `assert <expr>` alone still overflows the line.
        result = group(concat(
            text("assert "),
            exprDoc,
            indent(concat(line(), text(": "), messageDoc)),
            text(";")
        ));
        return false;
    }

    // expressions

    @Override
    public boolean visit(VariableDeclarationExpression node) {
        var parts = new ArrayList<Doc>();

        visitModifiers(parts, node, VariableDeclarationExpression.MODIFIERS2_PROPERTY);

        var type = getProperty(node, VariableDeclarationExpression.TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);
        parts.add(space());

        var fragments = getProperty(node, VariableDeclarationExpression.FRAGMENTS_PROPERTY);
        var fragDocs = new ArrayList<Doc>();
        for (var frag : fragments) {
            frag.accept(this);
            fragDocs.add(result);
        }
        parts.add(join(text(", "), fragDocs));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(Assignment node) {
        var left = getProperty(node, Assignment.LEFT_HAND_SIDE_PROPERTY);
        // The LHS is a glued target: an array-access LHS (`arr[i] = ...`) must not explode its
        // brackets just because the RHS is long — the RHS wraps instead. Same suppression as a
        // call receiver spine. Restored before the RHS so its own array accesses keep breaking.
        boolean prevFlatReceiver = flatReceiver;
        flatReceiver = true;
        left.accept(this);
        flatReceiver = prevFlatReceiver;
        var leftDoc = result;
        var op = node.getOperator().toString();

        var right = getProperty(node, Assignment.RIGHT_HAND_SIDE_PROPERTY);
        right.accept(this);
        var rightDoc = result;

        // A comment after the `=` (`x = // c\n v`) attaches as a trailing comment of the
        // left-hand side or a leading comment of the right-hand side; emit it after the
        // operator with the value on its own line, same as a variable initializer.
        var leftTrailing = comments != null ? comments.trailing(left) : List.<Comment>of();
        var rightLeading = comments != null ? comments.leading(right) : List.<Comment>of();
        if (!leftTrailing.isEmpty() || !rightLeading.isEmpty()) {
            result = initializerWithComments(concat(leftDoc, space(), text(op)), leftTrailing, rightLeading, rightDoc);
            return false;
        }

        result = concat(leftDoc, space(), text(op), space(), rightDoc);
        return false;
    }

    @Override
    public boolean visit(FieldAccess node) {
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, FieldAccess.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text("."));

        var name = getProperty(node, FieldAccess.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(SuperFieldAccess node) {
        var parts = new ArrayList<Doc>();

        var qualifier = getProperty(node, SuperFieldAccess.QUALIFIER_PROPERTY);
        if (qualifier != null) {
            qualifier.accept(this);
            parts.add(result);
            parts.add(text("."));
        }

        parts.add(text("super."));

        var name = getProperty(node, SuperFieldAccess.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(SwitchExpression node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("switch ("));

        var expression = getProperty(node, SwitchExpression.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(") {"));

        // Statements carry comments exactly as in a switch statement (see visit(SwitchStatement)):
        // an own-line comment before a case/body is leading, one after it is trailing, and a
        // comment before the closing brace is dangling.
        var stmtParts = new ArrayList<Doc>();
        var statements = getProperty(node, SwitchExpression.STATEMENTS_PROPERTY);
        Statement prevStmt = null;
        boolean prevTrailingLine = false;
        for (var statement : statements) {
            var leading = comments != null ? comments.leading(statement) : List.<Comment>of();
            for (var lc : leading) {
                // A comment leading a case/default aligns with it (no extra indent); one
                // leading a body statement is indented like the body.
                Doc lcDoc = concat(hardLine(), renderComment(lc));
                if (!(statement instanceof SwitchCase)) lcDoc = indent(lcDoc);
                stmtParts.add(lcDoc);
            }

            statement.accept(this);
            Doc stmtDoc;
            // A labeled rule's body normally joins on the same line (`case X -> body`), but a
            // line comment trailing the `->` (`case X -> // note`) would swallow the joined
            // body — so in that case the body drops to its own line instead.
            boolean joinToRule = leading.isEmpty()
                    && prevStmt instanceof SwitchCase sc && sc.isSwitchLabeledRule()
                    && !prevTrailingLine;
            if (joinToRule) {
                stmtDoc = concat(space(), result);
            } else {
                stmtDoc = concat(hardLine(), result);
                if (!(statement instanceof SwitchCase)) stmtDoc = indent(stmtDoc);
            }

            boolean trailingLine = false;
            if (comments != null) {
                for (var tc : comments.trailing(statement)) {
                    stmtDoc = concat(stmtDoc, text(" "), renderComment(tc));
                    if (tc instanceof LineComment) trailingLine = true;
                }
            }

            stmtParts.add(stmtDoc);
            prevStmt = (Statement) statement;
            prevTrailingLine = trailingLine;
        }

        // Comments after the last statement (before the closing brace) attach as dangling.
        if (comments != null) {
            for (var dc : comments.dangling(node)) {
                stmtParts.add(indent(concat(hardLine(), renderComment(dc))));
            }
        }

        parts.add(indent(concat(stmtParts)));
        parts.add(hardLine());
        parts.add(text("}"));

        result = concat(parts);
        result = conditionalGroup(List.of(result, result));
        return false;
    }

    @Override
    public boolean visit(ParenthesizedExpression node) {
        var expr = getProperty(node, ParenthesizedExpression.EXPRESSION_PROPERTY);
        expr.accept(this);

        result = concat(text("("), result, text(")"));
        return false;
    }

    @Override
    public boolean visit(PrefixExpression node) {
        var parts = new ArrayList<Doc>();

        parts.add(text(node.getOperator().toString()));

        var operand = getProperty(node, PrefixExpression.OPERAND_PROPERTY);
        operand.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(InfixExpression node) {
        var op = node.getOperator();
        var operator = op.toString();

        // Flatten same-operator chains. JDT sometimes returns a flat node (a single
        // InfixExpression with extended operands) and sometimes a left-leaning tree
        // of nested InfixExpressions — the AST shape depends on the operator. If we
        // let each nested layer keep its own group, inner sub-chains would fit flat
        // independently of the outer break decision, producing a hybrid where some
        // operands pack on the first line and the rest chop one-per-line. By folding
        // all same-operator operands up into one group, the whole chain breaks (or
        // not) as a unit — chop-all semantics for binary operator chains.
        var operands = new ArrayList<ASTNode>();
        // anchors[i] is the inner InfixExpression node that operand[i] is the RIGHT operand
        // of (null for the leftmost leaf). In a left-leaning chain `(a && b) && c`, a trailing
        // comment after `b` attaches to the inner `(a && b)` node — not the flattened leaf
        // `b` — so we must check the anchor too or it is dropped.
        var anchors = new ArrayList<ASTNode>();
        collectSameOpOperands(node, op, operands, anchors);

        var operandDocs = new ArrayList<Doc>();
        for (var operand : operands) {
            operand.accept(this);
            operandDocs.add(result);
        }

        // A trailing comment on a non-last operand (`a() //\n || b()`) attaches to that
        // operand (or its anchor) and would otherwise be dropped. Emit it; a line comment
        // forces the chain to break so it ends its line. The LAST operand's trailing comment
        // is the whole expression's — left to the enclosing context, since the `;`/`)` the
        // parent emits after it would be swallowed by an inline line comment here.
        int last = operandDocs.size() - 1;
        boolean forceBreak = false;

        Doc head = operandDocs.get(0);
        if (last > 0 && hasTrailingComment(operands.get(0))) {
            head = withTrailingComments(head, operands.get(0));
            forceBreak = true;
        }

        var tail = new ArrayList<Doc>();
        for (int i = 1; i < operandDocs.size(); i++) {
            tail.add(line());
            // An own-line comment after the operator (`a\n + // c\n b`) attaches as a leading
            // comment of this operand; emit it on its own line before the operator.
            var leading = comments != null ? comments.leading(operands.get(i)) : List.<Comment>of();
            for (var lc : leading) {
                tail.add(renderComment(lc));
                tail.add(line());
                forceBreak = true;
            }
            tail.add(text(operator + " "));
            var od = operandDocs.get(i);
            if (i < last) {
                var anchor = anchors.get(i);
                if (hasTrailingComment(operands.get(i))) {
                    od = withTrailingComments(od, operands.get(i));
                    forceBreak = true;
                }
                if (anchor != null && hasTrailingComment(anchor)) {
                    od = withTrailingComments(od, anchor);
                    forceBreak = true;
                }
            }
            tail.add(od);
        }
        var doc = concat(head, indent(concat(tail)));
        result = forceBreak ? breakGroup(doc) : group(doc);
        return false;
    }

    /// Walks the left spine of a same-operator InfixExpression chain, appending every
    /// leaf operand (in source order) into {@code out}. Stops descending at any node
    /// whose operator differs or that is not an InfixExpression (e.g.
    /// ParenthesizedExpression — parentheses correctly halt flattening).
    private void collectSameOpOperands(InfixExpression node, InfixExpression.Operator op,
            List<ASTNode> out, List<ASTNode> anchors) {
        var extended = getProperty(node, InfixExpression.EXTENDED_OPERANDS_PROPERTY);
        var left = getProperty(node, InfixExpression.LEFT_OPERAND_PROPERTY);
        if (left instanceof InfixExpression leftInfix && leftInfix.getOperator() == op) {
            collectSameOpOperands(leftInfix, op, out, anchors);
        } else {
            out.add(left);
            anchors.add(null); // leftmost leaf: a comment after it attaches to the leaf itself
        }
        var right = getProperty(node, InfixExpression.RIGHT_OPERAND_PROPERTY);
        if (right instanceof InfixExpression rightInfix && rightInfix.getOperator() == op) {
            collectSameOpOperands(rightInfix, op, out, anchors);
        } else {
            out.add(right);
            // `node`'s source range ends at its LAST operand, so a comment after that operand
            // attaches to `node` (the left-leaning subtree) rather than the leaf — anchor it
            // there. For a non-last operand of a flat node (one with extended operands), the
            // comment attaches to the leaf itself, so no anchor is needed.
            anchors.add(extended.isEmpty() ? node : null);
        }
        for (int e = 0; e < extended.size(); e++) {
            var ext = extended.get(e);
            if (ext instanceof InfixExpression extInfix && extInfix.getOperator() == op) {
                collectSameOpOperands(extInfix, op, out, anchors);
            } else {
                out.add(ext);
                anchors.add(e == extended.size() - 1 ? node : null);
            }
        }
    }

    @Override
    public boolean visit(PostfixExpression node) {
        var parts = new ArrayList<Doc>();

        var operand = getProperty(node, PostfixExpression.OPERAND_PROPERTY);
        operand.accept(this);
        parts.add(result);

        parts.add(text(node.getOperator().toString()));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ConditionalExpression node) {
        var parts = new ArrayList<Doc>();

        var condition = getProperty(node, ConditionalExpression.EXPRESSION_PROPERTY);
        condition.accept(this);
        parts.add(result);

        var thenExpr = getProperty(node, ConditionalExpression.THEN_EXPRESSION_PROPERTY);
        thenExpr.accept(this);
        var thenDoc = result;

        var elseExpr = getProperty(node, ConditionalExpression.ELSE_EXPRESSION_PROPERTY);
        elseExpr.accept(this);
        var elseDoc = result;

        // Comments attached to an operand would otherwise be dropped: the operand
        // visitors never consult the CommentMap, and no ancestor emits comments of a
        // nested expression. The four positions below are the only ones the CommentMap
        // can pin to this node's direct children — a comment before the condition or
        // after the else operand falls outside the conditional's range and so attaches
        // to an ancestor instead.
        //
        // Any operand comment forces the conditional to break. That keeps a trailing
        // line comment from swallowing the `:`/operand that follows it, keeps an
        // own-line comment from collapsing onto a neighbour's line, and makes the
        // re-parsed attachment — and therefore the layout — stable across passes.
        var condTrailing = comments != null ? comments.trailing(condition) : List.<Comment>of();
        var thenLeading = comments != null ? comments.leading(thenExpr) : List.<Comment>of();
        var thenTrailing = comments != null ? comments.trailing(thenExpr) : List.<Comment>of();
        var elseLeading = comments != null ? comments.leading(elseExpr) : List.<Comment>of();
        boolean hasComments = !condTrailing.isEmpty() || !thenLeading.isEmpty()
                || !thenTrailing.isEmpty() || !elseLeading.isEmpty();

        for (var c : condTrailing) parts.add(concat(text(" "), renderComment(c)));

        var body = new ArrayList<Doc>();
        body.add(line());
        body.add(text("? "));
        for (var c : thenLeading) {
            body.add(renderComment(c));
            body.add(line());
        }
        body.add(thenDoc);
        for (var c : thenTrailing) body.add(concat(text(" "), renderComment(c)));
        body.add(line());
        for (var c : elseLeading) {
            body.add(renderComment(c));
            body.add(line());
        }
        body.add(text(": "));
        body.add(elseDoc);

        parts.add(indent(concat(body)));

        var doc = concat(parts);
        result = hasComments ? breakGroup(doc) : group(doc);
        return false;
    }

    @Override
    public boolean visit(InstanceofExpression node) {
        var left = getProperty(node, InstanceofExpression.LEFT_OPERAND_PROPERTY);
        left.accept(this);
        var leftDoc = result;

        var right = getProperty(node, InstanceofExpression.RIGHT_OPERAND_PROPERTY);
        right.accept(this);
        var rightDoc = result;

        result = group(concat(
                leftDoc,
                indent(concat(line(), text("instanceof "), rightDoc))
        ));
        return false;
    }

    @Override
    public boolean visit(PatternInstanceofExpression node) {
        var left = getProperty(node, PatternInstanceofExpression.LEFT_OPERAND_PROPERTY);
        left.accept(this);
        var leftDoc = result;

        var pattern = getProperty(node, PatternInstanceofExpression.PATTERN_PROPERTY);
        pattern.accept(this);
        var patternDoc = result;

        result = group(concat(
                leftDoc,
                indent(concat(line(), text("instanceof "), patternDoc))
        ));
        return false;
    }

    @Override
    public boolean visit(MethodInvocation node) {
        // Collect chain by walking down EXPRESSION_PROPERTY through MethodInvocations.
        // Result order: [innermost, ..., outermost]
        var chain = new ArrayList<MethodInvocation>();
        collectMethodChain(node, chain);

        if (chain.size() >= 3) {
            visitMethodChain(chain);
            return false;
        }

        // A comment that forces a non-outermost chain segment to render multi-line — the
        // `foo() //\n .bar()` idiom, a `seg( //` comment, or a comment on a segment's
        // argument — must go through the chain-break path so the comment is emitted (per-call
        // would drop it) and the chain breaks at its dots rather than leaving a `).next()`
        // orphan. Only non-outermost segments count: the outermost segment's trailing
        // comment is the chain expression's, handled by the enclosing context.
        if (chainSegmentHasComment(chain)) {
            visitMethodChain(chain);
            return false;
        }

        // A trailing comment on the chain's root receiver (`new X() //\n .foo()`,
        // `obj //\n .foo()`) likewise routes through the chain-break path, which emits it
        // and breaks the first segment off so it doesn't glue onto the comment's line.
        var chainRoot = getProperty(chain.getFirst(), MethodInvocation.EXPRESSION_PROPERTY);
        if (chainRoot != null && hasTrailingComment(chainRoot)) {
            visitMethodChain(chain);
            return false;
        }

        // An own-line comment before any segment (including the outermost — unlike a
        // trailing comment, a leading one is followed by its own `.seg`, never a parent
        // token) routes through the chain-break path so it's emitted.
        for (var seg : chain) {
            if (!nameLeadingComments(seg).isEmpty()) {
                visitMethodChain(chain);
                return false;
            }
        }

        // For 1- and 2-segment chains the per-call path below has no break point at
        // the receiver `.`, so when the line overflows its ONLY break points are the
        // arg-lists. Route to the chain-break path (which does break at the `.`)
        // whenever leaving it on per-call would force an arg-list to explode as that
        // last resort. Two independent triggers:
        var rootExpr = getProperty(chain.getFirst(), MethodInvocation.EXPRESSION_PROPERTY);
        if (rootExpr != null) {
            // 1. The first segment carries args (only meaningful for a 2-seg chain —
            //    a 1-seg chain has no leading segment before the call). Per-call lets
            //    `first(...)` wrap independently into the orphaned shape
            //       root.firstSeg(
            //           arg
            //       ).secondSeg(...);     <-- `).secondSeg(` reads as junk
            //    Breaking at the `.` instead gives `root.firstSeg(arg)\n  .secondSeg(...)`.
            boolean firstSegHasArgs = chain.size() == 2
                    && !getProperty(chain.getFirst(), MethodInvocation.ARGUMENTS_PROPERTY).isEmpty();
            // 2. The root is a complex receiver with its own breakable interior
            //    (`new X(a, b).method(...)`, `arr[i].method(...)`, `((T) x).method(...)`,
            //    `(a + b).method(...)`). Per-call would blow up the root's args/elements
            //    rather than break at the `.`. Name-like roots (names, field access,
            //    `this`, `Foo.class`, literals) have no such interior, so they stay on
            //    the cleaner per-call path — e.g. `obj.first().second(\n  arg\n)`.
            boolean complexRoot = !isNameLikeReceiver(rootExpr);
            if (firstSegHasArgs || complexRoot) {
                visitMethodChain(chain);
                return false;
            }
        }

        // Per-call formatting: a single call on a name-like root, a no-receiver call,
        // or a 2-call chain on a name-like root whose first segment takes no args.
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, MethodInvocation.EXPRESSION_PROPERTY);
        if (expression != null) {
            boolean prevFlatReceiver = flatReceiver;
            flatReceiver = true;
            expression.accept(this);
            flatReceiver = prevFlatReceiver;
            parts.add(result);
            parts.add(text("."));
        }

        visitTypeArguments(parts, node, MethodInvocation.TYPE_ARGUMENTS_PROPERTY);

        parts.add(text(node.getName().getIdentifier()));

        var arguments = getProperty(node, MethodInvocation.ARGUMENTS_PROPERTY);
        formatArguments(parts, arguments, afterOpenParenComments(node), beforeCloseParenComments(node));

        result = group(concat(parts));
        return false;
    }

    /// Formats a method chain (`root.seg1(...).seg2(...)...`) with three modes
    /// chosen by ACTUAL line widths, not just total flat width:
    ///
    ///   flat:                     root.seg1.seg2.seg3
    ///   partial (root + seg1):    root.seg1
    ///                                 .seg2
    ///                                 .seg3
    ///   full (each on own line):  root
    ///                                 .seg1
    ///                                 .seg2
    ///                                 .seg3
    ///
    /// Built as nested groups rather than a conditionalGroup with [flat,
    /// partial, full] alternatives. The conditionalGroup approach measured all
    /// three alts flat — which collapses them to the same width since softLine
    /// is 0-width in flat mode — so it could never actually distinguish partial
    /// from full and always fell to the LAST listed alt. That picked partial for
    /// name-like roots even when partial would force the first segment's args
    /// to wrap (e.g. `EventNode.type(longargs).addListener(...)`).
    ///
    /// The nested-groups structure here lets the inner group's fits check
    /// detect "root + firstSeg(args) overflows the current line" and break the
    /// first segment off, producing the full-break shape that the user actually
    /// wants in that case — while still preferring partial when it does fit.
    private void visitMethodChain(List<MethodInvocation> chain) {
        var innermost = chain.getFirst();
        var rootExpr = getProperty(innermost, MethodInvocation.EXPRESSION_PROPERTY);
        int lastSeg = chain.size() - 1;

        // A segment that renders multi-line because of a comment (its own trailing comment
        // `foo() //`, a `seg( //` comment, or a comment on one of its arguments) forces the
        // chain to break at its dots so a broken segment doesn't leave a `).next()` orphan.
        // A non-outermost segment's trailing comment is also appended to its doc; the
        // OUTERMOST segment's trailing comment is left to the parent (the `;`/`,`/`)` it
        // emits would be swallowed by an inline line comment here).
        boolean forceBreak = false;

        Doc rootDoc;
        int startIndex;
        if (rootExpr != null) {
            rootExpr.accept(this);
            // A trailing comment on the root receiver (`new X() //\n .foo()`) is appended
            // here; it forces the chain to break and the first segment off its line.
            rootDoc = withTrailingComments(result, rootExpr);
            forceBreak |= hasTrailingComment(rootExpr);
            startIndex = 0;
        } else {
            // The innermost call is rendered into the root; emit its trailing comment here
            // unless it is also the outermost segment (a 1-segment chain).
            if (lastSeg > 0) {
                rootDoc = withTrailingComments(formatMethodCallNoDot(innermost), innermost);
                forceBreak |= segmentBreaks(innermost);
            } else {
                rootDoc = formatMethodCallNoDot(innermost);
            }
            startIndex = 1;
        }

        var segDocs = new ArrayList<Doc>();
        for (int i = startIndex; i < chain.size(); i++) {
            var seg = chain.get(i);
            var doc = formatMethodCallWithDot(seg);
            // An own-line comment leading this segment (emitted by formatMethodCallWithDot)
            // forces the chain to break — for every segment, including the outermost.
            forceBreak |= !nameLeadingComments(seg).isEmpty();
            if (i < lastSeg) {
                doc = withTrailingComments(doc, seg);
                forceBreak |= segmentBreaks(seg);
            }
            segDocs.add(doc);
        }

        if (segDocs.isEmpty()) {
            // Degenerate case: e.g. chain.size() == 1 with rootExpr == null.
            // Callers normally gate on chain.size() >= 2 so this shouldn't fire,
            // but defend anyway rather than emit an empty break-group.
            result = rootDoc;
            return;
        }

        // Inner group around the first segment. When the outer group breaks,
        // this inner group independently decides whether `root.firstSeg(args)`
        // fits on the current line:
        //   inner fits   → boundaryLine collapses to empty → first seg glued to root
        //   inner breaks → boundaryLine becomes newline    → first seg on its own
        //                                                     indented line
        //
        // boundaryLine (vs softLine) so that enclosing fits checks — notably the
        // root's own args-wrap group when the root is itself a method call like
        // `eventNode(args)` — can SEE this break point through the inner group
        // wall, and don't incorrectly conclude that the entire chain is on the
        // current line and start wrapping their own args.
        //
        // When the root renders multi-line because of a comment — a no-receiver root call
        // that ends in a trailing comment (`assertThat(x) //`) or has its args forced open
        // (`assertThat( //\n x)`), or a receiver that ends in a trailing comment
        // (`new X() //`) — its boundaryLine MUST break. Otherwise the inner group stays
        // flat, collapses the boundaryLine, and glues the first segment onto the root's last
        // line, swallowing a trailing line comment or leaving a `x).next()` orphan. Forcing
        // the break only here keeps the normal gluing otherwise.
        boolean rootBreaks = startIndex == 1
                ? segmentBreaks(innermost)
                : hasTrailingComment(rootExpr);
        var firstSegInner = indent(concat(boundaryLine(), segDocs.getFirst()));
        var firstSegGroup = rootBreaks ? breakGroup(firstSegInner) : group(firstSegInner);

        var outerParts = new ArrayList<Doc>();
        outerParts.add(rootDoc);
        outerParts.add(firstSegGroup);

        if (segDocs.size() > 1) {
            // Remaining segments always break together with the outer group —
            // there's no scenario where we'd want them on the same line as the
            // first segment if the first segment had to break to its own line.
            var restParts = new ArrayList<Doc>();
            for (int i = 1; i < segDocs.size(); i++) {
                restParts.add(softLine());
                restParts.add(segDocs.get(i));
            }
            outerParts.add(indent(concat(restParts)));
        }

        var doc = concat(outerParts);
        result = forceBreak ? breakGroup(doc) : group(doc);
    }

    private void collectMethodChain(MethodInvocation node, List<MethodInvocation> chain) {
        var expr = getProperty(node, MethodInvocation.EXPRESSION_PROPERTY);
        if (expr instanceof MethodInvocation inner) {
            collectMethodChain(inner, chain);
        }
        chain.add(node);
    }

    /// True when any NON-outermost segment of the chain renders across multiple lines
    /// because of a comment. Such chains must route through visitMethodChain so the chain
    /// breaks at its dots — leaving them on the per-call path would emit the orphan
    /// `...).nextSeg()`. The outermost segment is excluded: nothing follows it to orphan,
    /// and its own trailing comment belongs to the enclosing context.
    private boolean chainSegmentHasComment(List<MethodInvocation> chain) {
        for (int i = 0; i < chain.size() - 1; i++) {
            if (segmentBreaks(chain.get(i))) return true;
        }
        return false;
    }

    /// A chain segment renders across multiple lines because a comment forces it open: it
    /// carries a trailing comment (`seg() //`), a comment right after its `(` (`seg( //`),
    /// or a comment on one of its arguments. A non-outermost such segment forces the whole
    /// chain to break at its dots so the broken segment doesn't leave a `...).next()` orphan.
    private boolean segmentBreaks(MethodInvocation seg) {
        return hasTrailingComment(seg)
                || !afterOpenParenComments(seg).isEmpty()
                || !nameLeadingComments(seg).isEmpty()
                || !typeWitnessTrailing(seg).isEmpty()
                || argumentsHaveComments(getProperty(seg, MethodInvocation.ARGUMENTS_PROPERTY));
    }

    /// A receiver that reads as an indivisible accessor — a name, a field access, an
    /// array access, `this`, `Foo.class`, or a literal. These read as a single addressable
    /// thing (`obj.field`, `arr[idx]`) rather than a call/constructor with a parenthesized
    /// argument list, so a `recv.method(...)` call on one is left on the per-call path,
    /// which wraps the call's own args cleanly (see arrayAccessNoBracketBreak.test).
    ///
    /// Everything else is "complex": a `new X(a, b)`, a parenthesized expression (which
    /// is also how a cast or infix receiver must be written — `((T) x).m()`, `(a + b).m()`),
    /// an array creation, a nested call, etc. These carry a breakable interior that per-call
    /// would explode into the orphan shape `new X(\n  a,\n  b\n).method(...)`, so they are
    /// routed to the chain-break path and break at the `.` instead.
    private static boolean isNameLikeReceiver(ASTNode expr) {
        return expr instanceof Name
                || expr instanceof FieldAccess
                || expr instanceof SuperFieldAccess
                || expr instanceof ArrayAccess
                || expr instanceof ThisExpression
                || expr instanceof TypeLiteral
                || expr instanceof StringLiteral
                || expr instanceof NumberLiteral
                || expr instanceof BooleanLiteral
                || expr instanceof CharacterLiteral
                || expr instanceof NullLiteral;
    }

    private Doc formatMethodCallNoDot(MethodInvocation node) {
        var parts = new ArrayList<Doc>();
        visitTypeArguments(parts, node, MethodInvocation.TYPE_ARGUMENTS_PROPERTY);
        emitTypeWitnessTrailing(parts, node);
        parts.add(text(node.getName().getIdentifier()));
        formatArguments(parts, getProperty(node, MethodInvocation.ARGUMENTS_PROPERTY), afterOpenParenComments(node), beforeCloseParenComments(node));
        return concat(parts);
    }

    private Doc formatMethodCallWithDot(MethodInvocation node) {
        var parts = new ArrayList<Doc>();
        // Own-line comments before this segment (`seg()\n// c\n.next()`) attach as leading
        // comments of the method name; emit them on their own line before the `.`. The
        // hardLine after each breaks the chain here, so the comment ends its line.
        for (var lc : nameLeadingComments(node)) {
            parts.add(renderComment(lc));
            parts.add(hardLine());
        }
        parts.add(text("."));
        visitTypeArguments(parts, node, MethodInvocation.TYPE_ARGUMENTS_PROPERTY);
        emitTypeWitnessTrailing(parts, node);
        parts.add(text(node.getName().getIdentifier()));
        formatArguments(parts, getProperty(node, MethodInvocation.ARGUMENTS_PROPERTY), afterOpenParenComments(node), beforeCloseParenComments(node));
        return concat(parts);
    }

    /// Own-line comments before a chain segment (`seg()\n// c\n.next()`) attach as leading
    /// comments of the segment's method name.
    private List<Comment> nameLeadingComments(MethodInvocation node) {
        return comments != null ? comments.leading(node.getName()) : List.<Comment>of();
    }

    /// A comment after an explicit type witness, before the method name
    /// (`Comparator.<T, U> //\n compare(...)`), attaches as a trailing comment of the last
    /// type argument. Emit it and break before the name so a line comment ends its line.
    private List<Comment> typeWitnessTrailing(MethodInvocation node) {
        var typeArgs = getProperty(node, MethodInvocation.TYPE_ARGUMENTS_PROPERTY);
        if (comments == null || typeArgs.isEmpty()) return List.of();
        return comments.trailing(typeArgs.getLast());
    }

    private void emitTypeWitnessTrailing(List<Doc> parts, MethodInvocation node) {
        var trailing = typeWitnessTrailing(node);
        if (trailing.isEmpty()) return;
        for (var tc : trailing) {
            parts.add(text(" "));
            parts.add(renderComment(tc));
        }
        parts.add(hardLine());
    }

    /// A comment right after a call's `(` (`foo( // c\n arg)`) attaches as a trailing
    /// comment of the method name. Returns those so the argument list can re-emit them
    /// just after the `(`.
    private List<Comment> afterOpenParenComments(MethodInvocation node) {
        return comments != null ? comments.trailing(node.getName()) : List.<Comment>of();
    }

    /// An own-line comment after the last argument, before the `)` (`m(arg\n// c\n)`),
    /// attaches as a dangling comment of the call. Returns those so the argument list can
    /// re-emit them just before the `)`.
    private List<Comment> beforeCloseParenComments(MethodInvocation node) {
        return comments != null ? comments.dangling(node) : List.<Comment>of();
    }

    private void formatArguments(List<Doc> parts, List<ASTNode> arguments) {
        formatArguments(parts, arguments, List.of(), List.of());
    }

    private void formatArguments(List<Doc> parts, List<ASTNode> arguments,
            List<Comment> afterOpenParen, List<Comment> beforeClose) {
        if (arguments.isEmpty()) {
            if (afterOpenParen.isEmpty() && beforeClose.isEmpty()) {
                parts.add(text("()"));
            } else {
                // `m( // c\n)` / `m(\n// c\n)` — a comment inside an empty call.
                Doc open = text("(");
                for (var c : afterOpenParen) open = concat(open, text(" "), renderComment(c));
                var inner = new ArrayList<Doc>();
                for (var c : beforeClose) {
                    inner.add(hardLine());
                    inner.add(renderComment(c));
                }
                parts.add(breakGroup(concat(open, indent(concat(inner)), hardLine(), text(")"))));
            }
        } else {
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
            }

            // An argument carrying a comment (the `m(arg, //\n next)` idiom), a comment right
            // after `(` (attached to the method name), or a dangling comment before `)`,
            // forces a one-per-line break and bypasses the tuple / trailing-block layouts
            // below: a line comment must end its line and a leading comment needs its own
            // line, neither of which those layouts accommodate. Without this it's dropped.
            if (!afterOpenParen.isEmpty() || !beforeClose.isEmpty() || argumentsHaveComments(arguments)) {
                parts.add(argsWithComments(arguments, argDocs, afterOpenParen, beforeClose));
                return;
            }

            var lastArg = arguments.getLast();
            boolean lastArgIsBlock = (lastArg instanceof LambdaExpression le
                    && getProperty(le, LambdaExpression.BODY_PROPERTY) instanceof Block)
                    || lastArg instanceof SwitchExpression
                    || lastArg instanceof ClassInstanceCreation cic
                    && getProperty(cic, ClassInstanceCreation.ANONYMOUS_CLASS_DECLARATION_PROPERTY) != null;

            if (lastArgIsBlock && argDocs.size() == 1) {
                parts.add(text("("));
                parts.add(argDocs.getFirst());
                parts.add(text(")"));
            } else if (lastArgIsBlock) {
                // Trailing-block call (lambda body, switch expr, anonymous class).
                // Two acceptable shapes — picked by the ConditionalGroup chooser
                // using `fitsFirstLine` (i.e. does the first line of the alt — up
                // to the block's first HardLine — fit?):
                //
                //   trailing-lambda (preferred when its first line fits):
                //       foo(arg1, arg2, () -> {
                //           bodyStmt;
                //       });
                //
                //   wrap-all (used when the trailing-lambda first line is too long):
                //       foo(
                //           arg1,
                //           arg2,
                //           () -> {
                //               bodyStmt;
                //           }
                //       );
                //
                // Wrap-all keeps the last arg INSIDE the indent so the block contents
                // render at base+8 and the block's closing brace at base+4. A
                // softLine() AFTER the indent puts the call's `)` at base+0 —
                // matching every other wrapped multi-arg call.
                //
                // (The single-arg lambda case `foo(() -> { ... })` is handled above
                // and intentionally glues `});` together — that's the conventional
                // trailing-call idiom for Stream-style APIs and we keep it.)
                var trailingLambdaAlt = concat(
                        text("("),
                        join(concat(text(","), space()), argDocs),
                        text(")")
                );
                var wrapAllAlt = concat(
                        text("("),
                        indent(concat(
                                softLine(),
                                join(concat(text(","), line()), argDocs.subList(0, argDocs.size() - 1)),
                                text(","),
                                line(),
                                argDocs.getLast()
                        )),
                        softLine(),
                        text(")")
                );
                parts.add(conditionalGroup(List.of(trailingLambdaAlt, wrapAllAlt)));
            } else {
                var tupleSizes = computeTupleGroupSizes(arguments);
                if (tupleSizes != null) {
                    parts.add(buildTupleArgs(argDocs, tupleSizes));
                } else {
                    parts.add(group(concat(
                            text("("),
                            indent(concat(
                                    softLine(),
                                    join(concat(text(","), line()), argDocs)
                            )),
                            softLine(),
                            text(")")
                    )));
                }
            }
        }
    }

    private boolean argumentsHaveComments(List<ASTNode> arguments) {
        if (comments == null) return false;
        for (var arg : arguments) {
            if (!comments.leading(arg).isEmpty() || !comments.trailing(arg).isEmpty()) return true;
        }
        return false;
    }

    /// Bracketed, comment-carrying list layout shared by argument lists, try-with-resources,
    /// and array initializers. {@code hard} uses hardLines (an array initializer always breaks
    /// one-per-line); the others use soft breaks that the surrounding group resolves.
    /// {@code trailingSep} appends the separator after the last item too (an array's trailing
    /// comma).
    private record ListStyle(String open, String close, String sep, boolean trailingSep, boolean hard) {}

    private static final ListStyle PAREN_COMMA = new ListStyle("(", ")", ",", false, false);
    private static final ListStyle PAREN_SEMI = new ListStyle("(", ")", ";", false, false);
    private static final ListStyle BRACE_COMMA = new ListStyle("{", "}", ",", true, true);

    /// Render a bracketed list whose items carry comments, one per line. Always breaks — a
    /// line comment must end its line. An {@code afterOpen} comment stays on the open-bracket
    /// line; a leading comment goes on its own line before its item; a trailing comment goes
    /// AFTER the separator (so a line comment can't swallow it); a {@code dangling} comment
    /// sits on its own line before the close bracket.
    private Doc renderList(List<ASTNode> items, List<Doc> itemDocs,
            List<Comment> afterOpen, List<Comment> dangling, ListStyle style) {
        Doc breakUnit = style.hard() ? hardLine() : line();
        Doc edgeBreak = style.hard() ? hardLine() : softLine();
        Doc open = text(style.open());
        for (var c : afterOpen) open = concat(open, text(" "), renderComment(c));
        var inner = new ArrayList<Doc>();
        inner.add(edgeBreak);
        int n = items.size();
        for (int i = 0; i < n; i++) {
            var item = items.get(i);
            for (var lc : comments.leading(item)) {
                inner.add(renderComment(lc));
                inner.add(breakUnit);
            }
            inner.add(itemDocs.get(i));
            if (style.trailingSep() || i < n - 1) inner.add(text(style.sep()));
            for (var tc : comments.trailing(item)) {
                inner.add(text(" "));
                inner.add(renderComment(tc));
            }
            if (i < n - 1) inner.add(breakUnit);
        }
        for (var dc : dangling) {
            inner.add(breakUnit);
            inner.add(renderComment(dc));
        }
        return breakGroup(concat(open, indent(concat(inner)), edgeBreak, text(style.close())));
    }

    /// Render an argument list whose arguments carry comments (or that has a comment right
    /// after `(`), one per line. See {@link #renderList}.
    private Doc argsWithComments(List<ASTNode> arguments, List<Doc> argDocs,
            List<Comment> afterOpenParen, List<Comment> beforeClose) {
        return renderList(arguments, argDocs, afterOpenParen, beforeClose, PAREN_COMMA);
    }

    // Detect uniform tuple-per-line + smaller trailing group (e.g. [2,2,2,1]). Returns sizes or null.
    private List<Integer> computeTupleGroupSizes(List<ASTNode> arguments) {
        if (compilationUnit == null || arguments.size() < 3) return null;
        var sizes = new ArrayList<Integer>();
        int currentLine = compilationUnit.getLineNumber(arguments.getFirst().getStartPosition());
        int currentCount = 1;
        for (int i = 1; i < arguments.size(); i++) {
            int line = compilationUnit.getLineNumber(arguments.get(i).getStartPosition());
            if (line == currentLine) {
                currentCount++;
            } else {
                sizes.add(currentCount);
                currentLine = line;
                currentCount = 1;
            }
        }
        sizes.add(currentCount);

        if (sizes.size() < 2) return null;
        int n = sizes.getFirst();
        if (n < 2) return null;
        for (int i = 1; i < sizes.size() - 1; i++) {
            if (sizes.get(i) != n) return null;
        }
        if (sizes.getLast() >= n) return null;
        return sizes;
    }

    // Alt 1 flat; Alt 2 chunked with HardLine (always fails fits() so it's the fallback).
    // Args are pre-flattened to text so chunk-internal layout decisions can't break.
    // This makes tuple-args mean "user opted into per-line rows; render rows verbatim
    // even if one row exceeds the line budget." Rest-aware fits inside an inner arg
    // would otherwise see all of its sibling args in the same row and decide to break
    // the inner expression, mangling the row layout and breaking idempotence.
    private Doc buildTupleArgs(List<Doc> argDocs, List<Integer> sizes) {
        var flatArgs = new ArrayList<Doc>();
        for (var arg : argDocs) {
            flatArgs.add(text(new DocPrinter(Integer.MAX_VALUE).print(arg)));
        }

        var flatAlt = concat(
                text("("),
                join(concat(text(","), text(" ")), flatArgs),
                text(")")
        );

        var chunkDocs = new ArrayList<Doc>();
        int idx = 0;
        for (int size : sizes) {
            var chunk = flatArgs.subList(idx, idx + size);
            chunkDocs.add(join(concat(text(","), text(" ")), chunk));
            idx += size;
        }

        var chunkedAlt = concat(
                text("("),
                indent(concat(
                        hardLine(),
                        join(concat(text(","), hardLine()), chunkDocs)
                )),
                hardLine(),
                text(")")
        );

        return conditionalGroup(List.of(flatAlt, chunkedAlt));
    }

    @Override
    public boolean visit(SuperMethodInvocation node) {
        var parts = new ArrayList<Doc>();

        var qualifier = getProperty(node, SuperMethodInvocation.QUALIFIER_PROPERTY);
        if (qualifier != null) {
            qualifier.accept(this);
            parts.add(result);
            parts.add(text("."));
        }

        parts.add(text("super."));

        visitTypeArguments(parts, node, SuperMethodInvocation.TYPE_ARGUMENTS_PROPERTY);

        var name = getProperty(node, SuperMethodInvocation.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        var arguments = getProperty(node, SuperMethodInvocation.ARGUMENTS_PROPERTY);
        if (arguments.isEmpty()) {
            parts.add(text("()"));
        } else {
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
            }
            parts.add(group(concat(
                    text("("),
                    indent(concat(
                            softLine(),
                            join(concat(text(","), line()), argDocs)
                    )),
                    softLine(),
                    text(")")
            )));
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, ClassInstanceCreation.EXPRESSION_PROPERTY);
        if (expression != null) {
            expression.accept(this);
            parts.add(result);
            parts.add(text("."));
        }

        parts.add(text("new "));

        var typeArguments = getProperty(node, ClassInstanceCreation.TYPE_ARGUMENTS_PROPERTY);
        if (!typeArguments.isEmpty()) {
            parts.add(text("<"));
            parts.add(join(concat(text(","), line()), typeArguments.stream().map(t -> {
                t.accept(this);
                return result;
            }).toList()));
            parts.add(text(">"));
            parts.add(space());
        }

        var type = getProperty(node, ClassInstanceCreation.TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);

        var arguments = getProperty(node, ClassInstanceCreation.ARGUMENTS_PROPERTY);
        // A comment after `(` (`new X( // c`) attaches to the type; a dangling one before
        // `)` to the call; each argument carries its own comments — same as a method call.
        var ctorAfterOpen = comments != null ? comments.trailing(type) : List.<Comment>of();
        var ctorBeforeClose = comments != null ? comments.dangling(node) : List.<Comment>of();
        if (arguments.isEmpty()) {
            formatArguments(parts, List.of(), ctorAfterOpen, ctorBeforeClose);
        } else {
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
            }
            if (!ctorAfterOpen.isEmpty() || !ctorBeforeClose.isEmpty() || argumentsHaveComments(arguments)) {
                parts.add(argsWithComments(arguments, argDocs, ctorAfterOpen, ctorBeforeClose));
            } else {
                parts.add(group(concat(
                        text("("),
                        indent(concat(
                                softLine(),
                                join(concat(text(","), line()), argDocs)
                        )),
                        softLine(),
                        text(")")
                )));
            }
        }

        var anonymousClass = getProperty(node, ClassInstanceCreation.ANONYMOUS_CLASS_DECLARATION_PROPERTY);
        if (anonymousClass != null) {
            parts.add(space());

            anonymousClass.accept(this);
            parts.add(result);
        }

        var partsDoc = concat(parts);
        result = anonymousClass != null
                ? conditionalGroup(List.of(partsDoc, partsDoc))
                : partsDoc;
        return false;
    }

    @Override
    public boolean visit(CreationReference node) {
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, CreationReference.TYPE_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text("::"));

        visitTypeArguments(parts, node, CreationReference.TYPE_ARGUMENTS_PROPERTY);

        parts.add(text("new"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(SuperMethodReference node) {
        var parts = new ArrayList<Doc>();

        var qualifier = getProperty(node, SuperMethodReference.QUALIFIER_PROPERTY);
        if (qualifier != null) {
            qualifier.accept(this);
            parts.add(result);
        }

        parts.add(text("super::"));

        var typeArguments = getProperty(node, SuperMethodReference.TYPE_ARGUMENTS_PROPERTY);
        if (!typeArguments.isEmpty()) {
            parts.add(text("<"));
            parts.add(join(concat(text(","), line()), typeArguments.stream().map(t -> {
                t.accept(this);
                return result;
            }).toList()));
            parts.add(text(">"));
            parts.add(space());
        }

        var name = getProperty(node, SuperMethodReference.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ExpressionMethodReference node) {
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, ExpressionMethodReference.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text("::"));

        visitTypeArguments(parts, node, ExpressionMethodReference.TYPE_ARGUMENTS_PROPERTY);

        var name = getProperty(node, ExpressionMethodReference.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(TypeMethodReference node) {
        var parts = new ArrayList<Doc>();

        var type = getProperty(node, TypeMethodReference.TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);

        parts.add(text("::"));

        visitTypeArguments(parts, node, TypeMethodReference.TYPE_ARGUMENTS_PROPERTY);

        var name = getProperty(node, TypeMethodReference.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    private void visitTypeArguments(List<Doc> parts, ASTNode node, ChildListPropertyDescriptor property) {
        var typeArguments = getProperty(node, property);
        if (typeArguments.isEmpty()) return;

        var typeDocs = new ArrayList<Doc>();
        for (var typeArg : typeArguments) {
            typeArg.accept(this);
            typeDocs.add(result);
        }
        parts.add(text("<"));
        parts.add(join(concat(text(","), space()), typeDocs));
        parts.add(text(">"));
    }

    @Override
    public boolean visit(LambdaExpression node) {
        var parts = new ArrayList<Doc>();

        var paramsDoc = new ArrayList<Doc>();
        var parameters = getProperty(node, LambdaExpression.PARAMETERS_PROPERTY);
        for (var param : parameters) {
            param.accept(this);
            paramsDoc.add(result);
        }
        Doc paramListDoc;
        if (parameters.isEmpty()) {
            // `()` for a zero-arg lambda is always a single token. Routing it
            // through the wrap-with-softLines path used for one-or-more params
            // produced an empty `(\n)` whenever the enclosing group broke —
            // a `(` on one line, blank indent, `)` on the next.
            paramListDoc = text("()");
        } else if (parameters.size() == 1 && parameters.getFirst() instanceof VariableDeclarationFragment) {
            paramListDoc = paramsDoc.getFirst();
        } else {
            paramListDoc = concat(text("("), indent(concat(
                    softLine(),
                    join(concat(text(","), line()), paramsDoc)
            )), softLine(), text(")"));
        }
        var body = getProperty(node, LambdaExpression.BODY_PROPERTY);
        body.accept(this);
        var bodyDoc = result;

        // A comment after the arrow (`ctx -> // c\n body`) attaches as a trailing comment of
        // the (last) parameter or a leading comment of the body; emit it after `->` and push
        // the body onto its own indented line so a line comment can't swallow it.
        var arrowComments = new ArrayList<Comment>();
        if (comments != null && !parameters.isEmpty()) arrowComments.addAll(comments.trailing(parameters.getLast()));
        if (comments != null) arrowComments.addAll(comments.leading(body));

        if (!arrowComments.isEmpty()) {
            Doc arrow = concat(paramListDoc, text(" ->"));
            boolean lastWasLine = false;
            for (var c : arrowComments) {
                // A line comment ends its line, so a following comment can't share it —
                // put it on its own indented line instead of merging the two (which would
                // comment the second one out).
                if (lastWasLine) {
                    arrow = concat(arrow, indent(concat(hardLine(), renderComment(c))));
                } else {
                    arrow = concat(arrow, text(" "), renderComment(c));
                }
                lastWasLine = c instanceof LineComment;
            }
            parts.add(arrow);
            parts.add(indent(concat(hardLine(), bodyDoc)));
            result = concat(parts);
            return false;
        }

        parts.add(group(concat(paramListDoc, text(" -> "))));
        parts.add(bodyDoc);

        var partsDoc = concat(parts);
        result = body instanceof Block
                ? conditionalGroup(List.of(partsDoc, partsDoc))
                : partsDoc;
        return false;
    }

    @Override
    public boolean visit(ArrayCreation node) {
        var parts = new ArrayList<Doc>();

        var initializer = getProperty(node, ArrayCreation.INITIALIZER_PROPERTY);

        var type = getProperty(node, ArrayCreation.TYPE_PROPERTY);
        // We need to handle the type manually because dimensions with
        // sizes go between the element type and the empty bracket pairs.
        // e.g. new int[5][] — element type is int, one sized dim, one unsized dim.
        var elementType = getProperty((ArrayType) type, ArrayType.ELEMENT_TYPE_PROPERTY);
        elementType.accept(this);
        parts.add(text("new "));
        parts.add(result);

        var dimensions = getProperty(node, ArrayCreation.DIMENSIONS_PROPERTY);
        for (var dimension : dimensions) {
            parts.add(text("["));
            dimension.accept(this);
            parts.add(result);
            parts.add(text("]"));
        }

        // Add remaining empty [] pairs for unsized dimensions
        var allDims = getProperty((ArrayType) type, ArrayType.DIMENSIONS_PROPERTY);
        for (int i = dimensions.size(); i < allDims.size(); i++) {
            parts.add(text("[]"));
        }

        if (initializer != null) {
            parts.add(text(" "));
            initializer.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ArrayInitializer node) {
        var expressions = getProperty(node, ArrayInitializer.EXPRESSIONS_PROPERTY);
        var dangling = comments != null ? comments.dangling(node) : List.<Comment>of();

        if (expressions.isEmpty()) {
            if (dangling.isEmpty()) {
                result = text("{}");
            } else {
                var inner = new ArrayList<Doc>();
                for (var dc : dangling) {
                    inner.add(hardLine());
                    inner.add(renderComment(dc));
                }
                result = breakGroup(concat(text("{"), indent(concat(inner)), hardLine(), text("}")));
            }
            return false;
        }

        var elemDocs = new ArrayList<Doc>();
        for (var expr : expressions) {
            expr.accept(this);
            elemDocs.add(result);
        }

        // An element carrying a comment (`{ // c\n a, // c\n b }`) or a dangling comment
        // before `}` forces a one-per-line break; the element visitors don't emit these.
        if (!dangling.isEmpty() || elementsHaveComments(expressions)) {
            result = arrayWithComments(expressions, elemDocs, dangling);
            return false;
        }

        var flat = concat(
                text("{"),
                join(concat(text(","), space()), elemDocs),
                text("}")
        );

        var breaking = concat(
                text("{"),
                indent(concat(
                        hardLine(),
                        join(concat(text(","), hardLine()), elemDocs),
                        text(",")
                )),
                hardLine(),
                text("}")
        );

        result = conditionalGroup(List.of(flat, breaking));
        return false;
    }

    private boolean elementsHaveComments(List<ASTNode> elems) {
        if (comments == null) return false;
        for (var e : elems) {
            if (!comments.leading(e).isEmpty() || !comments.trailing(e).isEmpty()) return true;
        }
        return false;
    }

    /// Like {@link #elementsHaveComments} but ignores comments that belong on the body brace
    /// (so a lone `implements Foo { // note` doesn't force the interface list to break).
    private boolean interfaceListHasComments(List<ASTNode> elems, List<Comment> braceTrailing) {
        if (comments == null) return false;
        for (var e : elems) {
            if (!comments.leading(e).isEmpty()) return true;
            for (var tc : comments.trailing(e)) {
                if (!braceTrailing.contains(tc)) return true;
            }
        }
        return false;
    }

    /// A comment written after a type body's opening brace on the same line (`class X { // c`,
    /// `... implements Foo { // c`) attaches to the nearest preceding header node (the name or
    /// last interface) as a trailing comment, since `{` isn't an AST node. Returns such
    /// comments — those whose position is past the body brace — so they can be rendered glued
    /// to the `{` instead of being dropped or, worse, swallowing the brace on a line comment.
    private List<Comment> braceTrailingComments(List<ASTNode> headerNodes) {
        if (comments == null || headerNodes.isEmpty()) return List.of();
        int headerEnd = 0;
        for (var n : headerNodes) headerEnd = Math.max(headerEnd, n.getStartPosition() + n.getLength());
        int bracePos = source.indexOf('{', headerEnd);
        if (bracePos < 0) return List.of();
        var res = new ArrayList<Comment>();
        for (var n : headerNodes) {
            for (var tc : comments.trailing(n)) {
                if (tc.getStartPosition() > bracePos) res.add(tc);
            }
        }
        return res;
    }

    /// Render try-with-resources `(r1; r2; ...)` one per line when a resource carries a
    /// comment. See {@link #renderList}.
    private Doc resourcesWithComments(List<ASTNode> resources, List<Doc> resourceDocs) {
        return renderList(resources, resourceDocs, List.of(), List.of(), PAREN_SEMI);
    }

    /// Render an array initializer whose elements carry comments, one per line with the usual
    /// trailing comma. See {@link #renderList}.
    private Doc arrayWithComments(List<ASTNode> elems, List<Doc> elemDocs, List<Comment> dangling) {
        return renderList(elems, elemDocs, List.of(), dangling, BRACE_COMMA);
    }

    @Override
    public boolean visit(ArrayAccess node) {
        // Are we on a method call's receiver spine (`arr[i].m(...)`)? Capture it before
        // recursing into children, which clobber the field.
        boolean asReceiver = flatReceiver;

        var array = getProperty(node, ArrayAccess.ARRAY_PROPERTY);
        // The array part continues the receiver spine, so `arr[i][j].m(...)` stays fully
        // glued; the index is its own expression and keeps its break points.
        flatReceiver = asReceiver;
        array.accept(this);
        var arrayDoc = result;

        var index = getProperty(node, ArrayAccess.INDEX_PROPERTY);
        flatReceiver = false;
        index.accept(this);
        var indexDoc = result;

        var flat = concat(arrayDoc, text("["), indexDoc, text("]"));
        if (asReceiver) {
            // Receiver position: stay glued and let the trailing call's args wrap instead
            // of exploding the brackets. (Mirrors isNameLikeReceiver, which already routes
            // an array-access receiver onto the per-call path.)
            result = flat;
            return false;
        }
        var breaking = concat(arrayDoc, text("["), indent(concat(softLine(), indexDoc)), softLine(),
                              text("]"));
        result = conditionalGroup(List.of(flat, breaking));
        return false;
    }

    @Override
    public boolean visit(CastExpression node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("("));

        var type = getProperty(node, CastExpression.TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);

        parts.add(text(")"));
        parts.add(space());

        var expression = getProperty(node, CastExpression.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(TypeLiteral node) {
        var parts = new ArrayList<Doc>();

        var type = getProperty(node, TypeLiteral.TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);

        parts.add(text(".class"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ThisExpression node) {
        var qualifier = getProperty(node, ThisExpression.QUALIFIER_PROPERTY);
        if (qualifier == null) {
            result = text("this");
        } else {
            qualifier.accept(this);
            result = concat(result, text(".this"));
        }
        return false;
    }

    @Override
    public boolean visit(NullLiteral node) {
        result = text("null");
        return false;
    }

    @Override
    public boolean visit(CharacterLiteral node) {
        result = text(node.getEscapedValue());
        return false;
    }

    @Override
    public boolean visit(BooleanLiteral node) {
        result = text(Boolean.toString(node.booleanValue()));
        return false;
    }

    @Override
    public boolean visit(StringLiteral node) {
        result = text(node.getEscapedValue());
        return false;
    }

    @Override
    public boolean visit(TextBlock node) {
        result = text(node.getEscapedValue());
        return false;
    }

    @Override
    public boolean visit(SimpleName node) {
        result = text(node.getIdentifier());
        return false;
    }

    @Override
    public boolean visit(QualifiedName node) {
        var parts = new ArrayList<Doc>();

        var qualifier = getProperty(node, QualifiedName.QUALIFIER_PROPERTY);
        qualifier.accept(this);
        parts.add(result);

        parts.add(text("."));

        var name = getProperty(node, QualifiedName.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(NumberLiteral node) {
        result = text(node.getToken());
        return false;
    }

    @Override
    public boolean visit(PrimitiveType node) {
        var parts = new ArrayList<Doc>();

        visitAnnotations(parts, node, PrimitiveType.ANNOTATIONS_PROPERTY, false);

        parts.add(text(node.getPrimitiveTypeCode().toString()));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(SimpleType node) {
        var parts = new ArrayList<Doc>();

        visitAnnotations(parts, node, SimpleType.ANNOTATIONS_PROPERTY, false);

        parts.add(text(node.getName().getFullyQualifiedName()));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(QualifiedType node) {
        var parts = new ArrayList<Doc>();

        var qualifier = getProperty(node, QualifiedType.QUALIFIER_PROPERTY);
        qualifier.accept(this);
        parts.add(result);

        parts.add(text("."));

        visitAnnotations(parts, node, QualifiedType.ANNOTATIONS_PROPERTY, false);

        var name = getProperty(node, QualifiedType.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(NameQualifiedType node) {
        var parts = new ArrayList<Doc>();

        var qualifier = getProperty(node, NameQualifiedType.QUALIFIER_PROPERTY);
        qualifier.accept(this);
        parts.add(result);

        parts.add(text("."));

        visitAnnotations(parts, node, NameQualifiedType.ANNOTATIONS_PROPERTY, false);

        var name = getProperty(node, NameQualifiedType.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(WildcardType node) {
        var parts = new ArrayList<Doc>();

        visitAnnotations(parts, node, WildcardType.ANNOTATIONS_PROPERTY, false);

        parts.add(text("?"));

        var bound = getProperty(node, WildcardType.BOUND_PROPERTY);
        if (bound != null) {
            if (node.isUpperBound()) {
                parts.add(text(" extends "));
            } else {
                parts.add(text(" super "));
            }
            bound.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ParameterizedType node) {
        var parts = new ArrayList<Doc>();

        var type = getProperty(node, ParameterizedType.TYPE_PROPERTY);
        type.accept(this);
        parts.add(result);

        parts.add(text("<"));
        var typeArgDocs = new ArrayList<Doc>();
        var typeArgs = getProperty(node, ParameterizedType.TYPE_ARGUMENTS_PROPERTY);
        for (var typeArg : typeArgs) {
            typeArg.accept(this);
            typeArgDocs.add(result);
        }
        parts.add(join(concat(text(","), space()), typeArgDocs));
        parts.add(text(">"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ArrayType node) {
        var elementType = getProperty(node, ArrayType.ELEMENT_TYPE_PROPERTY);
        elementType.accept(this);
        var parts = new ArrayList<Doc>();
        parts.add(result);

        var dimensionsList = getProperty(node, ArrayType.DIMENSIONS_PROPERTY);
        for (var dim : dimensionsList) {
            // A dimension carrying type annotations renders as `@Foo []` and needs a space
            // separating it from the preceding element type or dimension (`byte @Nullable []`,
            // `int @Nullable [] @NonNull []`); an unannotated dimension stays glued (`byte[]`,
            // `int[][]`). Delegate to visit(Dimension) so the annotations aren't dropped.
            if (!getProperty(dim, Dimension.ANNOTATIONS_PROPERTY).isEmpty()) parts.add(space());
            dim.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(Dimension node) {
        var parts = new ArrayList<Doc>();

        visitAnnotations(parts, node, Dimension.ANNOTATIONS_PROPERTY, false);

        parts.add(text("[]"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(IntersectionType node) {
        var parts = new ArrayList<Doc>();

        var types = getProperty(node, IntersectionType.TYPES_PROPERTY);
        for (int i = 0; i < types.size(); i++) {
            var type = types.get(i);
            type.accept(this);
            parts.add(result);

            if (i < types.size() - 1) {
                parts.add(text(" & "));
            }
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(UnionType node) {
        var parts = new ArrayList<Doc>();

        var types = getProperty(node, UnionType.TYPES_PROPERTY);
        for (int i = 0; i < types.size(); i++) {
            var type = types.get(i);
            type.accept(this);
            parts.add(result);

            if (i < types.size() - 1) {
                parts.add(text(" | "));
            }
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(CaseDefaultExpression node) {
        result = text("default");
        return false;
    }

    // annotations

    @Override
    public boolean visit(MarkerAnnotation node) {
        var parts = new ArrayList<Doc>();
        parts.add(text("@"));

        var typeName = getProperty(node, MarkerAnnotation.TYPE_NAME_PROPERTY);
        typeName.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        var parts = new ArrayList<Doc>();
        parts.add(text("@"));

        var typeName = getProperty(node, SingleMemberAnnotation.TYPE_NAME_PROPERTY);
        typeName.accept(this);
        parts.add(result);

        parts.add(text("("));

        var value = getProperty(node, SingleMemberAnnotation.VALUE_PROPERTY);
        value.accept(this);
        parts.add(result);

        parts.add(text(")"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(NormalAnnotation node) {
        var parts = new ArrayList<Doc>();
        parts.add(text("@"));

        var typeName = getProperty(node, NormalAnnotation.TYPE_NAME_PROPERTY);
        typeName.accept(this);
        parts.add(result);

        // An annotation's `(values)` formats exactly like a call's `(args)`, including
        // comment handling: a comment after `(` attaches to the type name, a dangling one
        // before `)` to the annotation, and a member-value-pair carries its own comments.
        var values = getProperty(node, NormalAnnotation.VALUES_PROPERTY);
        var afterOpen = comments != null ? comments.trailing(typeName) : List.<Comment>of();
        var beforeClose = comments != null ? comments.dangling(node) : List.<Comment>of();
        if (values.isEmpty()) {
            formatArguments(parts, List.of(), afterOpen, beforeClose);
        } else {
            var valueDocs = new ArrayList<Doc>();
            for (var value : values) {
                value.accept(this);
                valueDocs.add(result);
            }
            if (!afterOpen.isEmpty() || !beforeClose.isEmpty() || argumentsHaveComments(values)) {
                parts.add(argsWithComments(values, valueDocs, afterOpen, beforeClose));
            } else {
                parts.add(group(concat(
                        text("("),
                        indent(concat(
                                softLine(),
                                join(concat(text(","), line()), valueDocs)
                        )),
                        softLine(),
                        text(")")
                )));
            }
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(MemberValuePair node) {
        var parts = new ArrayList<Doc>();

        var name = getProperty(node, MemberValuePair.NAME_PROPERTY);
        name.accept(this);
        parts.add(result);

        parts.add(text(" = "));

        var value = getProperty(node, MemberValuePair.VALUE_PROPERTY);
        value.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    // javadocs

    @Override
    public boolean visit(Javadoc node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(JavaDocRegion node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(JavaDocTextElement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(MemberRef node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(MethodRef node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(MethodRefParameter node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TagElement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TagProperty node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TextElement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    // module-info nodes (ModuleDeclaration and its directives) are rendered by the companion
    // ModuleInfoToDoc, dispatched from visit(CompilationUnit). They are never reached through
    // visitor dispatch, so no visit(...) overrides for them live here.

    // nodes to always be handled by their parents.

    @Override
    public boolean visit(LineComment node) {
        // Line comments are handled via CompilationUnit.getCommentList(), not via visitor dispatch
        return false;
    }

    @Override
    public boolean visit(BlockComment node) {
        // Block comments are handled via CompilationUnit.getCommentList(), not via visitor dispatch
        return false;
    }

    // Helpers

    private static @NullUnmarked ASTNode getProperty(ASTNode node, ChildPropertyDescriptor property) {
        return (ASTNode) node.getStructuralProperty(property);
    }

    @SuppressWarnings("unchecked")
    private static List<ASTNode> getProperty(ASTNode node, ChildListPropertyDescriptor property) {
        return (List<ASTNode>) node.getStructuralProperty(property);
    }

    private int blankLinesBetweenPositions(int from, int to) {
        int newlines = 0;
        for (int i = from; i < to; i++) {
            if (source.charAt(i) == '\n') newlines++;
        }
        return Math.max(0, Math.min(newlines - 1, 1));
    }

    int blankLinesBetween(ASTNode first, ASTNode second) {
        if (source == null) return 1;
        return blankLinesBetweenPositions(
                first.getStartPosition() + first.getLength(),
                second.getStartPosition());
    }

    private int blankLinesAfterOpenBrace(ASTNode node, ASTNode firstBodyDecl) {
        if (source == null) return 1;
        int firstBodyStart = firstBodyDecl.getStartPosition();
        int pos = firstBodyStart - 1;
        while (pos >= node.getStartPosition() && source.charAt(pos) != '{') {
            pos--;
        }
        return blankLinesBetweenPositions(pos + 1, firstBodyStart);
    }

    private int blankLinesBeforeCloseBrace(ASTNode node, ASTNode lastBodyDecl) {
        if (source == null) return 1;
        int lastBodyEnd = lastBodyDecl.getStartPosition() + lastBodyDecl.getLength();
        int closeBrace = node.getStartPosition() + node.getLength() - 1;
        return blankLinesBetweenPositions(lastBodyEnd, closeBrace);
    }

    void visitJavadoc(List<Doc> parts, ASTNode node, ChildPropertyDescriptor property) {
        // Check for legacy /** ... */ javadoc via the AST
        var javadoc = getProperty(node, property);
        if (javadoc != null) {
            // The Javadoc node is in cu.getCommentList() and is attached by CommentMap like any
            // other comment, but it renders here (not through renderComment). Mark it emitted so
            // the ledger's conservation law balances instead of flagging every documented decl.
            emitted.add((Comment) javadoc);
            int startPos = javadoc.getStartPosition();
            var text = source.substring(startPos, startPos + javadoc.getLength());
            // Compute the column of /** to strip that indentation from subsequent lines
            int col = 0;
            for (int i = startPos - 1; i >= 0 && source.charAt(i) != '\n'; i--) col++;
            appendCommentLines(parts, text, col);
            return;
        }

        // Check for markdown /// javadoc (not recognized by Eclipse JDT as Javadoc)
        int declStart = node.getStartPosition();
        // Walk backwards past whitespace to find preceding lines
        int pos = declStart - 1;
        while (pos >= 0 && source.charAt(pos) == ' ') {
            pos--;
        }
        if (pos >= 0 && source.charAt(pos) == '\n') pos--;

        // Collect /// lines going backwards
        var mdLines = new ArrayList<String>();
        while (pos >= 0) {
            // Find start of this line
            int lineEnd = pos;
            int lineStart = pos;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }
            var line = source.substring(lineStart, lineEnd + 1);
            var trimmed = line.stripLeading();
            if (trimmed.startsWith("///")) {
                mdLines.add(0, trimmed);
                // Move to previous line
                pos = lineStart - 1;
                if (pos >= 0 && source.charAt(pos) == '\n') pos--;
            } else {
                break;
            }
        }

        if (!mdLines.isEmpty()) {
            appendCommentLines(parts, String.join("\n", mdLines), 0);
        }
    }

    private Doc renderLineComment(LineComment lc) {
        return text(source.substring(lc.getStartPosition(), lc.getStartPosition() + lc.getLength()).stripTrailing());
    }

    private Doc renderBlockComment(BlockComment bc) {
        return renderSourceRange(bc.getStartPosition(), bc.getLength());
    }

    /// Render source verbatim, stripping its original start column from continuation
    /// lines so it re-indents to the new column. Used for comments and incomplete try ASTs.
    private Doc renderSourceRange(int startPos, int length) {
        var text = source.substring(startPos, startPos + length);
        // Compute the original column to strip that indentation from subsequent lines
        int col = 0;
        for (int i = startPos - 1; i >= 0 && source.charAt(i) != '\n'; i--) col++;
        var parts = new ArrayList<Doc>();
        appendCommentLines(parts, text, col);
        // appendCommentLines adds a trailing hardLine after the last line; remove it
        if (!parts.isEmpty()) parts.removeLast();
        return concat(parts);
    }

    Doc renderComment(Comment comment) {
        // Ledger: renderComment is the universal leaf for the leading/trailing/dangling comment
        // channel, so recording here marks every such comment emitted exactly once.
        emitted.add(comment);
        if (comment instanceof LineComment lc) return renderLineComment(lc);
        if (comment instanceof BlockComment bc) return renderBlockComment(bc);
        // A Javadoc comment (`/** */` or markdown `///`) normally attaches to a declaration
        // and is rendered by visitJavadoc. One that isn't in a doc position — e.g. a stray
        // doc comment between two declarations, or commented-out `///` code in a method — is
        // classified as an ordinary leading/trailing/dangling comment and lands here. Render
        // its source verbatim with the same re-indentation as a block comment.
        if (comment instanceof Javadoc jd) return renderSourceRange(jd.getStartPosition(), jd.getLength());
        throw new IllegalArgumentException("Unexpected comment type: " + comment.getClass());
    }

    private boolean isOnSameLineAsOpenBrace(Block block, Comment comment) {
        if (source == null) return false;
        int bracePos = block.getStartPosition();
        int commentStart = comment.getStartPosition();
        for (int i = bracePos + 1; i < commentStart; i++) {
            if (source.charAt(i) == '\n') return false;
        }
        return true;
    }

    private void appendCommentLines(List<Doc> parts, String text, int indentToStrip) {
        var lines = text.split("\n");
        for (var line : lines) {
            // Strip up to indentToStrip leading spaces
            int i = 0;
            while (i < indentToStrip && i < line.length() && line.charAt(i) == ' ') {
                i++;
            }
            parts.add(text(line.substring(i).stripTrailing()));
            parts.add(hardLine());
        }
    }

}
