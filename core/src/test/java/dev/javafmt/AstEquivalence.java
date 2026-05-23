package dev.javafmt;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ChildPropertyDescriptor;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.SimplePropertyDescriptor;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;

import java.util.List;
import java.util.Objects;

/// Asserts two JDT ASTs are structurally equivalent, ignoring source positions, whitespace, and Javadoc contents.
/// A failing comparison throws {@link Mismatch} with a path showing where the trees diverge.
final class AstEquivalence {

    static final class Mismatch extends RuntimeException {
        Mismatch(String path, String detail) {
            super(path + ": " + detail);
        }
    }

    static void assertEquivalent(ASTNode a, ASTNode b) {
        compare(a, b, "");
    }

    @SuppressWarnings("unchecked")
    private static void compare(ASTNode a, ASTNode b, String path) {
        if (a == null && b == null) return;
        if (a == null || b == null) {
            throw new Mismatch(path, "one side is null (a=" + a + ", b=" + b + ")");
        }
        if (a.getClass() != b.getClass()) {
            throw new Mismatch(path, "node type differs (" + a.getClass().getSimpleName()
                    + " vs " + b.getClass().getSimpleName() + ")");
        }

        // Javadoc reparsing can produce trivially different tag trees; treat any two Javadoc
        // nodes as equivalent at this level (their presence/absence is still checked above).
        if (a instanceof Javadoc) return;

        var props = (List<StructuralPropertyDescriptor>) a.structuralPropertiesForType();
        for (var prop : props) {
            var childPath = path + "/" + prop.getId();
            if (prop instanceof SimplePropertyDescriptor sp) {
                var av = a.getStructuralProperty(sp);
                var bv = b.getStructuralProperty(sp);
                if (!Objects.equals(av, bv)) {
                    throw new Mismatch(childPath, "simple value differs (" + av + " vs " + bv + ")");
                }
            } else if (prop instanceof ChildPropertyDescriptor cp) {
                compare((ASTNode) a.getStructuralProperty(cp),
                        (ASTNode) b.getStructuralProperty(cp),
                        childPath);
            } else if (prop instanceof ChildListPropertyDescriptor lp) {
                var al = (List<ASTNode>) a.getStructuralProperty(lp);
                var bl = (List<ASTNode>) b.getStructuralProperty(lp);
                if (al.size() != bl.size()) {
                    throw new Mismatch(childPath,
                            "list size differs (" + al.size() + " vs " + bl.size() + ")");
                }
                for (int i = 0; i < al.size(); i++) {
                    compare(al.get(i), bl.get(i), childPath + "[" + i + "]");
                }
            }
        }
    }

    private AstEquivalence() {}
}
