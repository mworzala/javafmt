# javafmt

javafmt is a highly opinionated, zero-configuration code formatter for Java. You hand it your source, it gives
you back consistently formatted code. No style options, no debates, no `.editorconfig` archaeology.

### Why javafmt?

Java has no shortage of formatters (google-java-format, Eclipse JDT, IntelliJ's built-in, Palantir's
fork, Spotless as a wrapper around any of them). javafmt exists because none of them give you all three of
the following at once:

- **Zero config.** No thought or debate.
- **Tier-one Gradle and IntelliJ integration.** Both are first-party plugins. No need to configure Spotless, 
  no manual *Reformat Code* dance. The IntelliJ plugin transparently replaces the built-in formatter, so
  format-on-save and **Reformat Code** route through javafmt.
- **Fast support for new Java releases.** javafmt is built on the Eclipse JDT parser, which ships support for new
  language features alongside the JDK release allowing javafmt to support releases quickly.

---

## Install

### Gradle Plugin

Add the plugin to your build:

```kotlin
// build.gradle.kts
plugins {
    id("dev.javafmt.gradle") version "<latest>"
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
through javafmt instead of IntelliJ's built-in formatter.

### CLI

Download `javafmt-<version>.jar` from the [latest release](https://github.com/mworzala/javafmt/releases/latest)
and run it with any JDK 25+:

```bash
# Format files in place
java -jar javafmt.jar format src/main/java

# Check formatting (exits non-zero if any file would change)
java -jar javafmt.jar check src/main/java

# Format from stdin
cat Foo.java | java -jar javafmt.jar format -
```

Run `java -jar javafmt.jar --help` for the full list of options (`--threads`, `--release`,
`--enable-preview`, `--line-length`, `--verbose`).

### As a library

The formatter is published to Maven Central as `dev.javafmt:core`:

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.javafmt:core:<latest>")
}
```

```java
import dev.javafmt.api.Formatter;
import dev.javafmt.api.Formatter.Result;

var result = Formatter.defaults().format(source);
switch (result) {
    case Result.Success(var formatted)    -> System.out.println(formatted);
    case Result.SyntaxError(var problems) -> problems.forEach(System.err::println);
    case Result.Failure(var error)        -> error.printStackTrace();
}
```

For custom config:

```java
var formatter = Formatter.create(Formatter.Config.defaults().withLineLength(120));
```

## Style

javafmt makes all formatting decisions for you. See [STYLE.md](STYLE.md) for a full account of the choices
it makes and the reasoning behind them.

## Contributing

Contributions via PRs and issues are always welcome.

## License

This project is licensed under the [MIT LICENSE](LICENSE).
