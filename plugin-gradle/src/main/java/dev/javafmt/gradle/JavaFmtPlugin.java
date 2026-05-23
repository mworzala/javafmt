package dev.javafmt.gradle;

import dev.javafmt.gradle.task.CheckFormat;
import dev.javafmt.gradle.task.FormatJava;
import dev.javafmt.gradle.task.JavaFmtTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;
import java.util.List;

public class JavaFmtPlugin implements Plugin<Project> {
    private final ToolingModelBuilderRegistry toolingModelBuilderRegistry;

    @Inject
    public JavaFmtPlugin(ToolingModelBuilderRegistry toolingModelBuilderRegistry) {
        this.toolingModelBuilderRegistry = toolingModelBuilderRegistry;
    }

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
        // Register our tooling model for the IntelliJ plugin to query during Gradle project sync.
        toolingModelBuilderRegistry.register(new JavaFmtToolingModelBuilder());

        var java = project.getExtensions().getByType(JavaPluginExtension.class);
        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        var buildDir = project.getLayout().getBuildDirectory();

        project.getTasks().register("formatJava", FormatJava.class, task -> {
            task.setDescription("Format Java source files");
            task.setGroup("formatting");

            task.getLanguageVersion().set(java.getToolchain().getLanguageVersion());
            task.getEnablePreview().set(false);
            task.getStampFile().convention(buildDir.file("javafmt/format-stamp.txt"));

            wireSources(task, sourceSets);
        });

        var checkFormat = project.getTasks().register("checkFormat", CheckFormat.class, task -> {
            task.setDescription("Check Java source files are formatted");
            task.setGroup("verification");

            task.getLanguageVersion().set(java.getToolchain().getLanguageVersion());
            task.getEnablePreview().set(false);
            task.getReportFile().convention(buildDir.file("javafmt/check-report.txt"));

            wireSources(task, sourceSets);
        });

        project.getTasks().named("check").configure(c -> c.dependsOn(checkFormat));
    }

    private static void wireSources(JavaFmtTask task, SourceSetContainer sourceSets) {
        sourceSets.all(sourceSet -> {
            var filtered = task.getExcludes().orElse(List.<String>of()).map(excludes ->
                    sourceSet.getJava().getAsFileTree().matching(p -> p.exclude(excludes))
            );
            task.getSourceFiles().from(filtered);
        });
    }
}
