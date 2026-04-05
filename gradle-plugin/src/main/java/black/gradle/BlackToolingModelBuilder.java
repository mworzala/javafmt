package black.gradle;

import black.tool.BlackToolInfo;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jspecify.annotations.Nullable;

public class BlackToolingModelBuilder implements ToolingModelBuilder {
    @Override
    public boolean canBuild(String modelName) {
        return BlackToolInfo.class.getName().equals(modelName);
    }

    @Override
    public @Nullable Object buildAll(String modelName, Project project) {
        var pluginSourceCode = getClass().getProtectionDomain().getCodeSource();
        return new ToolInfo(pluginSourceCode.getLocation().toString());
    }

    record ToolInfo(String path) implements BlackToolInfo { }
}
