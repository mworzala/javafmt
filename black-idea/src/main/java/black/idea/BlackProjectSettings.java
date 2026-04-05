package black.idea;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@State(
        name = "BlackProjectSettings",
        storages = @Storage("black-formatter.xml")
)
@Service(Service.Level.PROJECT)
public final class BlackProjectSettings implements PersistentStateComponent<BlackProjectSettings.State> {

    public static class State {
        public String formatterJarPath;
    }

    public static BlackProjectSettings getInstance(Project project) {
        return project.getService(BlackProjectSettings.class);
    }

    private State myState = new State();

    @Override
    public State getState() { return myState; }

    @Override
    public void loadState(@NonNull State state) {
        myState = state;
    }

    public @Nullable String getFormatterJarPath() {
        return myState.formatterJarPath;
    }

    public void setFormatterJarPath(String path) {
        myState.formatterJarPath = path;
    }

    public boolean isEnabled() {
        return myState.formatterJarPath != null;
    }

    public void clear() {
        myState = new State();
    }

}
