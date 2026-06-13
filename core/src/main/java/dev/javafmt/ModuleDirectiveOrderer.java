package dev.javafmt;

import org.eclipse.jdt.core.dom.ExportsDirective;
import org.eclipse.jdt.core.dom.ModuleDirective;
import org.eclipse.jdt.core.dom.OpensDirective;
import org.eclipse.jdt.core.dom.ProvidesDirective;
import org.eclipse.jdt.core.dom.RequiresDirective;
import org.eclipse.jdt.core.dom.UsesDirective;

import java.util.ArrayList;
import java.util.List;

/// Groups a module declaration's directives into the blocks `module-info.java` is printed as:
/// `exports`, `opens`, `requires`, `uses`, `provides` — in that fixed order, each non-empty
/// block separated from the next by one blank line. Source order is preserved within a block
/// and empty blocks are dropped. The directive kind alone determines the block, so the result
/// is a stable layout regardless of how the directives were originally interleaved.
///
/// Mirrors {@link ImportOrderer}: a pure regrouping consulted by the printer, no mutation.
final class ModuleDirectiveOrderer {

    private ModuleDirectiveOrderer() {}

    static List<List<ModuleDirective>> group(List<ModuleDirective> statements) {
        var exports = new ArrayList<ModuleDirective>();
        var opens = new ArrayList<ModuleDirective>();
        var requires = new ArrayList<ModuleDirective>();
        var uses = new ArrayList<ModuleDirective>();
        var provides = new ArrayList<ModuleDirective>();

        for (var s : statements) {
            if (s instanceof ExportsDirective) {
                exports.add(s);
            } else if (s instanceof OpensDirective) {
                opens.add(s);
            } else if (s instanceof RequiresDirective) {
                requires.add(s);
            } else if (s instanceof UsesDirective) {
                uses.add(s);
            } else if (s instanceof ProvidesDirective) {
                provides.add(s);
            } else {
                throw new IllegalStateException(
                        "unknown module directive: " + s.getClass().getSimpleName());
            }
        }

        var blocks = new ArrayList<List<ModuleDirective>>(5);
        if (!exports.isEmpty()) blocks.add(exports);
        if (!opens.isEmpty()) blocks.add(opens);
        if (!requires.isEmpty()) blocks.add(requires);
        if (!uses.isEmpty()) blocks.add(uses);
        if (!provides.isEmpty()) blocks.add(provides);
        return blocks;
    }
}
