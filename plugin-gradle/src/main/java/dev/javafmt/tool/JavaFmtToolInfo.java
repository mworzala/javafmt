package dev.javafmt.tool;

import org.jspecify.annotations.NullMarked;

import java.io.Serializable;

/// This type is serialized across the Gradle Tooling API boundary, so must remain
/// structurally consistent with the same type in the IntelliJ plugin.
@NullMarked
public interface JavaFmtToolInfo extends Serializable {
    String path();
}
