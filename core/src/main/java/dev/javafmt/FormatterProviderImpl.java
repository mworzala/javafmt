package dev.javafmt;

import dev.javafmt.api.Formatter;
import dev.javafmt.api.spi.FormatterProvider;
import org.eclipse.jdt.core.dom.AST;

/// ServiceLoader-discovered provider that hands the api a {@link FormatterImpl}.
///
/// Registered via `META-INF/services/dev.javafmt.api.spi.FormatterProvider`. Not
/// referenced by name from outside the SPI lookup; users get here through
/// {@link Formatter#create(Formatter.Config)}.
public final class FormatterProviderImpl implements FormatterProvider {

    public FormatterProviderImpl() {}

    @Override
    public Formatter create(Formatter.Config config) {
        int release = config.javaRelease() == Formatter.Config.LATEST_RELEASE
                ? AST.getJLSLatest()
                : config.javaRelease();
        return new FormatterImpl(release, config.enablePreview(), config.lineLength());
    }
}
