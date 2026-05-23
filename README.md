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
import dev.javafmt.Formatter;

var result = new Formatter().format(source);
switch (result) {
    case Formatter.Success(var formatted)   -> System.out.println(formatted);
    case Formatter.SyntaxError(var problems) -> problems.forEach(System.err::println);
    case Formatter.Failure(var error)        -> error.printStackTrace();
}
```

## Style

javafmt makes all formatting decisions for you. See [STYLE.md](STYLE.md) for a full account of the choices
it makes and the reasoning behind them.

## Contributing

Contributions via PRs and issues are always welcome.

## License

This project is licensed under the [MIT LICENSE](LICENSE).
