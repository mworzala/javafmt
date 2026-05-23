package dev.javafmt.gradle.task;

import java.io.File;

record FormatError(File file, String relativePath, String message) {}
