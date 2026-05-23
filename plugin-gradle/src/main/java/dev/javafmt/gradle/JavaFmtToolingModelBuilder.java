package dev.javafmt.gradle;

import dev.javafmt.tool.JavaFmtToolInfo;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jspecify.annotations.Nullable;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class JavaFmtToolingModelBuilder implements ToolingModelBuilder {
    @Override
    public boolean canBuild(String modelName) {
        return JavaFmtToolInfo.class.getName().equals(modelName);
    }

    @Override
    public @Nullable Object buildAll(String modelName, Project project) {
        return new ToolInfo(collectClasspath());
    }

    /// Returns every jar on the plugin's classloader path so the IntelliJ side can
    /// build a self-contained URLClassLoader for the user's pinned formatter version.
    /// The plugin-gradle jar is just the entry point; the formatter itself lives in
    /// transitively-resolved core + JDT jars.
    private List<String> collectClasspath() {
        var paths = new LinkedHashSet<String>();

        var ownLocation = getClass().getProtectionDomain().getCodeSource();
        if (ownLocation != null && ownLocation.getLocation() != null) {
            paths.add(ownLocation.getLocation().toString());
        }

        // Plugin classloaders in Gradle flatten transitive deps into one classloader's URLs,
        // so this picks up core + JDT jars without leaking Gradle's own classes from the parent.
        if (getClass().getClassLoader() instanceof URLClassLoader urlCl) {
            for (URL url : urlCl.getURLs()) {
                paths.add(url.toString());
            }
        }

        return new ArrayList<>(paths);
    }

    record ToolInfo(List<String> paths) implements JavaFmtToolInfo { }
}
