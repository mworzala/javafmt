package dev.javafmt.gradle.task;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;

@DisableCachingByDefault(because = "Abstract base; subclasses opt into @CacheableTask.")
public abstract class JavaFmtTask extends DefaultTask {

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

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Inject
    protected abstract Problems getProblems();
}
