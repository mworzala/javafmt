package black;

import org.eclipse.jdt.core.dom.*;
import org.jspecify.annotations.NullUnmarked;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static black.Doc.*;

@SuppressWarnings("unchecked")
public class AstToDoc extends ASTVisitor {

    private final String source;
    private Doc result;
    private CompilationUnit compilationUnit;

    private boolean lastResultGluesToEquals;

    public AstToDoc(String source) {
        this.source = source;
    }

    public AstToDoc() {
        this.source = null;
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
            // Collect comments before this type (after previous type, imports, package, or start of file)
            int beforeTypeStart;
            if (i > 0) {
                beforeTypeStart = types.get(i - 1).getStartPosition() + types.get(i - 1).getLength();
            } else if (!imports.isEmpty()) {
                var lastImport = imports.getLast();
                beforeTypeStart = lastImport.getStartPosition() + lastImport.getLength();
            } else if (package_ != null) {
                beforeTypeStart = package_.getStartPosition() + package_.getLength();
            } else {
                beforeTypeStart = 0;
            }
            var leadingComments = collectCommentsInRange(beforeTypeStart, types.get(i).getStartPosition());
            if (!leadingComments.isEmpty()) {
                ASTNode prev = null;
                for (var c : leadingComments) {
                    if (prev != null && blankLinesBetween(prev, c) > 0) {
                        parts.add(hardLine());
                    }
                    parts.add(renderComment(c));
                    parts.add(hardLine());
                    prev = c;
                }
                if (blankLinesBetween(leadingComments.getLast(), types.get(i)) > 0) {
                    parts.add(hardLine());
                }
            }

            types.get(i).accept(this);
            parts.add(result);

            // Collect line comments between this type and the next (or EOF)
            int afterTypeEnd = types.get(i).getStartPosition() + types.get(i).getLength();
            int nextBoundary = (i + 1 < types.size())
                    ? types.get(i + 1).getStartPosition()
                    : (source != null ? source.length() : afterTypeEnd);
            var trailingComments = collectCommentsInRange(afterTypeEnd, nextBoundary);

            if (trailingComments.isEmpty()) {
                parts.add(hardLine());
                parts.add(hardLine());
            } else {
                ASTNode prev = types.get(i);
                for (var c : trailingComments) {
                    parts.add(hardLine());
                    if (blankLinesBetween(prev, c) > 0) parts.add(hardLine());
                    parts.add(renderComment(c));
                    prev = c;
                }
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
                            line(""),
                            join(concat(text(","), line()), componentDocs)
                    )),
                    line(""),
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
                            line(""),
                            join(concat(text(","), line()), argDocs)
                    )),
                    line(""),
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

        // Find open/close brace positions to scope comment collection
        int nodeStart = node.getStartPosition();
        int nodeEnd = nodeStart + node.getLength();

        int commentRangeStart;
        if (withBraces) {
            int openBrace = nodeStart;
            while (openBrace < nodeEnd && source != null && source.charAt(openBrace) != '{') {
                openBrace++;
            }
            commentRangeStart = openBrace + 1;
        } else {
            commentRangeStart = nodeStart;
        }

        // Collect comments that appear at the body level (not inside a member's range)
        var bodyComments = collectCommentsInRange(commentRangeStart, nodeEnd - (withBraces ? 1 : 0));
        bodyComments.removeIf(c -> body.stream().anyMatch(decl ->
                                                                  c.getStartPosition() >= decl.getStartPosition() &&
                                                                          c.getStartPosition() < decl.getStartPosition() + decl.getLength()));

        // Build merged sorted list of body members and comments
        var items = new ArrayList<ASTNode>(body);
        items.addAll(bodyComments);
        items.sort(Comparator.comparingInt(ASTNode::getStartPosition));

        if (items.isEmpty()) {
            if (withBraces) parts.add(text("{}"));
            return;
        }

        if (withBraces) parts.add(text("{"));

        var bodyParts = new ArrayList<Doc>();
        if (withBraces) {
            bodyParts.add(hardLine());
            if (blankLinesAfterOpenBrace(node, items.getFirst()) > 0) {
                bodyParts.add(hardLine());
            }
        }
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);

            // Check if this comment is trailing on the same line as the previous item
            if (item instanceof Comment comment && i > 0 && isTrailingComment(items.get(i - 1), comment)) {
                var prevDoc = bodyParts.removeLast();
                bodyParts.add(concat(prevDoc, text(" "), renderComment(comment)));
                continue;
            }

            if (i > 0) {
                bodyParts.add(hardLine());
                if (blankLinesBetween(items.get(i - 1), items.get(i)) > 0) {
                    bodyParts.add(hardLine());
                }
            }
            if (item instanceof Comment comment) {
                bodyParts.add(renderComment(comment));
            } else {
                item.accept(this);
                bodyParts.add(result);
            }
        }
        var bodyDoc = concat(bodyParts);
        parts.add(withBraces ? indent(bodyDoc) : bodyDoc);

        if (withBraces) {
            parts.add(hardLine());
            if (blankLinesBeforeCloseBrace(node, items.getLast()) > 0) {
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
                            line(""),
                            join(concat(text(","), line()), paramDocs)
                    )),
                    line(""),
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

        // Collect comments in this block's range, filtering out those inside child statements
        var blockComments = collectCommentsInRange(
                node.getStartPosition(),
                node.getStartPosition() + node.getLength());
        blockComments.removeIf(c -> stmts.stream().anyMatch(stmt ->
                c.getStartPosition() >= stmt.getStartPosition() &&
                        c.getStartPosition() < stmt.getStartPosition() + stmt.getLength()));

        // Build merged sorted list of statements and comments
        var items = new ArrayList<ASTNode>(stmts);
        items.addAll(blockComments);
        items.sort(Comparator.comparingInt(ASTNode::getStartPosition));

        if (items.isEmpty()) {
            result = text("{}");
            return false;
        }

        // Check if the first item is a comment on the same line as the opening brace
        Doc openBraceTrailing = null;
        int startIdx = 0;
        if (items.getFirst() instanceof Comment firstComment && isOnSameLineAsOpenBrace(node, firstComment)) {
            openBraceTrailing = renderComment(firstComment);
            startIdx = 1;
        }

        var parts = new ArrayList<Doc>();
        for (int i = startIdx; i < items.size(); i++) {
            var item = items.get(i);

            // Check if this comment is trailing on the same line as the previous item
            if (item instanceof Comment comment && i > startIdx && isTrailingComment(items.get(i - 1), comment)) {
                // Append as trailing comment to the previous line
                var prevDoc = parts.removeLast();
                parts.add(concat(prevDoc, text(" "), renderComment(comment)));
                continue;
            }

            if (i > startIdx && blankLinesBetween(items.get(i - 1), items.get(i)) > 0) {
                parts.add(hardLine());
            }
            parts.add(hardLine());
            if (item instanceof Comment comment) {
                parts.add(renderComment(comment));
            } else {
                item.accept(this);
                parts.add(result);
            }
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
        var parts = new ArrayList<Doc>();

        parts.add(text("if ("));
        var expression = getProperty(node, IfStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);
        parts.add(text(") "));

        var thenStmt = getProperty(node, IfStatement.THEN_STATEMENT_PROPERTY);
        thenStmt.accept(this);
        parts.add(result);

        var elseStmt = getProperty(node, IfStatement.ELSE_STATEMENT_PROPERTY);
        if (elseStmt != null) {
            if (elseStmt instanceof IfStatement) {
                parts.add(text(" else "));
                elseStmt.accept(this);
                parts.add(result);
            } else {
                parts.add(text(" else "));
                elseStmt.accept(this);
                parts.add(result);
            }
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(WhileStatement node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("while ("));

        var expression = getProperty(node, WhileStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(") "));

        var body = getProperty(node, WhileStatement.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        result = concat(parts);
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
        var parts = new ArrayList<Doc>();

        parts.add(text("for ("));

        var initializers = getProperty(node, ForStatement.INITIALIZERS_PROPERTY);
        if (!initializers.isEmpty()) {
            var initDocs = new ArrayList<Doc>();
            for (var init : initializers) {
                init.accept(this);
                initDocs.add(result);
            }
            parts.add(join(concat(text(","), space()), initDocs));
        }
        parts.add(text("; "));

        var condition = getProperty(node, ForStatement.EXPRESSION_PROPERTY);
        if (condition != null) {
            condition.accept(this);
            parts.add(result);
        }
        parts.add(text("; "));

        var updaters = getProperty(node, ForStatement.UPDATERS_PROPERTY);
        if (!updaters.isEmpty()) {
            var updateDocs = new ArrayList<Doc>();
            for (var updater : updaters) {
                updater.accept(this);
                updateDocs.add(result);
            }
            parts.add(join(concat(text(","), space()), updateDocs));
        }

        parts.add(text(") "));

        var body = getProperty(node, ForStatement.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(EnhancedForStatement node) {
        var parts = new ArrayList<Doc>();

        parts.add(text("for ("));

        var parameter = getProperty(node, EnhancedForStatement.PARAMETER_PROPERTY);
        parameter.accept(this);
        parts.add(result);

        parts.add(text(" : "));

        var expression = getProperty(node, EnhancedForStatement.EXPRESSION_PROPERTY);
        expression.accept(this);
        parts.add(result);

        parts.add(text(") "));

        var body = getProperty(node, EnhancedForStatement.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        result = concat(parts);
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

        // Collect comments inside the switch body, filtering out those inside child statements
        var switchComments = collectCommentsInRange(
                node.getStartPosition(),
                node.getStartPosition() + node.getLength());
        switchComments.removeIf(c -> statements.stream().anyMatch(stmt ->
                c.getStartPosition() >= stmt.getStartPosition() &&
                        c.getStartPosition() < stmt.getStartPosition() + stmt.getLength()));

        var items = new ArrayList<ASTNode>(statements);
        items.addAll(switchComments);
        items.sort(Comparator.comparingInt(ASTNode::getStartPosition));

        var stmtParts = new ArrayList<Doc>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);

            // Check if this comment is trailing on the same line as the previous item
            if (item instanceof Comment comment && i > 0 && isTrailingComment(items.get(i - 1), comment)) {
                var prevDoc = stmtParts.removeLast();
                stmtParts.add(concat(prevDoc, text(" "), renderComment(comment)));
                continue;
            }

            // Find the previous non-comment item for layout decisions
            ASTNode prevItem = i > 0 ? items.get(i - 1) : null;

            if (item instanceof Comment comment) {
                // Comments follow same indentation rules as non-SwitchCase statements
                stmtParts.add(indent(concat(hardLine(), renderComment(comment))));
            } else {
                item.accept(this);
                if (prevItem instanceof SwitchCase sc && sc.isSwitchLabeledRule()) {
                    result = concat(space(), result);
                } else {
                    result = concat(hardLine(), result);
                    if (!(item instanceof SwitchCase))
                        result = indent(result);
                }
                stmtParts.add(result);
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
                            line(""),
                            join(concat(text(","), line()), patternDocs)
                    )),
                    line(""),
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
        var glue = lastResultGluesToEquals;
        lastResultGluesToEquals = false;

        if (glue) {
            result = group(concat(name, text(" = "), initDoc));
        } else {
            result = concat(
                    name,
                    group(concat(text(" ="), indent(concat(line(), initDoc))))
            );
        }
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
                            line(""),
                            join(concat(text(","), line()), argDocs)
                    )),
                    line(""),
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
                            line(""),
                            join(concat(text(","), line()), argDocs)
                    )),
                    line(""),
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
            parts.add(text("("));

            var resourceParts = new ArrayList<Doc>();
            for (var resource : resources) {
                resource.accept(this);
                resourceParts.add(result);
            }
            parts.add(group(join(concat(text(";"), line()), resourceParts)));

            parts.add(text(") "));
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

        var fragments = getProperty(node, VariableDeclarationExpression.FRAGMENTS_PROPERTY);
        for (var frag : fragments) {
            parts.add(space());

            frag.accept(this);
            parts.add(result);
        }

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
        lastResultGluesToEquals = true;
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
        var parts = new ArrayList<Doc>();

        var left = getProperty(node, InfixExpression.LEFT_OPERAND_PROPERTY);
        left.accept(this);
        parts.add(result);

        var op = node.getOperator();
        var operator = op.toString();

        var right = getProperty(node, InfixExpression.RIGHT_OPERAND_PROPERTY);
        right.accept(this);
        var rightDoc = result;

        var extendedOperands = getProperty(node, InfixExpression.EXTENDED_OPERANDS_PROPERTY);

        // Only wrap for logical/bitwise/comparison operators, not arithmetic
        boolean shouldWrap = op == InfixExpression.Operator.CONDITIONAL_AND
                || op == InfixExpression.Operator.CONDITIONAL_OR
                || op == InfixExpression.Operator.AND
                || op == InfixExpression.Operator.OR
                || op == InfixExpression.Operator.XOR;

        if (!shouldWrap) {
            // Arithmetic, comparison, shift — keep flat
            parts.add(text(" " + operator + " "));
            parts.add(rightDoc);
            for (var operand : extendedOperands) {
                parts.add(text(" " + operator + " "));
                operand.accept(this);
                parts.add(result);
            }
            result = concat(parts);
            return false;
        }

        if (extendedOperands.isEmpty()) {
            parts.add(indent(concat(
                    line(),
                    text(operator + " "),
                    rightDoc
            )));
        } else {
            var operandDocs = new ArrayList<Doc>();
            operandDocs.add(rightDoc);
            for (var operand : extendedOperands) {
                operand.accept(this);
                operandDocs.add(result);
            }
            parts.add(indent(concat(
                    line(),
                    text(operator + " "),
                    join(concat(line(), text(operator + " ")), operandDocs)
            )));
        }

        result = group(concat(parts));
        return false;
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

        parts.add(indent(concat(
                line(),
                text("? "),
                thenDoc,
                line(),
                text(": "),
                elseDoc
        )));

        result = group(concat(parts));
        return false;
    }

    @Override
    public boolean visit(InstanceofExpression node) {
        var parts = new ArrayList<Doc>();

        var left = getProperty(node, InstanceofExpression.LEFT_OPERAND_PROPERTY);
        left.accept(this);
        parts.add(result);

        parts.add(text(" instanceof "));

        var right = getProperty(node, InstanceofExpression.RIGHT_OPERAND_PROPERTY);
        right.accept(this);
        parts.add(result);

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(PatternInstanceofExpression node) {
        var parts = new ArrayList<Doc>();

        var left = getProperty(node, PatternInstanceofExpression.LEFT_OPERAND_PROPERTY);
        left.accept(this);
        parts.add(result);

        parts.add(text(" instanceof "));

        var pattern = getProperty(node, PatternInstanceofExpression.PATTERN_PROPERTY);
        pattern.accept(this);
        parts.add(result);

        result = concat(parts);
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

        // Single call or 2-call chain: use original per-call formatting
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, MethodInvocation.EXPRESSION_PROPERTY);
        if (expression != null) {
            expression.accept(this);
            parts.add(result);
            parts.add(text("."));
        }

        visitTypeArguments(parts, node, MethodInvocation.TYPE_ARGUMENTS_PROPERTY);

        parts.add(text(node.getName().getIdentifier()));

        var arguments = getProperty(node, MethodInvocation.ARGUMENTS_PROPERTY);
        formatArguments(parts, arguments);

        result = group(concat(parts));
        // Only glue to = when there's a receiver expression or complex args,
        // so "name = receiver.method(...)" stays together but
        // "name = simpleCall(a, b, c)" can break after =
        lastResultGluesToEquals = expression != null;
        return false;
    }

    /**
     * Format a method chain of 3+ calls with "break all or none" semantics.
     * Uses conditionalGroup with two alternatives:
     * Alt 1 (flat): root.s1.s2.s3
     * Alt 2 (broken): root.s1\n    .s2\n    .s3
     * First segment stays with root; remaining segments break together.
     */
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

        // Alt 1: everything flat
        var flatParts = new ArrayList<Doc>();
        flatParts.add(rootDoc);
        flatParts.addAll(segDocs);
        var flatAlt = concat(flatParts);

        // Alt 3: everything breaks, including first segment
        var fullBreakParts = new ArrayList<Doc>();
        fullBreakParts.add(rootDoc);
        var indentAllParts = new ArrayList<Doc>();
        for (var segDoc : segDocs) {
            indentAllParts.add(line(""));
            indentAllParts.add(segDoc);
        }
        fullBreakParts.add(indent(concat(indentAllParts)));
        var fullBreakAlt = concat(fullBreakParts);

        // Check if the first call in the chain has "simple" arguments
        // (0 or 1 args). If so, offer a partial break alt where the
        // first segment stays with the root.
        var firstCall = chain.get(startIndex);
        var firstArgs = getProperty(firstCall, MethodInvocation.ARGUMENTS_PROPERTY);
        boolean rootIsName = rootExpr instanceof SimpleName
                || rootExpr instanceof QualifiedName;
        boolean firstSegmentSimple = firstArgs.size() <= 1
                || rootIsName
                || rootExpr == null;

        if (firstSegmentSimple && segDocs.size() > 1) {
            var partialBreakParts = new ArrayList<Doc>();
            partialBreakParts.add(rootDoc);
            partialBreakParts.add(segDocs.getFirst());
            var indentRestParts = new ArrayList<Doc>();
            for (int i = 1; i < segDocs.size(); i++) {
                indentRestParts.add(line(""));
                indentRestParts.add(segDocs.get(i));
            }
            partialBreakParts.add(indent(concat(indentRestParts)));
            var partialBreakAlt = concat(partialBreakParts);

            if (rootIsName) {
                // Name roots ALWAYS keep first segment glued — never full break
                result = conditionalGroup(List.of(flatAlt, partialBreakAlt));
            } else {
                result = conditionalGroup(List.of(flatAlt, partialBreakAlt, fullBreakAlt));
            }
        } else if (segDocs.size() == 1 && rootIsName) {
            // Single segment after a name root — just flat or keep together
            var partialBreakParts = new ArrayList<Doc>();
            partialBreakParts.add(rootDoc);
            partialBreakParts.add(segDocs.getFirst());
            var partialBreakAlt = concat(partialBreakParts);
            result = conditionalGroup(List.of(flatAlt, partialBreakAlt));
        } else {
            result = conditionalGroup(List.of(flatAlt, fullBreakAlt));
        }
    }

    private void collectMethodChain(MethodInvocation node, List<MethodInvocation> chain) {
        var expr = getProperty(node, MethodInvocation.EXPRESSION_PROPERTY);
        if (expr instanceof MethodInvocation inner) {
            collectMethodChain(inner, chain);
        }
        chain.add(node);
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
                parts.add(group(concat(
                        text("("),
                        indent(concat(
                                line(""),
                                join(concat(text(","), line()), argDocs.subList(0, argDocs.size() - 1)),
                                text(","),
                                line()
                        )),
                        argDocs.getLast(),
                        text(")")
                )));
            } else {
                parts.add(group(concat(
                        text("("),
                        indent(concat(
                                line(""),
                                join(concat(text(","), line()), argDocs)
                        )),
                        line(""),
                        text(")")
                )));
            }
        }
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
                            line(""),
                            join(concat(text(","), line()), argDocs)
                    )),
                    line(""),
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
                            line(""),
                            join(concat(text(","), line()), argDocs)
                    )),
                    line(""),
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
        lastResultGluesToEquals = true;
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
        boolean skipParens = parameters.size() == 1 && parameters.getFirst() instanceof VariableDeclarationFragment;
        parts.add(group(concat(
                skipParens ? paramsDoc.getFirst() : concat(text("("), indent(concat(
                        line(""),
                        join(concat(text(","), line()), paramsDoc)
                )), line(""), text(")")),
                text(" -> ")
        )));

        var body = getProperty(node, LambdaExpression.BODY_PROPERTY);
        body.accept(this);
        parts.add(result);

        var partsDoc = concat(parts);
        result = body instanceof Block
                ? conditionalGroup(List.of(partsDoc, partsDoc))
                : partsDoc;
        lastResultGluesToEquals = true;
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
        lastResultGluesToEquals = true;
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
        var array = getProperty(node, ArrayAccess.ARRAY_PROPERTY);
        array.accept(this);
        var arrayDoc = result;

        var index = getProperty(node, ArrayAccess.INDEX_PROPERTY);
        index.accept(this);
        var indexDoc = result;

        var flat = concat(arrayDoc, text("["), indexDoc, text("]"));
        var breaking = concat(arrayDoc, text("["), indent(concat(new Doc.Line(""), indexDoc)), new Doc.Line(""),
                              text("]"));
        result = conditionalGroup(List.of(flat, breaking));
        lastResultGluesToEquals = true;
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
        result = text("this");
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
                            line(""),
                            join(concat(text(","), line()), valueDocs)
                    )),
                    line(""),
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

    private List<Comment> collectCommentsInRange(int from, int to) {
        if (source == null || compilationUnit == null) return List.of();
        var collected = new ArrayList<Comment>();
        for (ASTNode obj : (List<ASTNode>) compilationUnit.getCommentList()) {
            if (obj instanceof LineComment lc) {
                int start = lc.getStartPosition();
                if (start >= from && start < to) {
                    String text = source.substring(start, start + lc.getLength());
                    if (!text.startsWith("///")) {
                        collected.add(lc);
                    }
                }
            } else if (obj instanceof BlockComment bc) {
                int start = bc.getStartPosition();
                if (start >= from && start < to) {
                    collected.add(bc);
                }
            }
        }
        return collected;
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

    private boolean isTrailingComment(ASTNode prev, Comment comment) {
        if (source == null) return false;
        int prevEnd = prev.getStartPosition() + prev.getLength();
        int commentStart = comment.getStartPosition();
        for (int i = prevEnd; i < commentStart; i++) {
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
