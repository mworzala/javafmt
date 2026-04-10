package dev.javafmt.idea;

import dev.javafmt.tool.JavaFmtToolInfo;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import org.gradle.tooling.model.idea.IdeaModule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension;

import java.util.Set;

@Order(ExternalSystemConstants.UNORDERED)
public class JavaFmtProjectResolverExtension extends AbstractProjectResolverExtension {

    @Override
    public @NotNull Set<Class<?>> getExtraProjectModelClasses() {
        return Set.of(JavaFmtToolInfo.class);
    }

    @Override
    public void populateModuleExtraModels(@NotNull IdeaModule gradleModule, @NotNull DataNode<ModuleData> ideModule) {
        var toolInfo = resolverCtx.getExtraProject(gradleModule, JavaFmtToolInfo.class);
        if (toolInfo != null) {
            var data = new JavaFmtToolData(toolInfo.path());
            ideModule.createChild(JavaFmtToolData.KEY, data);
            System.err.println("black: loaded tool info " + data);
        }

        nextResolver.populateModuleExtraModels(gradleModule, ideModule);
    }
}
