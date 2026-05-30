package dev.javafmt;

import org.eclipse.jdt.core.dom.ImportDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ImportOrderer {

    private ImportOrderer() {}

    private static final Comparator<ImportDeclaration> BY_NAME =
            Comparator.comparing(ImportOrderer::sortKey);

    static List<List<ImportDeclaration>> group(List<ImportDeclaration> imports) {
        var other = new ArrayList<ImportDeclaration>();
        var javax = new ArrayList<ImportDeclaration>();
        var java = new ArrayList<ImportDeclaration>();
        var statics = new ArrayList<ImportDeclaration>();

        for (var imp : imports) {
            if (imp.isStatic()) {
                statics.add(imp);
                continue;
            }
            var fqn = imp.getName().getFullyQualifiedName();
            if (fqn.equals("javax") || fqn.startsWith("javax.")) {
                javax.add(imp);
            } else if (fqn.equals("java") || fqn.startsWith("java.")) {
                java.add(imp);
            } else {
                other.add(imp);
            }
        }

        other.sort(BY_NAME);
        javax.sort(BY_NAME);
        java.sort(BY_NAME);
        statics.sort(BY_NAME);

        var std = new ArrayList<ImportDeclaration>(javax.size() + java.size());
        std.addAll(javax);
        std.addAll(java);

        var blocks = new ArrayList<List<ImportDeclaration>>(3);
        if (!other.isEmpty()) blocks.add(other);
        if (!std.isEmpty()) blocks.add(std);
        if (!statics.isEmpty()) blocks.add(statics);
        return blocks;
    }

    private static String sortKey(ImportDeclaration imp) {
        var fqn = imp.getName().getFullyQualifiedName();
        return imp.isOnDemand() ? fqn + ".*" : fqn;
    }
}
