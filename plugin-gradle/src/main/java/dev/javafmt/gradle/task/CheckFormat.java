package dev.javafmt.gradle.task;

import dev.javafmt.api.Formatter;
import dev.javafmt.api.Formatter.Result;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.problems.ProblemGroup;
import org.gradle.api.problems.ProblemId;
import org.gradle.api.problems.Severity;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

@CacheableTask
public abstract class CheckFormat extends JavaFmtTask {

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void check() {
        var languageVersion = getLanguageVersion().getOrElse(JavaLanguageVersion.current());
        var enablePreview = getEnablePreview().getOrElse(false);
        var formatter = Formatter.create(Formatter.Config.defaults()
                .withRelease(languageVersion.asInt())
                .withPreview(enablePreview));
        var projectDir = getProjectDirectory().get().getAsFile().toPath();
        var affected = new TreeSet<String>();
        var errors = new ArrayList<FormatError>();

        for (File file : getSourceFiles().getAsFileTree()) {
            var relativePath = projectDir.relativize(file.toPath()).toString();
            try {
                checkFile(file, relativePath, formatter, affected, errors);
            } catch (IOException e) {
                errors.add(new FormatError(file, relativePath, "I/O error: " + e.getMessage()));
            }
        }

        writeReport(affected);
        reportErrors(errors);
        if (!affected.isEmpty()) {
            var msg = affected.stream().collect(Collectors.joining("\n  ",
                    affected.size() + " file(s) need formatting:\n  ",
                    "\nRun ./gradlew formatJava to fix."));
            throw new GradleException(msg);
        }
    }

    private void checkFile(File file, String relativePath, Formatter formatter,
                           TreeSet<String> affected, List<FormatError> errors) throws IOException {
        var source = Files.readString(file.toPath());
        switch (formatter.format(source)) {
            case Result.Success(var formatted) -> {
                if (!formatted.equals(source)) {
                    affected.add(relativePath);
                }
            }
            case Result.SyntaxError(var problems) ->
                    errors.add(new FormatError(file, relativePath, "syntax error", problems));
            case Result.Failure(var t) ->
                    errors.add(new FormatError(file, relativePath, "format failed: " + t.getMessage()));
        }
    }

    private void writeReport(TreeSet<String> affected) {
        try {
            var report = getReportFile().get().getAsFile().toPath();
            Files.createDirectories(report.getParent());
            Files.writeString(report, String.join("\n", affected) + (affected.isEmpty() ? "" : "\n"));
        } catch (IOException e) {
            throw new GradleException("javafmt: failed to write check report", e);
        }
    }

    private void reportErrors(List<FormatError> errors) {
        if (errors.isEmpty()) return;
        var reporter = getProblems().getReporter();
        var problemId = ProblemId.create(
                "javafmt-error",
                "javafmt failed to check file",
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
