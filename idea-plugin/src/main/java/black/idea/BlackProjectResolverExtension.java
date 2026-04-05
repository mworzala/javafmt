package black.idea;

import black.tool.BlackToolInfo;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import org.gradle.tooling.model.idea.IdeaModule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension;

import java.util.Set;

@Order(ExternalSystemConstants.UNORDERED)
public class BlackProjectResolverExtension extends AbstractProjectResolverExtension {

    @Override
    public @NotNull Set<Class<?>> getExtraProjectModelClasses() {
        return Set.of(BlackToolInfo.class);
    }

    @Override
    public void populateModuleExtraModels(@NotNull IdeaModule gradleModule, @NotNull DataNode<ModuleData> ideModule) {
        var toolInfo = resolverCtx.getExtraProject(gradleModule, BlackToolInfo.class);
        if (toolInfo != null) {
            var data = new BlackToolData(toolInfo.path());
            ideModule.createChild(BlackToolData.KEY, data);
            System.err.println("black: loaded tool info " + data);
        }

        nextResolver.populateModuleExtraModels(gradleModule, ideModule);
    }
}
