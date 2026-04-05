package black.idea;

import black.Black;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

public class BlackFormattingService extends AsyncDocumentFormattingService {
    private static final Set<Feature> FEATURES = EnumSet.noneOf(Feature.class);

    @Override
    protected @NotNull @NlsSafe String getName() {
        return "Black Formatter";
    }

    @Override
    public @NotNull Set<Feature> getFeatures() {
        return FEATURES;
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return "Black Formatter";
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        if (!file.getLanguage().is(JavaLanguage.INSTANCE)) return false;
        return BlackProjectSettings.getInstance(file.getProject()).isEnabled();
    }

    @Override
    protected @Nullable FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        return new FormattingTask() {
            @Override
            public void run() {
                var project = request.getContext().getProject();
                var projectSettings = BlackProjectSettings.getInstance(project);
                var jarPath = projectSettings.getFormatterJarPath();

                request.onTextReady(
                        Black.formatSource(request.getDocumentText())
                );
            }

            @Override
            public boolean cancel() {
                return true;
            }
        };
    }
}
