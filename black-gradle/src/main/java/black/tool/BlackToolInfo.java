package black.tool;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.Serializable;

/// This type is serialized across the Gradle Tooling API boundary, so must remain
/// structurally consistent with the same type in the IntelliJ plugin.
@NotNullByDefault
public interface BlackToolInfo extends Serializable {
    String path();
}
