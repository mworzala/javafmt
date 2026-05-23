package dev.javafmt.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Loads the formatter from the user's pinned Gradle-plugin jars at runtime via a
/// URLClassLoader, so the IDE plugin can be shipped without bundling `core` and
/// version drift between IDE plugin and Gradle plugin is impossible.
///
/// The URLClassLoader's parent is the JDK platform classloader (not the plugin's
/// own), so `dev.javafmt.*` types resolved here cannot leak in from the IDE
/// plugin's classpath even if (mis)configured.
@Service(Service.Level.PROJECT)
public final class JavaFmtFormatterLoader implements Disposable {

    public static JavaFmtFormatterLoader getInstance(@NotNull Project project) {
        return project.getService(JavaFmtFormatterLoader.class);
    }

    private @Nullable List<String> cachedPaths;
    private @Nullable URLClassLoader cachedLoader;
    private @Nullable Method cachedFormatMethod;
    private @Nullable Class<?> cachedSuccessClass;
    private @Nullable Method cachedSuccessFormattedMethod;
    private @Nullable Class<?> cachedFailureClass;
    private @Nullable Method cachedFailureErrorMethod;

    public synchronized @NotNull String format(@NotNull List<String> paths, @NotNull String source) throws FormatException {
        if (paths.isEmpty()) {
            throw new FormatException("javafmt is not configured for this project (no formatter jar detected)");
        }
        var loader = loaderFor(paths);
        try {
            var formatterClass = loader.loadClass("dev.javafmt.Formatter");
            if (cachedFormatMethod == null) {
                cachedFormatMethod = formatterClass.getMethod("format", String.class);
            }
            var instance = formatterClass.getDeclaredConstructor().newInstance();
            var result = cachedFormatMethod.invoke(instance, source);
            return extractResult(result, loader);
        } catch (FormatException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            throw new FormatException(
                    "Failed to invoke formatter from " + paths.get(0) + ": " + cause.getMessage(),
                    cause
            );
        }
    }

    public synchronized @NotNull ClassLoader getOrCreateClassLoader(@NotNull List<String> paths) throws FormatException {
        return loaderFor(paths);
    }

    private @NotNull String extractResult(@NotNull Object result, @NotNull ClassLoader loader) throws FormatException, ReflectiveOperationException {
        if (cachedSuccessClass == null) {
            cachedSuccessClass = loader.loadClass("dev.javafmt.Formatter$Success");
            cachedSuccessFormattedMethod = cachedSuccessClass.getMethod("formatted");
            cachedFailureClass = loader.loadClass("dev.javafmt.Formatter$Failure");
            cachedFailureErrorMethod = cachedFailureClass.getMethod("error");
        }
        if (cachedSuccessClass.isInstance(result)) {
            return (String) cachedSuccessFormattedMethod.invoke(result);
        }
        if (cachedFailureClass.isInstance(result)) {
            var throwable = (Throwable) cachedFailureErrorMethod.invoke(result);
            throw new FormatException("javafmt failed: " + throwable.getMessage(), throwable);
        }
        // SyntaxError or unknown subtype — surface a generic message rather than the
        // sealed-interface name, which would leak implementation detail to the user.
        throw new FormatException("javafmt could not format source (syntax error)");
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
        cachedLoader = new URLClassLoader(
                "javafmt-formatter",
                urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader()
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
        cachedFormatMethod = null;
        cachedSuccessClass = null;
        cachedSuccessFormattedMethod = null;
        cachedFailureClass = null;
        cachedFailureErrorMethod = null;
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

    public static class FormatException extends Exception {
        public FormatException(@NotNull String message) { super(message); }
        public FormatException(@NotNull String message, @NotNull Throwable cause) { super(message, cause); }
    }
}
