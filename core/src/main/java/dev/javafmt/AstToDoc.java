package dev.javafmt;

import org.eclipse.jdt.core.dom.*;
import org.jspecify.annotations.NullUnmarked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.javafmt.Doc.*;

final class AstToDoc extends ASTVisitor {

    private final String source;
    private final CommentMap comments;
    private Doc result;
    private CompilationUnit compilationUnit;

    /// True while rendering the receiver spine of a `recv.method(...)` call. Consumed
    /// by visit(ArrayAccess) to suppress its bracket-break alternative: an array access
    /// used as a call receiver stays glued (`arr[i].m(...)`) and the call's own argument
    /// list wraps instead of exploding the brackets into the orphan `arr[\n i\n].m(...)`.
    /// The bracket break stays available for a standalone access (`int x = arr[longIdx];`),
    /// the last resort when nothing else on the line can wrap. Saved/restored around the
    /// set in visit(MethodInvocation) and cleared before an array index, so it never leaks
    /// into siblings, arguments, or index subexpressions.
    private boolean flatReceiver = false;

    public AstToDoc(String source, CommentMap comments) {
        this.source = source;
        this.comments = comments;
    }

    public AstToDoc(String source) {
        this(source, null);
    }

    public AstToDoc() {
        this(null, null);
    }

    public Doc result() {
        return result;
    }

