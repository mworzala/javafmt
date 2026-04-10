# javafmt

javafmt is a highly opinionated, zero-configuration code formatter for Java. You hand it your source, it gives
you back consistently formatted code. No style options, no debates, no `.editorconfig` archaeology.

---

## Install

### Gradle Plugin

Add the plugin to your build:

```kotlin
// build.gradle.kts
plugins {
    id("dev.javafmt.gradle") version "0.1.0"
}
```

This registers two tasks:

| Task          | Description                                                                                               |
|---------------|-----------------------------------------------------------------------------------------------------------|
| `formatJava`  | Formats all Java source files in-place                                                                    |
| `checkFormat` | Verifies formatting without modifying files and exits non-zero if any file would change. Suitable for CI. |

```bash
# Run formatting
./gradlew formatJava

# Check format in CI
./gradlew checkFormat
```

### IntelliJ Plugin

The IntelliJ plugin detects when the Gradle plugin is active in a project and automatically replaces the
built-in Java formatter. There is nothing new to invoke, **Reformat Code** and format-on-save both route
through black instead of IntelliJ's built-in formatter.

### CLI
TODO

### As a library
The formatter is available as a library for standalone use, published to Maven Central.

TODO

## Style

black.java makes all formatting decisions for you. See [STYLE.md](STYLE.md) for a full account of the choices it makes
and the reasoning behind them.

## Contributing

Contributions via PRs and issues are always welcome.

## License

This project is licensed under the [MIT LICENSE](LICENSE).
