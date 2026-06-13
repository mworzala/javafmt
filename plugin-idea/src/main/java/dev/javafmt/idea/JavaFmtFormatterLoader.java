package dev.javafmt.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import dev.javafmt.api.Formatter;
import dev.javafmt.api.Formatter.Config;
import dev.javafmt.api.Formatter.Result;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Loads the formatter from the user's pinned Gradle-plugin jars at runtime so the IDE
/// plugin can be shipped without bundling `core` and version drift between IDE plugin
/// and Gradle plugin is impossible.
///
/// The user's jars are mounted under a {@link BridgeAwareClassLoader} whose parent is
/// the JDK platform classloader (so no IDE-plugin types leak into the formatter), but
/// which delegates the `dev.javafmt.api.*` packages back to the IDE plugin's
/// classloader. That guarantees the {@link Formatter} interface both sides see is the
/// same {@code Class} object, so the {@code instanceof}/cast at the api boundary
/// always succeeds — independent of whether the bridge jar is also reachable via the
/// user's pinned jars.
///
/// Loading the impl uses {@link java.util.ServiceLoader} via
/// {@link Formatter#create(ClassLoader, Config)} — no reflection in this class.
@Service(Service.Level.PROJECT)
public final class JavaFmtFormatterLoader implements Disposable {

    public static JavaFmtFormatterLoader getInstance(@NotNull Project project) {
        return project.getService(JavaFmtFormatterLoader.class);
    }

    private @Nullable List<String> cachedPaths;
    private @Nullable URLClassLoader cachedLoader;
    private @Nullable Formatter cachedFormatter;

    public synchronized @NotNull String format(@NotNull List<String> paths, @NotNull String source,
            @Nullable String fileName) throws FormatException {
        if (paths.isEmpty()) {
            throw new FormatException("javafmt is not configured for this project (no formatter jar detected)");
        }
        var formatter = formatterFor(paths);
        // Pass the file name so a module-info.java is parsed as a module unit. A formatter
        // pinned to an older api ignores it (the two-arg overload defaults to the one-arg form).
        var result = formatter.format(source, fileName);
        return switch (result) {
            case Result.Success s -> s.formatted();
            case Result.Failure f -> throw new FormatException(
                    "javafmt failed: " + (f.error().getMessage() != null ? f.error().getMessage() : f.error().getClass().getSimpleName()),
                    f.error());
            case Result.SyntaxError ignored -> throw new FormatException("javafmt could not format source (syntax error)");
            // No default arm today; if api adds a new Result variant we want the
            // compile error so we treat it deliberately.
        };
    }

    public synchronized @NotNull ClassLoader getOrCreateClassLoader(@NotNull List<String> paths) throws FormatException {
        return loaderFor(paths);
    }

    private @NotNull Formatter formatterFor(@NotNull List<String> paths) throws FormatException {
        var loader = loaderFor(paths);
        if (cachedFormatter == null) {
            try {
                cachedFormatter = Formatter.create(loader, Config.defaults());
            } catch (IllegalStateException e) {
                throw new FormatException(
                        "No FormatterProvider service registered in pinned formatter jars: " + e.getMessage(), e);
            }
        }
        return cachedFormatter;
    }

    private @NotNull URLClassLoader loaderFor(@NotNull List<String> paths) throws FormatException {
        if (paths.equals(cachedPaths) && cachedLoader != null) {
            return cachedLoader;
        }
        invalidate();
        var urls = new ArrayList<URL>(paths.size());
        for (var p : paths) {
            urls.add(toUrl(p));
        }
        cachedLoader = new BridgeAwareClassLoader(
                urls.toArray(new URL[0]),
                JavaFmtFormatterLoader.class.getClassLoader()
        );
        cachedPaths = List.copyOf(paths);
        return cachedLoader;
    }

    private static @NotNull URL toUrl(@NotNull String path) throws FormatException {
        try {
            if (path.startsWith("file:") || path.startsWith("jar:") || path.startsWith("http:") || path.startsWith("https:")) {
                return new URI(path).toURL();
            }
            return Path.of(path).toUri().toURL();
        } catch (URISyntaxException | IOException e) {
            throw new FormatException("Invalid jar path: " + path, e);
        }
    }

    private void invalidate() {
        cachedFormatter = null;
        if (cachedLoader != null) {
            try { cachedLoader.close(); } catch (IOException ignored) {}
            cachedLoader = null;
        }
        cachedPaths = null;
    }

    @Override
    public synchronized void dispose() {
        invalidate();
    }

    /// URLClassLoader that delegates `dev.javafmt.api.*` (and `…api.spi.*`) back to
    /// a "bridge provider" classloader — typically the IDE plugin's classloader, which
    /// has the api jar on its classpath. Everything else loads from the supplied URLs
    /// or the platform classloader parent.
    ///
    /// Without this delegation, ServiceLoader-found impls would end up implementing a
    /// different `Formatter` interface (loaded twice — once from the IDE plugin, once
    /// from the user's pinned jars if the bridge is included transitively), and the
    /// cast would fail with `ClassCastException`.
    private static final class BridgeAwareClassLoader extends URLClassLoader {
        private static final String API_PREFIX = "dev.javafmt.api.";
        private final ClassLoader bridgeProvider;

        BridgeAwareClassLoader(URL[] urls, ClassLoader bridgeProvider) {
            super("javafmt-formatter", urls, ClassLoader.getPlatformClassLoader());
            this.bridgeProvider = bridgeProvider;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(API_PREFIX)) {
                var c = bridgeProvider.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }

    public static class FormatException extends Exception {
        public FormatException(@NotNull String message) { super(message); }
        public FormatException(@NotNull String message, @NotNull Throwable cause) { super(message, cause); }
    }
}
