package black;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;

public class Black {
    static void main() {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource("public class Example {}".toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        var ast = parser.createAST(null);
        System.out.println("---\n"+ast+"---\n");

        var a2d = new AstToDoc();
        ast.accept(a2d);

        var printer = new DocPrinter(100);
        var result = printer.print(a2d.result());

        System.out.println("---\n"+result+"---\n");
    }
}
