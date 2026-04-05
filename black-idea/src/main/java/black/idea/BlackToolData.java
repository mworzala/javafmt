package black.idea;

import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.serialization.PropertyMapping;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

public record BlackToolData(@NotNull String path) implements Serializable {
    public static final Key<BlackToolData> KEY = Key.create(BlackToolData.class, 500);

    @PropertyMapping({"path"})
    public BlackToolData {
    }
}
