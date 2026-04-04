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

    // types

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
    public boolean visit(RecordDeclaration node) {
        var parts = new ArrayList<Doc>();

        visitJavadoc(parts, node);

        visitModifiers(parts, node, RecordDeclaration.MODIFIERS2_PROPERTY);

        parts.add(text("record"));
        parts.add(space());
        parts.add(text(node.getName().getIdentifier()));

        // todo SUPER_INTERFACE_TYPES_PROPERTY
        // todo TYPE_PARAMETERS_PROPERTY

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

        visitBodyDeclarations(parts, node, RecordDeclaration.BODY_DECLARATIONS_PROPERTY, true);

        result = concat(parts);
        return false;
    }

    private void visitBodyDeclarations(
            List<Doc> parts,
            AbstractTypeDeclaration node,
            ChildListPropertyDescriptor property,
            boolean withBraces
    ) {
        var body = getProperty(node, property);
        if (body.isEmpty()) {
            if (withBraces) {
                parts.add(text("{}"));
            }
        } else {
            if (withBraces) parts.add(text("{"));

            var bodyParts = new ArrayList<Doc>();
            bodyParts.add(hardLine());
            if (blankLinesAfterOpenBrace(node, body.getFirst()) > 0) {
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
            var bodyDoc = concat(bodyParts);
            parts.add(withBraces ? indent(bodyDoc) : bodyDoc);

            parts.add(hardLine());
            if (blankLinesBeforeCloseBrace(node, body.getLast()) > 0) {
                parts.add(hardLine());
            }
            if (withBraces) parts.add(text("}"));
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

        // todo ChildListPropertyDescriptor VARARGS_ANNOTATIONS_PROPERTY
        // todo SimplePropertyDescriptor VARARGS_PROPERTY

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

        visitJavadoc(parts, node);

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

        if (!node.isConstructor()) {
            var returnType = getProperty(node, MethodDeclaration.RETURN_TYPE2_PROPERTY);
            returnType.accept(this);
            parts.add(result);
            parts.add(space());
        }

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

    // statements

    @Override
    public boolean visit(Block node) {
        var stmts = new ArrayList<>(getProperty(node, Block.STATEMENTS_PROPERTY));
        stmts.removeIf(stmt -> stmt instanceof EmptyStatement);

        if (stmts.isEmpty()) {
            result = text("{}");
            return false;
        }

        var parts = new ArrayList<Doc>();
        for (int i = 0; i < stmts.size(); i++) {
            if (i > 0 && blankLinesBetween(stmts.get(i - 1), stmts.get(i)) > 0) {
                parts.add(hardLine());
            }
            stmts.get(i).accept(this);
            parts.add(hardLine());
            parts.add(result);
        }

        result = concat(text("{"),
                        indent(concat(parts)),
                        hardLine(),
                        text("}"));
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
        if (initDoc instanceof Doc.ConditionalGroup) {
            // Wrap the whole "name = initDoc" in a group. When it doesn't fit flat,
            // non-flat mode propagates to the ConditionalGroup at the current column,
            // where the flat alt no longer fits and the breaking alt is chosen.
            result = group(concat(name, text(" = "), initDoc));
        } else {
            // Keep "= init" on same line; if line is too long, break before initializer
            result = concat(
                    name,
                    group(concat(text(" ="), indent(concat(line(), initDoc))))
            );
        }
        return false;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        var parts = new ArrayList<Doc>();

        // todo TYPE_ARGUMENTS_PROPERTY

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
        parts.add(text("yield"));
        parts.add(space());

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
                ? concat(text("break"), space(), text(label.getIdentifier()), text(";"))
                : text("break;");
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

    // expressions

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

        parts.add(space());
        var operator = text(node.getOperator().toString());
        parts.add(operator);
        parts.add(space());

        var right = getProperty(node, InfixExpression.RIGHT_OPERAND_PROPERTY);
        right.accept(this);
        parts.add(result);

        var extendedOperands = getProperty(node, InfixExpression.EXTENDED_OPERANDS_PROPERTY);
        for (var operand : extendedOperands) {
            parts.add(space());
            parts.add(operator);
            parts.add(space());
            operand.accept(this);
            parts.add(result);
        }

        result = concat(parts);
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
    public boolean visit(MethodInvocation node) {
        var parts = new ArrayList<Doc>();

        var expression = getProperty(node, MethodInvocation.EXPRESSION_PROPERTY);
        if (expression != null) {
            expression.accept(this);
            parts.add(result);
            parts.add(indent(concat(new Doc.Line(""), text("."))));
        }

        // todo TYPE_ARGUMENTS_PROPERTY

        parts.add(text(node.getName().getIdentifier()));

        var arguments = getProperty(node, MethodInvocation.ARGUMENTS_PROPERTY);
        if (arguments.isEmpty()) {
            parts.add(text("()"));
        } else {
            parts.add(text("("));
            var argDocs = new ArrayList<Doc>();
            for (var arg : arguments) {
                arg.accept(this);
                argDocs.add(result);
            }
            parts.add(join(concat(text(","), space()), argDocs));
            parts.add(text(")"));
        }

        result = group(concat(parts));
        return false;
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
        if (body instanceof Block) {
            result = conditionalGroup(List.of(partsDoc, partsDoc));
        } else {
            result = partsDoc;
        }
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
        result = text(node.getPrimitiveTypeCode().toString());
        return false;
    }

    @Override
    public boolean visit(SimpleType node) {
        result = text(node.getName().getFullyQualifiedName());
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
    public boolean visit(BlockComment node) {
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
    public boolean visit(ClassInstanceCreation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(ConditionalExpression node) {
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
    public boolean visit(ExpressionMethodReference node) {
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
    public boolean visit(IntersectionType node) {
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
    public boolean visit(MemberValuePair node) {
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
    public boolean visit(NullPattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(PatternInstanceofExpression node) {
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
    public boolean visit(RecordPattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(EitherOrMultiPattern node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
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
    public boolean visit(VariableDeclarationExpression node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(WhileStatement node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
    }

    @Override
    public boolean visit(WildcardType node) {
        throw new UnsupportedOperationException("not implemented: " + node.getClass().getSimpleName());
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

    // nodes to always be handled by their parents.

    @Override
    public boolean visit(EmptyStatement node) {
        throw new IllegalStateException("EmptyStatement should have been removed");
    }

    // Helpers

    private static @UnknownNullability ASTNode getProperty(ASTNode node, ChildPropertyDescriptor property) {
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

    private int blankLinesAfterOpenBrace(AbstractTypeDeclaration node, ASTNode firstBodyDecl) {
        if (source == null) return 1;
        int firstBodyStart = firstBodyDecl.getStartPosition();
        int pos = firstBodyStart - 1;
        while (pos >= node.getStartPosition() && source.charAt(pos) != '{') {
            pos--;
        }
        return blankLinesBetweenPositions(pos + 1, firstBodyStart);
    }

    private int blankLinesBeforeCloseBrace(AbstractTypeDeclaration node, ASTNode lastBodyDecl) {
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
