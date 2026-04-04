package black;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;

import java.util.Map;

public class Black {
    static void main() {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource("public class Example {}".toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, "25");
        options.put(JavaCore.COMPILER_COMPLIANCE, "25");
        parser.setCompilerOptions(options);

        var ast = parser.createAST(null);
        System.out.println("---\n"+ast+"---\n");

        var a2d = new AstToDoc();
        ast.accept(a2d);

        var printer = new DocPrinter(100);
        var result = printer.print(a2d.result());

        System.out.println("---\n"+result+"---\n");
    }
}
