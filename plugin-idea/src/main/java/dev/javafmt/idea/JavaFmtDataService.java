package dev.javafmt.idea;

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
public class JavaFmtDataService extends AbstractProjectDataService<JavaFmtToolData, Void> {

    @Override
    public @NotNull Key<JavaFmtToolData> getTargetDataKey() {
        return JavaFmtToolData.KEY;
    }

    @Override
    public void importData(
            @NotNull Collection<? extends DataNode<JavaFmtToolData>> toImport,
            @Nullable ProjectData projectData, @NotNull Project project,
            @NotNull IdeModifiableModelsProvider modelsProvider
    ) {
        var projectSettings = JavaFmtProjectSettings.getInstance(project);

        for (DataNode<JavaFmtToolData> node : toImport) {
            projectSettings.setFormatterClasspath(node.getData().paths());
            return;
        }

        projectSettings.clear();
    }
}
