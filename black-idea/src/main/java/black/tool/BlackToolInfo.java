package black.tool;

import org.jetbrains.annotations.NotNull;

/// This type is serialized across the Gradle Tooling API boundary, so must remain
/// structurally consistent with the same type in the Gradle plugin.
public interface BlackToolInfo {
    @NotNull String path();
}