    @Override
    public boolean visit(CompilationUnit node) {
        for (var problem : node.getProblems()) {
            throw new RuntimeException("AST has problems: " + problem.getMessage());
        }

        this.compilationUnit = node;
        var parts = new ArrayList<Doc>();

        // todo MODULE_PROPERTY

        var package_ = getProperty(node, CompilationUnit.PACKAGE_PROPERTY);
        if (package_ != null) {
            package_.accept(this);
            parts.add(result);
            parts.add(hardLine());
            parts.add(hardLine());
        }

        var imports = getProperty(node, CompilationUnit.IMPORTS_PROPERTY);
        if (!imports.isEmpty()) {
            for (var imp : imports) {
                imp.accept(this);
                parts.add(result);
                parts.add(hardLine());
            }
            parts.add(hardLine());
        }

        var types = getProperty(node, CompilationUnit.TYPES_PROPERTY);
        for (int i = 0; i < types.size(); i++) {
            var type = types.get(i);
            var leading = comments != null ? comments.leading(type) : List.<Comment>of();

            if (!leading.isEmpty()) {
                ASTNode prev = null;
                for (var c : leading) {
                    if (prev != null && blankLinesBetween(prev, c) > 0) parts.add(hardLine());
                    parts.add(renderComment(c));
                    parts.add(hardLine());
                    prev = c;
                }
                if (blankLinesBetween(leading.getLast(), type) > 0) parts.add(hardLine());
            }

            type.accept(this);
            parts.add(result);

            var trailing = comments != null ? comments.trailing(type) : List.<Comment>of();
            if (!trailing.isEmpty()) {
                var prevDoc = parts.removeLast();
                Doc combined = prevDoc;
                for (var tc : trailing) {
                    combined = concat(combined, text(" "), renderComment(tc));
                }
                parts.add(combined);
            }

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

        result = concat(parts);
        return false;
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

        visitModifiers(parts, node, TypeDeclaration.MODIFIERS2_PROPERTY);

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

        visitSuperInterfaces(parts, node, TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY);

        visitPermits(parts, node, TypeDeclaration.PERMITS_TYPES_PROPERTY);

        visitBodyDeclarations(parts, node, TypeDeclaration.BODY_DECLARATIONS_PROPERTY, true);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(ImplicitTypeDeclaration node) {
        var parts = new ArrayList<Doc>();

        // TODO: how are any of the following present on an implicit class?
        // todo MODIFIERS2_PROPERTY
        // todo JAVADOC_PROPERTY
        // todo NAME_PROPERTY

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

        visitModifiers(parts, node, RecordDeclaration.MODIFIERS2_PROPERTY);

        parts.add(text("record"));
        parts.add(space());
        parts.add(text(node.getName().getIdentifier()));

        visitTypeArguments(parts, node, RecordDeclaration.TYPE_PARAMETERS_PROPERTY);

        var components = getProperty(node, RecordDeclaration.RECORD_COMPONENTS_PROPERTY);
        if (components.isEmpty()) {
            parts.add(text("()"));
        } else {
            var componentDocs = new ArrayList<Doc>();
            for (var component : components) {
                component.accept(this);
                componentDocs.add(result);
            }
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

        parts.add(space());

        visitSuperInterfaces(parts, node, RecordDeclaration.SUPER_INTERFACE_TYPES_PROPERTY);

        visitBodyDeclarations(parts, node, RecordDeclaration.BODY_DECLARATIONS_PROPERTY, true);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node, EnumDeclaration.JAVADOC_PROPERTY);

        visitModifiers(parts, node, EnumDeclaration.MODIFIERS2_PROPERTY);

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

        if (!constants.isEmpty()) {
            innerParts.add(hardLine());
            for (int i = 0; i < constants.size(); i++) {
                if (i > 0) {
                    innerParts.add(hardLine());
                    if (blankLinesBetween(constants.get(i - 1), constants.get(i)) > 0) {
                        innerParts.add(hardLine());
                    }
                }
                constants.get(i).accept(this);
                if (i < constants.size() - 1) {
                    innerParts.add(concat(result, text(",")));
                } else if (body.isEmpty()) {
                    innerParts.add(result);
                } else {
                    innerParts.add(concat(result, text(";")));
                }
            }
        }

        if (!body.isEmpty()) {
            if (!constants.isEmpty()) {
                innerParts.add(hardLine());
            }
            for (int i = 0; i < body.size(); i++) {
                innerParts.add(hardLine());
                if (i > 0 && blankLinesBetween(body.get(i - 1), body.get(i)) > 0) {
                    innerParts.add(hardLine());
                }
                body.get(i).accept(this);
                innerParts.add(result);
            }
        }

        parts.add(indent(concat(innerParts)));
        parts.add(hardLine());
        parts.add(text("}"));

        result = concat(parts);
        return false;
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

        visitModifiers(parts, node, AnnotationTypeDeclaration.MODIFIERS2_PROPERTY);

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

        visitModifiers(parts, node, AnnotationTypeMemberDeclaration.MODIFIERS2_PROPERTY);

        var type = getProperty(node, AnnotationTypeMemberDeclaration.TYPE_PROPERTY);
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
        var interfaces = getProperty(node, property);
        if (interfaces.isEmpty()) return;

        var keyword = node instanceof TypeDeclaration td && td.isInterface()
                ? "extends" : "implements";
        parts.add(text(keyword));
        parts.add(space());
        var ifaceDocs = new ArrayList<Doc>();
        for (var iface : interfaces) {
            iface.accept(this);
            ifaceDocs.add(result);
        }
        parts.add(join(concat(text(","), space()), ifaceDocs));
        parts.add(space());
    }

    private void visitPermits(List<Doc> parts, ASTNode node, ChildListPropertyDescriptor property) {
        var permits = getProperty(node, property);
        if (permits.isEmpty()) return;

        parts.add(text("permits "));
        var permitDocs = new ArrayList<Doc>();
        for (var permit : permits) {
            permit.accept(this);
            permitDocs.add(result);
        }
        parts.add(join(concat(text(","), space()), permitDocs));
        parts.add(space());
    }

    private void visitBodyDeclarations(
            List<Doc> parts,
            ASTNode node,
            ChildListPropertyDescriptor property,
            boolean withBraces
    ) {
        var body = getProperty(node, property);
        var dangling = comments != null ? comments.dangling(node) : List.<Comment>of();

        if (body.isEmpty() && dangling.isEmpty()) {
            if (withBraces) parts.add(text("{}"));
            return;
        }

        if (withBraces) parts.add(text("{"));

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
            visitAnnotations(parts, node, SingleVariableDeclaration.VARARGS_ANNOTATIONS_PROPERTY, false);
            parts.add(text("..."));
        }

        parts.add(space());
        parts.add(text(node.getName().getIdentifier()));

        // todo ChildListPropertyDescriptor EXTRA_DIMENSIONS2_PROPERTY

        // This is currently never used in java
        var initializer = getProperty(node, SingleVariableDeclaration.INITIALIZER_PROPERTY);
        if (initializer != null) throw new UnsupportedOperationException("initializer on SingleVariableDeclaration");

        result = concat(parts);
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

        visitModifiers(parts, node, FieldDeclaration.MODIFIERS2_PROPERTY);

        var type = getProperty(node, FieldDeclaration.TYPE_PROPERTY);
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

        visitModifiers(parts, node, MethodDeclaration.MODIFIERS2_PROPERTY);

        visitTypeArguments(parts, node, MethodDeclaration.TYPE_PARAMETERS_PROPERTY);
        if (!getProperty(node, MethodDeclaration.TYPE_PARAMETERS_PROPERTY).isEmpty()) {
            parts.add(space());
        }

        // todo CONSTRUCTOR_PROPERTY
        // todo COMPACT_CONSTRUCTOR_PROPERTY

        if (!node.isConstructor()) {
            var returnType = getProperty(node, MethodDeclaration.RETURN_TYPE2_PROPERTY);
            returnType.accept(this);
            parts.add(result);
            parts.add(space());
        }

        parts.add(text(node.getName().getIdentifier()));

        // todo EXTRA_DIMENSIONS_PROPERTY
        // todo EXTRA_DIMENSIONS2_PROPERTY

        var params = getProperty(node, MethodDeclaration.PARAMETERS_PROPERTY);
        if (params.isEmpty()) {
            parts.add(text("()"));
        } else {
            var paramDocs = new ArrayList<Doc>();
            for (var param : params) {
                param.accept(this);
                paramDocs.add(result);
            }
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

        // todo RECEIVER_TYPE_PROPERTY
        // todo RECEIVER_QUALIFIER_PROPERTY

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

    private void visitModifiers(List<Doc> doc, ASTNode node, ChildListPropertyDescriptor property) {
        var modifiers = getProperty(node, property);
        if (modifiers.isEmpty()) return;

        boolean inline = node instanceof SingleVariableDeclaration
                || node instanceof VariableDeclarationExpression
                || node instanceof VariableDeclarationStatement;

        boolean seenKeyword = false;
        for (int i = 0; i < modifiers.size(); i++) {
            var modifier = modifiers.get(i);
            if (modifier instanceof Modifier) {
                seenKeyword = true;
            }
            modifier.accept(this);

            if (modifier instanceof Annotation && !inline) {
                // Annotation after a keyword modifier (e.g. private static @Nullable)
                // is a type-use annotation — keep on same line as type
                if (seenKeyword) {
                    doc.add(result);
                    doc.add(space());
                } else {
                    doc.add(result);
                    doc.add(hardLine());
                }
            } else if (modifier instanceof Annotation) {
                doc.add(result);
                doc.add(space());
            } else {
                doc.add(result);
            }
        }
    }

    @Override
    public boolean visit(Modifier node) {
        result = text(node.getKeyword().toString() + " ");
        return false;
    }

    @Override
    public boolean visit(TypeParameter node) {
        var parts = new ArrayList<Doc>();

        visitAnnotations(parts, node, TypeParameter.MODIFIERS_PROPERTY, false);

        // todo MODIFIERS_PROPERTY (annotations on type params)

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
                for (var tc : trailing) {
                    combined = concat(combined, text(" "), renderComment(tc));
                    prevItem = tc;
                }
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
        var ifAndThen = headerWithBody(concat(text("if ("), condDoc, text(")")), thenStmt);

        var parts = new ArrayList<Doc>();
        parts.add(ifAndThen);

        var elseStmt = getProperty(node, IfStatement.ELSE_STATEMENT_PROPERTY);
        if (elseStmt != null) {
            // `} else` stays on the same line when the then-body uses braces;
            // otherwise `else` moves to its own line so a non-braced then is visible.
            if (thenStmt instanceof Block) {
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
        body.accept(this);
        var bodyDoc = result;
        if (body instanceof Block) {
            return concat(header, text(" "), bodyDoc);
        }
        return group(concat(header, indent(concat(line(), bodyDoc))));
    }

    @Override
    public boolean visit(WhileStatement node) {
        var expression = getProperty(node, WhileStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        var condDoc = result;

        var body = getProperty(node, WhileStatement.BODY_PROPERTY);
        result = headerWithBody(concat(text("while ("), condDoc, text(")")), body);
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
        for (var stmt : statements) {
            var leading = comments != null ? comments.leading(stmt) : List.<Comment>of();
            for (var lc : leading) {
                stmtParts.add(indent(concat(hardLine(), renderComment(lc))));
            }

            stmt.accept(this);
            Doc stmtDoc;
            boolean joinToRule = leading.isEmpty()
                    && prevStmt instanceof SwitchCase sc && sc.isSwitchLabeledRule();
            if (joinToRule) {
                stmtDoc = concat(space(), result);
            } else {
                stmtDoc = concat(hardLine(), result);
                if (!(stmt instanceof SwitchCase)) stmtDoc = indent(stmtDoc);
            }

            if (comments != null) {
                for (var tc : comments.trailing(stmt)) {
                    stmtDoc = concat(stmtDoc, text(" "), renderComment(tc));
                }
            }

            stmtParts.add(stmtDoc);
            prevStmt = (Statement) stmt;
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

        visitModifiers(parts, node, VariableDeclarationStatement.MODIFIERS2_PROPERTY);

        var type = getProperty(node, VariableDeclarationStatement.TYPE_PROPERTY);
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
        var name = text(node.getName().getIdentifier());
        var init = node.getInitializer();
        if (init == null) {
            result = name;
            return false;
        }
        init.accept(this);
        var initDoc = result;
        result = group(concat(name, text(" = "), initDoc));
        return false;
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
        parts.add(text("try "));

        var resources = getProperty(node, TryStatement.RESOURCES2_PROPERTY);
        if (!resources.isEmpty()) {
            var resourceParts = new ArrayList<Doc>();
            for (var resource : resources) {
                resource.accept(this);
                resourceParts.add(result);
            }
            parts.add(group(concat(
                    text("("),
                    indent(concat(
                            softLine(),
                            join(concat(text(";"), line()), resourceParts)
                    )),
                    softLine(),
                    text(")")
            )));
            parts.add(text(" "));
        }

        var body = getProperty(node, TryStatement.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        var catches = getProperty(node, TryStatement.CATCH_CLAUSES_PROPERTY);
        for (var catchClause : catches) {
            catchClause.accept(this);
            parts.add(result);
        }

        var finallyBlock = getProperty(node, TryStatement.FINALLY_PROPERTY);
        if (finallyBlock != null) {
            parts.add(text(" finally "));
            finallyBlock.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(CatchClause node) {
        var parts = new ArrayList<Doc>();
        parts.add(text(" catch ("));

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
        var parts = new ArrayList<Doc>();
        parts.add(text("assert "));

        var expression = getProperty(node, AssertStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        var message = getProperty(node, AssertStatement.MESSAGE_PROPERTY);
        if (message != null) {
            parts.add(text(" : "));
            message.accept(this);
            parts.add(result);
        }

        parts.add(text(";"));
        result = concat(parts);
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
        var parts = new ArrayList<Doc>();

        var left = getProperty(node, Assignment.LEFT_HAND_SIDE_PROPERTY);
        left.accept(this);
        parts.add(result);

        parts.add(space());
        parts.add(text(node.getOperator().toString()));
        parts.add(space());

        var right = getProperty(node, Assignment.RIGHT_HAND_SIDE_PROPERTY);
        right.accept(this);
        parts.add(result);

        result = concat(parts);
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

        var stmtParts = new ArrayList<Doc>();
        var statements = getProperty(node, SwitchExpression.STATEMENTS_PROPERTY);
        for (int i = 0; i < statements.size(); i++) {
            var statement = statements.get(i);
            statement.accept(this);

            if (i > 0 && statements.get(i - 1) instanceof SwitchCase sc && sc.isSwitchLabeledRule()) {
                result = concat(space(), result);
            } else {
                result = concat(hardLine(), result);
                if (!(statement instanceof SwitchCase))
                    result = indent(result);
            }

            stmtParts.add(result);
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
        collectSameOpOperands(node, op, operands);

        var operandDocs = new ArrayList<Doc>();
        for (var operand : operands) {
            operand.accept(this);
            operandDocs.add(result);
        }

        var tail = new ArrayList<Doc>();
        for (int i = 1; i < operandDocs.size(); i++) {
            tail.add(line());
            tail.add(text(operator + " "));
            tail.add(operandDocs.get(i));
        }
        result = group(concat(operandDocs.get(0), indent(concat(tail))));
        return false;
    }

    /// Walks the left spine of a same-operator InfixExpression chain, appending every
    /// leaf operand (in source order) into {@code out}. Stops descending at any node
    /// whose operator differs or that is not an InfixExpression (e.g.
    /// ParenthesizedExpression — parentheses correctly halt flattening).
    private void collectSameOpOperands(InfixExpression node, InfixExpression.Operator op, List<ASTNode> out) {
        var left = getProperty(node, InfixExpression.LEFT_OPERAND_PROPERTY);
        if (left instanceof InfixExpression leftInfix && leftInfix.getOperator() == op) {
            collectSameOpOperands(leftInfix, op, out);
        } else {
            out.add(left);
        }
        var right = getProperty(node, InfixExpression.RIGHT_OPERAND_PROPERTY);
        if (right instanceof InfixExpression rightInfix && rightInfix.getOperator() == op) {
            collectSameOpOperands(rightInfix, op, out);
        } else {
            out.add(right);
        }
        for (var ext : getProperty(node, InfixExpression.EXTENDED_OPERANDS_PROPERTY)) {
            if (ext instanceof InfixExpression extInfix && extInfix.getOperator() == op) {
                collectSameOpOperands(extInfix, op, out);
            } else {
                out.add(ext);
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
        formatArguments(parts, arguments);

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

        Doc rootDoc;
        int startIndex;
        if (rootExpr != null) {
            rootExpr.accept(this);
            rootDoc = result;
            startIndex = 0;
        } else {
            rootDoc = formatMethodCallNoDot(innermost);
            startIndex = 1;
        }

        var segDocs = new ArrayList<Doc>();
        for (int i = startIndex; i < chain.size(); i++) {
            segDocs.add(formatMethodCallWithDot(chain.get(i)));
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
        var firstSegGroup = group(indent(concat(boundaryLine(), segDocs.getFirst())));

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

        result = group(concat(outerParts));
    }

    private void collectMethodChain(MethodInvocation node, List<MethodInvocation> chain) {
        var expr = getProperty(node, MethodInvocation.EXPRESSION_PROPERTY);
        if (expr instanceof MethodInvocation inner) {
            collectMethodChain(inner, chain);
        }
        chain.add(node);
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
        parts.add(text(node.getName().getIdentifier()));
        formatArguments(parts, getProperty(node, MethodInvocation.ARGUMENTS_PROPERTY));
        return concat(parts);
    }

    private Doc formatMethodCallWithDot(MethodInvocation node) {
        var parts = new ArrayList<Doc>();
        parts.add(text("."));
        visitTypeArguments(parts, node, MethodInvocation.TYPE_ARGUMENTS_PROPERTY);
        parts.add(text(node.getName().getIdentifier()));
        formatArguments(parts, getProperty(node, MethodInvocation.ARGUMENTS_PROPERTY));
        return concat(parts);
    }

    private void formatArguments(List<Doc> parts, List<ASTNode> arguments) {
        if (arguments.isEmpty()) {
            parts.add(text("()"));
        } else {
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
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
        parts.add(group(concat(paramListDoc, text(" -> "))));

        var body = getProperty(node, LambdaExpression.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

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

        if (expressions.isEmpty()) {
            result = text("{}");
            return false;
        }

        var elemDocs = new ArrayList<Doc>();
        for (var expr : expressions) {
            expr.accept(this);
            elemDocs.add(result);
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
            parts.add(text("[]"));
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

        var values = getProperty(node, NormalAnnotation.VALUES_PROPERTY);
        if (values.isEmpty()) {
            parts.add(text("()"));
        } else {
            var valueDocs = new ArrayList<Doc>();
            for (var value : values) {
                value.accept(this);
                valueDocs.add(result);
            }
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

    // module-info nodes

    @Override
    public boolean visit(ModuleDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(RequiresDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ExportsDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(OpensDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(UsesDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ProvidesDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ModuleModifier node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ModuleQualifiedName node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

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

    @Override
    public boolean visit(EmptyStatement node) {
        throw new IllegalStateException("EmptyStatement should have been removed");
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

    private int blankLinesBetween(ASTNode first, ASTNode second) {
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

    private void visitJavadoc(List<Doc> parts, ASTNode node, ChildPropertyDescriptor property) {
        // Check for legacy /** ... */ javadoc via the AST
        var javadoc = getProperty(node, property);
        if (javadoc != null) {
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
        int startPos = bc.getStartPosition();
        var text = source.substring(startPos, startPos + bc.getLength());
        // Compute column of /* to strip that indentation from subsequent lines
        int col = 0;
        for (int i = startPos - 1; i >= 0 && source.charAt(i) != '\n'; i--) col++;
        var parts = new ArrayList<Doc>();
        appendCommentLines(parts, text, col);
        // appendCommentLines adds a trailing hardLine after the last line; remove it
        if (!parts.isEmpty()) parts.removeLast();
        return concat(parts);
    }

    private Doc renderComment(Comment comment) {
        if (comment instanceof LineComment lc) return renderLineComment(lc);
        if (comment instanceof BlockComment bc) return renderBlockComment(bc);
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
