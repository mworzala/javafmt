package black.gradle;

import black.Formatter;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

// TODO: how can we support partial caching on source files? We should only reformat changed files.
@CacheableTask
public abstract class BlackFormat extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Input
    @Optional
    public abstract ListProperty<String> getExcludes();

    @Input
    @Optional
    public abstract Property<JavaLanguageVersion> getLanguageVersion();

    @Input
    @Optional
    public abstract Property<Boolean> getEnablePreview();

    @TaskAction
    public void format() {
        var languageVersion = getLanguageVersion().getOrElse(JavaLanguageVersion.current());
        var enablePreview = getEnablePreview().getOrElse(false);

        var excludes = getExcludes().getOrElse(List.of());
        var filesToFormat = getSourceFiles()
                .getAsFileTree()
                .matching(pattern -> pattern.exclude(excludes));

        var formatter = new Formatter(languageVersion.asInt(), enablePreview);
        for (File file : filesToFormat) {
            try {
                getLogger().debug("black: about to format '{}'", file);
                var source = Files.readString(file.toPath());
                var formatted = switch (formatter.format(source)) {
                    case Formatter.Success(var text) -> text;
                    default -> throw new RuntimeException("formatting failed");
                };
                Files.writeString(file.toPath(), formatted);
            } catch (IOException e) {
                throw new RuntimeException(e); // todo better error handling
            }
        }
    }
}
