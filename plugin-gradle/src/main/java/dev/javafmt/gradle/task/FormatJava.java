package dev.javafmt.gradle.task;

import dev.javafmt.api.Formatter;
import dev.javafmt.api.Formatter.Result;
import org.gradle.api.GradleException;
import org.gradle.api.problems.ProblemGroup;
import org.gradle.api.problems.ProblemId;
import org.gradle.api.problems.Severity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Untracked, not incremental/cacheable: the task's real output is an in-place rewrite of its
// *input* files, which Gradle cannot model. Tracked state fingerprints inputs pre-execution, so a
// git revert back to that exact content makes the task UP-TO-DATE while the tree is unformatted,
// and a build-cache hit would replay only declared outputs, skipping the rewrite entirely.
@UntrackedTask(because = "Rewrites its input files in place; the formatted sources are not declarable outputs.")
public abstract class FormatJava extends JavaFmtTask {

    @TaskAction
    public void format() {
        var languageVersion = getLanguageVersion().getOrElse(JavaLanguageVersion.current());
        var enablePreview = getEnablePreview().getOrElse(false);
        var formatter = Formatter.create(Formatter.Config.defaults()
                .withRelease(languageVersion.asInt())
                .withPreview(enablePreview));
        var projectDir = getProjectDirectory().get().getAsFile().toPath();
        var errors = new ArrayList<FormatError>();

        for (File file : getSourceFiles().getAsFileTree()) {
            var relativePath = projectDir.relativize(file.toPath()).toString();
            try {
                processFile(file, relativePath, formatter, errors);
            } catch (IOException e) {
                errors.add(new FormatError(file, relativePath, "I/O error: " + e.getMessage()));
            }
        }

        reportErrors(errors);
    }

    private void processFile(File file, String relativePath, Formatter formatter, List<FormatError> errors) throws IOException {
        getLogger().debug("javafmt: formatting '{}'", relativePath);
        var source = Files.readString(file.toPath());
        switch (formatter.format(source, file.getName())) {
            case Result.Success(var formatted) -> {
                if (!formatted.equals(source)) {
                    Files.writeString(file.toPath(), formatted);
                }
            }
            case Result.SyntaxError(var problems) ->
                    errors.add(new FormatError(file, relativePath, "syntax error", problems));
            case Result.Failure(var t) ->
                    errors.add(new FormatError(file, relativePath, "format failed: " + t.getMessage()));
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
