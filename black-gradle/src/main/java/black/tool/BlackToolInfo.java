package black.tool;

import org.jspecify.annotations.NullMarked;

import java.io.Serializable;

/// This type is serialized across the Gradle Tooling API boundary, so must remain
/// structurally consistent with the same type in the IntelliJ plugin.
@NullMarked
public interface BlackToolInfo extends Serializable {
    String path();
}
