package black.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSetContainer;

public class BlackPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPlugins().withType(
                JavaPlugin.class,
                _ -> applyTasksAfterJava(project)
        );

        project.afterEvaluate(p -> {
            if (!p.getPlugins().hasPlugin(JavaPlugin.class)) {
                project.getLogger().warn("black: java plugin not applied");
            }
        });
    }

    private void applyTasksAfterJava(Project project) {
        project.getTasks().register("formatJava", BlackFormat.class, task -> {
            task.setDescription("Format Java source files");
            task.setGroup("formatting");

            var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);

            sourceSets.all(sourceSet -> task.getSourceFiles().from(sourceSet.getJava().getSourceDirectories()));
        });
    }

}
