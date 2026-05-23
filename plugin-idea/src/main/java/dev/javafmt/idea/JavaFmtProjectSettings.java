package dev.javafmt.idea;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@State(
        name = "BlackProjectSettings",
        storages = @Storage("black-formatter.xml")
)
@Service(Service.Level.PROJECT)
public final class JavaFmtProjectSettings implements PersistentStateComponent<JavaFmtProjectSettings.State> {

    public static class State {
        public List<String> formatterClasspath = new ArrayList<>();
    }

    public static JavaFmtProjectSettings getInstance(Project project) {
        return project.getService(JavaFmtProjectSettings.class);
    }

    private State myState = new State();

    @Override
    public State getState() { return myState; }

    @Override
    public void loadState(@NonNull State state) {
        if (state.formatterClasspath == null) state.formatterClasspath = new ArrayList<>();
        myState = state;
    }

    public @NonNull List<String> getFormatterClasspath() {
        return List.copyOf(myState.formatterClasspath);
    }

    public void setFormatterClasspath(@NonNull List<String> paths) {
        myState.formatterClasspath = new ArrayList<>(paths);
    }

    /// Convenience: the plugin-gradle jar (first entry in the classpath), used for
    /// display in the settings UI. The remaining entries are core + JDT transitive deps.
    public @Nullable String getPrimaryJarPath() {
        return myState.formatterClasspath.isEmpty() ? null : myState.formatterClasspath.get(0);
    }

    public boolean isEnabled() {
        return !myState.formatterClasspath.isEmpty();
    }

    public void clear() {
        myState = new State();
    }

}
