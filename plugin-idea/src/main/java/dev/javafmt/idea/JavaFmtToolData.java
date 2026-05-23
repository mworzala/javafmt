package dev.javafmt.idea;

import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.serialization.PropertyMapping;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.List;

public record JavaFmtToolData(@NotNull List<String> paths) implements Serializable {
    public static final Key<JavaFmtToolData> KEY = Key.create(JavaFmtToolData.class, 500);

    @PropertyMapping({"paths"})
    public JavaFmtToolData {
    }
}
