package dev.javafmt.api.spi;

import dev.javafmt.api.Formatter;

/// Service-loader SPI for {@link Formatter} implementations.
///
/// Library users should NOT depend on this directly. Construct a Formatter via
/// {@link Formatter#defaults()} or {@link Formatter#create(Formatter.Config)} instead;
/// those methods load the provider via {@link java.util.ServiceLoader} internally.
///
/// This type is public solely because implementations live in a different package and
/// must be able to declare `implements FormatterProvider`. Treat it as internal to the
/// `dev.javafmt:core` ↔ `dev.javafmt:api` contract.
public interface FormatterProvider {
    Formatter create(Formatter.Config config);
}
