package dev.javafmt.gradle.task;

import dev.javafmt.Formatter;
import org.gradle.api.GradleException;
import org.gradle.api.file.FileType;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.problems.ProblemGroup;
import org.gradle.api.problems.ProblemId;
import org.gradle.api.problems.Severity;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@CacheableTask
public abstract class FormatJava extends JavaFmtTask {

    @OutputFile
    public abstract RegularFileProperty getStampFile();

    @TaskAction
    public void format(InputChanges inputChanges) {
        var languageVersion = getLanguageVersion().getOrElse(JavaLanguageVersion.current());
        var enablePreview = getEnablePreview().getOrElse(false);
        var formatter = new Formatter(languageVersion.asInt(), enablePreview);
        var projectDir = getProject().getProjectDir().toPath();
        var errors = new ArrayList<FormatError>();
        var processed = 0;

        for (FileChange change : inputChanges.getFileChanges(getSourceFiles())) {
            if (change.getFileType() == FileType.DIRECTORY) continue;
            if (change.getChangeType() == ChangeType.REMOVED) continue;

            var file = change.getFile();
            var relativePath = projectDir.relativize(file.toPath()).toString();
            try {
                processFile(file, relativePath, formatter, errors);
                processed++;
            } catch (IOException e) {
                errors.add(new FormatError(file, relativePath, "I/O error: " + e.getMessage()));
            }
        }

        writeStamp(processed);
        reportErrors(errors);
    }

    private void processFile(File file, String relativePath, Formatter formatter, List<FormatError> errors) throws IOException {
        getLogger().debug("javafmt: formatting '{}'", relativePath);
        var source = Files.readString(file.toPath());
        switch (formatter.format(source)) {
            case Formatter.Success(var formatted) -> {
                if (!formatted.equals(source)) {
                    Files.writeString(file.toPath(), formatted);
                }
            }
            case Formatter.SyntaxError(var problems) ->
                    errors.add(new FormatError(file, relativePath, "syntax error", problems));
            case Formatter.Failure(var t) ->
                    errors.add(new FormatError(file, relativePath, "format failed: " + t.getMessage()));
        }
    }

    private void writeStamp(int processed) {
        try {
            var stamp = getStampFile().get().getAsFile().toPath();
            Files.createDirectories(stamp.getParent());
            Files.writeString(stamp, "processed=" + processed + "\n");
        } catch (IOException e) {
            throw new GradleException("javafmt: failed to write stamp file", e);
        }
    }

    private void reportErrors(List<FormatError> errors) {
        if (errors.isEmpty()) return;
        var reporter = getProblems().getReporter();
        var problemId = ProblemId.create(
                "javafmt-error",
                "javafmt failed to format file",
                ProblemGroup.create("javafmt", "javafmt")
        );
        for (var e : errors) {
            if (e.problems().isEmpty()) {
                reporter.report(problemId, spec -> spec
                        .contextualLabel(e.relativePath())
                        .severity(Severity.ERROR)
                        .fileLocation(e.file().getAbsolutePath())
                        .details(e.message()));
            } else {
                for (var p : e.problems()) {
                    reporter.report(problemId, spec -> spec
                            .contextualLabel(e.relativePath() + ":" + p.line() + ":" + p.column())
                            .severity(Severity.ERROR)
                            .lineInFileLocation(e.file().getAbsolutePath(), p.line(), p.column())
                            .details("syntax error: " + p.message()));
                }
            }
        }
        var msg = errors.stream()
                .flatMap(e -> {
                    if (e.problems().isEmpty()) {
                        return java.util.stream.Stream.of("  " + e.relativePath() + ": " + e.message());
                    }
                    return e.problems().stream().map(p ->
                            "  " + e.relativePath() + ":" + p.line() + ":" + p.column()
                                    + ": syntax error: " + p.message());
                })
                .collect(Collectors.joining("\n",
                        "javafmt failed for " + errors.size() + " file(s):\n", ""));
        throw new GradleException(msg);
    }
}
