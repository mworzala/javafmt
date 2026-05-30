package dev.javafmt.idea;

import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

public class JavaFmtFormattingService extends AsyncDocumentFormattingService {
    // We can format fragments, although it still parses the entire file so may not be perfect and is not faster.
    private static final Set<Feature> FEATURES = EnumSet.of(Feature.FORMAT_FRAGMENTS);

    @Override
    protected @NotNull @NlsSafe String getName() {
        return "javafmt";
    }

    @Override
    public @NotNull Set<Feature> getFeatures() {
        return FEATURES;
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return "javafmt";
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        if (!file.getLanguage().is(JavaLanguage.INSTANCE)) return false;
        return JavaFmtProjectSettings.getInstance(file.getProject()).isEnabled();
    }

    @Override
    protected @Nullable FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        return new FormattingTask() {
            @Override
            public void run() {
                var project = request.getContext().getProject();
                var paths = JavaFmtProjectSettings.getInstance(project).getFormatterClasspath();
                var loader = JavaFmtFormatterLoader.getInstance(project);
                var source = request.getDocumentText();
                try {
                    var formatted = loader.format(paths, source);
                    var result = RangeRestrictedFormat.restrict(source, formatted, request.getFormattingRanges());
                    request.onTextReady(result);
                } catch (JavaFmtFormatterLoader.FormatException e) {
                    request.onError("javafmt", e.getMessage());
                }
            }

            @Override
            public boolean cancel() {
                return true;
            }
        };
    }
}
