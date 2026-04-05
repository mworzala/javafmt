package black.idea;

import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider;
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

@Order(ExternalSystemConstants.UNORDERED)
public class BlackDataService extends AbstractProjectDataService<BlackToolData, Void> {

    @Override
    public @NotNull Key<BlackToolData> getTargetDataKey() {
        return BlackToolData.KEY;
    }

    @Override
    public void importData(
            @NotNull Collection<? extends DataNode<BlackToolData>> toImport,
            @Nullable ProjectData projectData, @NotNull Project project,
            @NotNull IdeModifiableModelsProvider modelsProvider
    ) {
        var projectSettings = BlackProjectSettings.getInstance(project);

        for (DataNode<BlackToolData> node : toImport) {
            projectSettings.setFormatterJarPath(node.getData().path());
            return;
        }

        projectSettings.clear();
    }
}
