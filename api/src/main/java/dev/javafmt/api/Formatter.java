package dev.javafmt.api;

import dev.javafmt.api.spi.FormatterProvider;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.ServiceLoader;

/// Formats Java source according to a fixed, opinionated style.
///
/// Obtain an instance via {@link #defaults()} (no configuration), {@link #create(Config)}
/// (custom configuration), or {@link #create(ClassLoader, Config)} (custom classloader,
/// for callers that load the implementation jar at runtime — e.g. an IDE plugin loading
/// a project-pinned formatter version).
///
/// The implementation is discovered via {@link java.util.ServiceLoader}; the
/// `dev.javafmt:core` artifact registers itself as the provider.
public interface Formatter {

    /// Formats a complete Java compilation unit. Returns one of the nested {@link Result}
    /// variants — success, syntax error, or internal failure.
    ///
    /// Equivalent to {@link #format(String, String)} with no file name, so the source is
    /// always parsed as an ordinary compilation unit. Use the two-argument overload for a
    /// `module-info.java`, whose module syntax the parser only recognizes when told the unit
    /// name.
    default Result format(String source) {
        return format(source, null);
    }

    /// Formats a complete Java compilation unit, using {@code fileName} (if any) to resolve
    /// file-name-dependent parsing. The only case that currently matters is `module-info.java`:
    /// its `module`/`requires`/`exports`/... grammar is a syntax error unless the parser knows
    /// the unit is named `module-info.java`, so a module declaration is only formatted when a
    /// matching {@code fileName} is supplied. {@code fileName} may be a bare name or a full
    /// path; only its final path segment is consulted.
    ///
    /// @param fileName the source file's name or path, or {@code null} if unknown (e.g. stdin)
    default Result format(String source, @Nullable String fileName) {
        // Default cross-delegates to the single-argument form so that an implementation
        // compiled against an older api — one that only overrides format(String) — still links
        // when reached through this overload (it simply ignores the file name). A current
        // implementation overrides this method and does the real work; every implementation
        // overrides exactly one of the two, so there is no infinite recursion.
        return format(source);
    }

    // ── construction ─────────────────────────────────────────────────────────

    /// Default configuration, resolved against the thread context classloader.
    static Formatter defaults() {
        return create(Config.defaults());
    }

    /// Custom configuration, resolved against the thread context classloader.
    static Formatter create(Config config) {
        return create(Thread.currentThread().getContextClassLoader(), config);
    }

    /// Custom configuration, resolved against a specific classloader.
    ///
    /// Callers (e.g. IDE plugins) that load the implementation jar at runtime should
    /// pass the loader whose classpath contains both this api module and the impl jar.
    /// Standard library users should prefer {@link #defaults()} or {@link #create(Config)}.
    static Formatter create(ClassLoader loader, Config config) {
        return ServiceLoader.load(FormatterProvider.class, loader)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No FormatterProvider on classpath — add dev.javafmt:core"))
                .create(config);
    }

    // ── nested types ─────────────────────────────────────────────────────────

    /// The outcome of a {@link Formatter#format(String)} call.
    ///
    /// New variants are a major-version event; callers writing exhaustive `switch`
    /// patterns over `Result` should still include a `default ->` arm so that future
    /// additions don't break them at compile time.
    sealed interface Result {
        record Success(String formatted) implements Result {}
        record SyntaxError(List<Problem> problems) implements Result {}
        record Failure(Throwable error) implements Result {}
    }

    /// A syntax problem reported by the parser.
    record Problem(int line, int column, String message) {}

    /// Immutable formatter configuration.
    ///
    /// Implemented as a class (rather than a record) so that new knobs can be added
    /// as additive `with*` methods + accessors without breaking the canonical
    /// constructor signature — which a record would lock forever.
    final class Config {
        /// Sentinel meaning "the latest JLS the implementation supports."
        /// Keeps the api module free of JDT version constants.
        public static final int LATEST_RELEASE = -1;

        private final int javaRelease;
        private final boolean enablePreview;
        private final int lineLength;

        private Config(int javaRelease, boolean enablePreview, int lineLength) {
            this.javaRelease = javaRelease;
            this.enablePreview = enablePreview;
            this.lineLength = lineLength;
        }

        public static Config defaults() {
            return new Config(LATEST_RELEASE, false, 100);
        }

        public int javaRelease()       { return javaRelease; }
        public boolean enablePreview() { return enablePreview; }
        public int lineLength()        { return lineLength; }

        public Config withRelease(int release) {
            return new Config(release, enablePreview, lineLength);
        }
        public Config withPreview(boolean preview) {
            return new Config(javaRelease, preview, lineLength);
        }
        public Config withLineLength(int lineLength) {
            return new Config(javaRelease, enablePreview, lineLength);
        }
    }
}
