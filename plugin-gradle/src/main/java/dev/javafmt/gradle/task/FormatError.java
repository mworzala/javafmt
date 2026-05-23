package dev.javafmt.gradle.task;

import dev.javafmt.api.Formatter;

import java.io.File;
import java.util.List;

record FormatError(File file, String relativePath, String message, List<Formatter.Problem> problems) {
    FormatError(File file, String relativePath, String message) {
        this(file, relativePath, message, List.of());
    }
}
