package black;

import org.eclipse.jdt.core.dom.*;
import org.jetbrains.annotations.UnknownNullability;

import java.util.ArrayList;
import java.util.List;

import static black.Doc.*;

@SuppressWarnings("unchecked")
public class AstToDoc extends ASTVisitor {

    private final String source;
    private Doc result;

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
        var parts = new ArrayList<Doc>();

        // todo MODULE_PROPERTY

        var package_ = getProperty(node, CompilationUnit.PACKAGE_PROPERTY);
        if (package_ != null) {
            package_.accept(this);
            parts.add(result);
            parts.add(hardLine());
            parts.add(hardLine());
        }

        // TODO IMPORTS_PROPERTY

        var types = getProperty(node, CompilationUnit.TYPES_PROPERTY);
        for (var type : types) {
            type.accept(this);
            parts.add(result);
            parts.add(hardLine());
            parts.add(hardLine());
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(PackageDeclaration node) {
        result = text("package " + node.getName().getFullyQualifiedName() + ";");
        return false;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node);

        visitModifiers(parts, node, TypeDeclaration.MODIFIERS2_PROPERTY);

        boolean isInterface = node.isInterface();
        parts.add(text(isInterface ? "interface" : "class"));
        parts.add(space());

        parts.add(text(node.getName().getIdentifier()));
        parts.add(space());

        // todo SUPERCLASS_PROPERTY
        // todo SUPER_INTERFACES_PROPERTY
        // todo SUPERCLASS_TYPE_PROPERTY
        // todo SUPER_INTERFACE_TYPES_PROPERTY
        // todo TYPE_PARAMETERS_PROPERTY
        // todo PERMITS_TYPES_PROPERTY

        var body = getProperty(node, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        if (body.isEmpty()) {
            parts.add(text("{}"));
        } else {
            parts.add(text("{"));

            var bodyParts = new ArrayList<Doc>();
            bodyParts.add(hardLine());
            if (blankLinesAfterOpenBrace(node, body.get(0)) > 0) {
                bodyParts.add(hardLine());
            }
            for (int i = 0; i < body.size(); i++) {
                if (i > 0) {
                    bodyParts.add(hardLine());
                    if (blankLinesBetween(body.get(i - 1), body.get(i)) > 0) {
                        bodyParts.add(hardLine());
                    }
                }
                body.get(i).accept(this);
                bodyParts.add(result);
            }
            parts.add(indent(concat(bodyParts)));

            parts.add(hardLine());
            if (blankLinesBeforeCloseBrace(node, body.get(body.size() - 1)) > 0) {
                parts.add(hardLine());
            }
            parts.add(text("}"));
        }

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node);

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

        visitJavadoc(parts, node);

        visitModifiers(parts, node, MethodDeclaration.MODIFIERS2_PROPERTY);

        // todo CONSTRUCTOR_PROPERTY
        // todo COMPACT_CONSTRUCTOR_PROPERTY

        var returnType = getProperty(node, MethodDeclaration.RETURN_TYPE2_PROPERTY);
        returnType.accept(this);
        parts.add(result);
        parts.add(space());

        parts.add(text(node.getName().getIdentifier()));

        // todo EXTRA_DIMENSIONS_PROPERTY
        // todo EXTRA_DIMENSIONS2_PROPERTY
        // todo TYPE_PARAMETERS_PROPERTY

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
                            line(),
                            join(concat(text(","), line()), paramDocs)
                    )),
                    line(),
                    text(")")
            )));
        }
        parts.add(space());

        // todo RECEIVER_TYPE_PROPERTY
        // todo RECEIVER_QUALIFIER_PROPERTY
        // todo THROWN_EXCEPTIONS_PROPERTY
        // todo THROWN_EXCEPTION_TYPES_PROPERTY

        var body = getProperty(node, MethodDeclaration.BODY_PROPERTY);
        if (body != null) {
            body.accept(this);
            parts.add(result);
        }

        result = concat(parts);
        return false;
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

    private int blankLinesAfterOpenBrace(TypeDeclaration node, ASTNode firstBodyDecl) {
        if (source == null) return 1;
        int firstBodyStart = firstBodyDecl.getStartPosition();
        int pos = firstBodyStart - 1;
        while (pos >= node.getStartPosition() && source.charAt(pos) != '{') pos--;
        return blankLinesBetweenPositions(pos + 1, firstBodyStart);
    }

    private int blankLinesBeforeCloseBrace(TypeDeclaration node, ASTNode lastBodyDecl) {
        if (source == null) return 1;
        int lastBodyEnd = lastBodyDecl.getStartPosition() + lastBodyDecl.getLength();
        int closeBrace = node.getStartPosition() + node.getLength() - 1;
        return blankLinesBetweenPositions(lastBodyEnd, closeBrace);
    }

    private void visitJavadoc(List<Doc> parts, BodyDeclaration node) {
        if (source == null) return;

        // Check for legacy /** ... */ javadoc via the AST
        var javadoc = node.getJavadoc();
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
        while (pos >= 0 && source.charAt(pos) == ' ') pos--;
        if (pos >= 0 && source.charAt(pos) == '\n') pos--;

        // Collect /// lines going backwards
        var mdLines = new ArrayList<String>();
        while (pos >= 0) {
            // Find start of this line
            int lineEnd = pos;
            int lineStart = pos;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') lineStart--;
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

    private void appendCommentLines(List<Doc> parts, String text, int indentToStrip) {
        var lines = text.split("\n");
        for (var line : lines) {
            // Strip up to indentToStrip leading spaces
            int i = 0;
            while (i < indentToStrip && i < line.length() && line.charAt(i) == ' ') i++;
            parts.add(text(line.substring(i).stripTrailing()));
            parts.add(hardLine());
        }
    }

    private void visitModifiers(List<Doc> doc, ASTNode node, ChildListPropertyDescriptor property) {
        var modifiers = getProperty(node, property);
        if (modifiers.isEmpty()) return;

        for (var modifier : modifiers) {
            modifier.accept(this);
            doc.add(result);
        }
    }

    @Override
    public boolean visit(Modifier node) {
        result = text(node.getKeyword().toString() + " ");
        return false;
    }

    @Override
    public boolean visit(Block node) {
        var stmts = getProperty(node, Block.STATEMENTS_PROPERTY);
        if (stmts.isEmpty()) {
            result = text("{}");
            return false;
        }

        var parts = new ArrayList<Doc>();
        parts.add(text("{"));
        parts.add(hardLine());
        for (var stmt : stmts) {
            stmt.accept(this);
            parts.add(result);
            parts.add(hardLine());
        }
        parts.add(text("}"));

        result = concat(parts);
        return false;
    }

    @Override
    public boolean visit(PrimitiveType node) {
        result = text(node.getPrimitiveTypeCode().toString());
        return false;
    }

    // --- the rest :)

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(AnnotationTypeMemberDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ArrayAccess node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ArrayCreation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ArrayInitializer node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ArrayType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(AssertStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(Assignment node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(BlockComment node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(BooleanLiteral node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(BreakStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(CaseDefaultExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(CastExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(CatchClause node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(CharacterLiteral node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ConditionalExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ContinueStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(CreationReference node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(Dimension node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(DoStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(EmptyStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(EnhancedForStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(EnumConstantDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ExportsDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ExpressionMethodReference node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ExpressionStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(FieldAccess node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ForStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(GuardedPattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(IfStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ImportDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(InfixExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(Initializer node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(InstanceofExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(IntersectionType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

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
    public boolean visit(LabeledStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(LambdaExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(LineComment node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(MarkerAnnotation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(MemberRef node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(MemberValuePair node) {
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
    public boolean visit(MethodInvocation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ModuleDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ModuleModifier node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(NameQualifiedType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(NormalAnnotation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(NullLiteral node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(NullPattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(NumberLiteral node) {
        result = text(node.getToken());
        return false;
    }

    @Override
    public boolean visit(OpensDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ParameterizedType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ParenthesizedExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(PatternInstanceofExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(PostfixExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(PrefixExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ProvidesDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(QualifiedName node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(QualifiedType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ModuleQualifiedName node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(RequiresDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(RecordPattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(EitherOrMultiPattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ReturnStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SimpleName node) {
        result = text(node.getIdentifier());
        return false;
    }

    @Override
    public boolean visit(SimpleType node) {
        result = text(node.getName().getFullyQualifiedName());
        return false;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SingleVariableDeclaration node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(StringLiteral node) {
        result = text(node.getEscapedValue());
        return false;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SuperFieldAccess node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SuperMethodInvocation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SuperMethodReference node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SwitchCase node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SwitchExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SwitchStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SynchronizedStatement node) {
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
    public boolean visit(TextBlock node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TextElement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ThisExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ThrowStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TryStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TypeDeclarationStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TypeLiteral node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TypeMethodReference node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TypeParameter node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(TypePattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(UnionType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(UsesDirective node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(VariableDeclarationExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(VariableDeclarationStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
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
        // Keep "= init" on same line; if line is too long, break before initializer
        result = concat(name, group(concat(text(" ="), indent(concat(line(), initDoc)))));
        return false;
    }

    @Override
    public boolean visit(WhileStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(WildcardType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(YieldStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ImplicitTypeDeclaration implicitTypeDeclaration) {
        return super.visit(implicitTypeDeclaration);
    }

    // Helpers

    private static @UnknownNullability ASTNode getProperty(ASTNode node, ChildPropertyDescriptor property) {
        return (ASTNode) node.getStructuralProperty(property);
    }

    private static List<ASTNode> getProperty(ASTNode node, ChildListPropertyDescriptor property) {
        return (List<ASTNode>) node.getStructuralProperty(property);
    }

}
