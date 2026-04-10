package dev.javafmt.tool;

import org.jetbrains.annotations.NotNull;

/// This type is serialized across the Gradle Tooling API boundary, so must remain
/// structurally consistent with the same type in the Gradle plugin.
public interface JavaFmtToolInfo {
    @NotNull String path();
}
