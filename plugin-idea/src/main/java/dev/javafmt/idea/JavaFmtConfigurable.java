package dev.javafmt.idea;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class JavaFmtConfigurable implements Configurable {
    private final Project project;
    private @Nullable JPanel panel;
    private @Nullable JBLabel statusLabel;
    private @Nullable JBLabel primaryPathLabel;
    private @Nullable JBLabel versionLabel;
    private @Nullable DefaultListModel<String> classpathModel;
    private @Nullable JButton revealButton;

    public JavaFmtConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "javafmt";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (panel != null) return panel;

        var content = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.weightx = 1.0;
        gbc.insets = JBUI.insets(4);

        gbc.gridy = 0;
        statusLabel = new JBLabel();
        content.add(statusLabel, gbc);

        gbc.gridy++;
        content.add(new JBLabel("Formatter jar:"), gbc);

        gbc.gridy++;
        primaryPathLabel = new JBLabel();
        primaryPathLabel.setCopyable(true);
        content.add(primaryPathLabel, gbc);

        gbc.gridy++;
        var revealRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        revealButton = new JButton("Reveal in Finder", AllIcons.Actions.ShowAsTree);
        revealButton.addActionListener(e -> onReveal());
        revealRow.add(revealButton);
        content.add(revealRow, gbc);

        gbc.gridy++;
        versionLabel = new JBLabel();
        content.add(versionLabel, gbc);

        gbc.gridy++;
        content.add(new JBLabel("Resolved classpath:"), gbc);

        gbc.gridy++;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        classpathModel = new DefaultListModel<>();
        var list = new JBList<>(classpathModel);
        var scroll = new JBScrollPane(list);
        scroll.setPreferredSize(new Dimension(400, 120));
        content.add(scroll, gbc);

        gbc.gridy++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weighty = 0;
        var actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        var refreshButton = new JButton("Re-detect (re-import Gradle)");
        refreshButton.addActionListener(e -> onRefresh());
        actionsRow.add(refreshButton);
        content.add(actionsRow, gbc);

        panel = new JPanel(new BorderLayout());
        panel.add(content, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
        // Settings are populated by the Gradle import pipeline, not edited here.
    }

    @Override
    public void reset() {
        if (statusLabel == null) return;
        var settings = JavaFmtProjectSettings.getInstance(project);
        var classpath = settings.getFormatterClasspath();
        var primary = settings.getPrimaryJarPath();

        if (classpath.isEmpty()) {
            statusLabel.setText("Status: not detected — apply the `dev.javafmt.gradle` plugin and re-sync.");
            statusLabel.setIcon(AllIcons.General.Warning);
            primaryPathLabel.setText("(none)");
            versionLabel.setText("Version: —");
            revealButton.setEnabled(false);
        } else {
            statusLabel.setText("Status: detected");
            statusLabel.setIcon(AllIcons.General.InspectionsOK);
            primaryPathLabel.setText(toDisplayPath(primary));
            versionLabel.setText("Version: " + Objects.requireNonNullElse(readVersion(primary), "unknown (Implementation-Version not set in MANIFEST)"));
            revealButton.setEnabled(true);
        }

        classpathModel.clear();
        for (var p : classpath) {
            classpathModel.addElement(toDisplayPath(p));
        }
    }

    private void onReveal() {
        var primary = JavaFmtProjectSettings.getInstance(project).getPrimaryJarPath();
        if (primary == null) return;
        var f = toFile(primary);
        if (f != null) {
            RevealFileAction.openFile(f);
        }
    }

    private void onRefresh() {
        var basePath = project.getBasePath();
        if (basePath == null) return;
        ExternalSystemUtil.refreshProject(
                basePath,
                new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
        );
    }

    private static @NotNull String toDisplayPath(@Nullable String p) {
        if (p == null) return "(none)";
        var f = toFile(p);
        return f != null ? f.getAbsolutePath() : p;
    }

    private static @Nullable File toFile(@NotNull String pathOrUrl) {
        try {
            if (pathOrUrl.startsWith("file:")) {
                return new File(new URI(pathOrUrl));
            }
            return Path.of(pathOrUrl).toFile();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String readVersion(@Nullable String jarPath) {
        if (jarPath == null) return null;
        var f = toFile(jarPath);
        if (f == null || !f.isFile()) return null;
        try (var jar = new JarFile(f)) {
            Manifest mf = jar.getManifest();
            if (mf == null) return null;
            var attrs = mf.getMainAttributes();
            var version = attrs.getValue("Implementation-Version");
            if (version != null) return version;
            return attrs.getValue("Bundle-Version");
        } catch (IOException e) {
            return null;
        }
    }
}
