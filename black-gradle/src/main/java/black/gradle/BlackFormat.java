package black.gradle;

import black.Black;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.util.List;

public abstract class BlackFormat extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Input
    @Optional
    public abstract ListProperty<String> getExcludes();

    @TaskAction
    public void format() {
        var excludes = getExcludes().getOrElse(List.of());
        var filesToFormat = getSourceFiles()
                .getAsFileTree()
                .matching(pattern -> pattern.exclude(excludes));

        int count = 0;
        for (File file : filesToFormat) {
            getLogger().debug("black: about to format '{}'", file);

            try {
                Black.formatFile(file.toPath());
                count++;
            } catch (IOException e) {
                throw new RuntimeException(e); // todo better error
            }
        }
        getLogger().info("black: formatted {} files successfully", count);
    }
}
