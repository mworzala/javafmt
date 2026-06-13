# CLAUDE.md

## What this is

**javafmt** is a highly opinionated, zero-configuration code formatter for Java. The full style spec
lives in `STYLE.md`, and `core/src/main/java/dev/javafmt/AstToDoc.java` is its implementation.
When changing formatting behavior, keep the two in sync.

Two invariants every reformat must uphold (both enforced by tests):

1. **AST equivalence** — reformatting never changes program meaning.
2. **Idempotence** — `format(format(x)) == format(x)`.

## Build & test commands

```bash
./gradlew build                       # compile + test every module
./gradlew :core:test                  # core unit + golden-file tests (the main dev loop)
./gradlew :core:test --tests 'dev.javafmt.FileTest'   # just the .test golden cases
./gradlew :core:corpusTest            # AST-equiv/idempotence over real-world repos (see below)
./gradlew :cli:run --args='check .'   # run the CLI against a path
./gradlew :cli:run --args='format src/main/java'
./gradlew :cli:shadowJar              # build the standalone javafmt-<version>.jar
./gradlew :plugin-gradle:functionalTest   # TestKit tests for the Gradle plugin
```

- **JDK 25** is required to build (the `cli` module targets language level 25; `api`/`core`
  target 21 via Gradle toolchains, so a single JDK 25 provisions everything). CI uses GraalVM 25.
- The default `test` task **excludes** the `corpus` tag. `corpusTest` runs it; it first
  shallow-clones the corpora declared in `core/build.gradle.kts` into `core/build/corpus/`.
  To add a corpus, append a `Corpus(name, repo, tag)` entry — nothing else needs changing.
- `JAVAFMT_VERSION` env var sets the published version (defaults to `dev`).

### Golden-file tests

`core/src/test/resources/testcases/**/*.test` are the primary regression suite. Each file is
`input` + a `---\n` separator + `expected output`. `FileTest` runs each as a parameterized case
and additionally asserts comment preservation and idempotence on it. Adding a `.test` file is the
normal way to pin a formatting behavior; no code change is needed to register it.

## Architecture

### Modules (Gradle multi-module, Kotlin DSL)

| Module          | Purpose                                                                                                                                                     | Key dependency                               |
|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| `api`           | Stable public API + SPI. `Formatter`, `Formatter.Config`, `Formatter.Result`, `spi.FormatterProvider`. Published as `dev.javafmt:api`.                      | jspecify only                                |
| `core`          | The formatter. The only module that touches the parser. Published as `dev.javafmt:core`.                                                                    | Eclipse JDT (`org.eclipse.jdt.core`), `:api` |
| `cli`           | `dev.javafmt.cli.Main` — `format`/`check` commands, stdin, `--only-changed`, gitignore-aware walking, parallel processing.                                  | `:core`, java-diff-utils                     |
| `plugin-gradle` | Gradle plugin `dev.javafmt.gradle` (`JavaFmtPlugin`). Registers `formatJava` + `checkFormat` tasks.                                                         | `:core`                                      |
| `plugin-idea`   | IntelliJ plugin. Replaces the built-in Java formatter. Depends on `:api` only and loads `:core` **dynamically at runtime** from a project-pinned classpath. | `:api`                                       |

The API is consumed only through `Formatter.defaults()` / `Formatter.create(...)`, which
`ServiceLoader`-discover the `FormatterProvider` registered by `core` (`FormatterProviderImpl`
→ `FormatterImpl`). `Formatter.create(ClassLoader, Config)` exists specifically so the IntelliJ
plugin can load a project's pinned formatter version off a separate classloader — that is why
`api` carries no JDT dependency.

### Core formatting pipeline (`FormatterImpl.format`)

The formatter is a classic Wadler/Prettier-style pretty-printer. The flow:

1. **Parse** — Eclipse JDT `ASTParser` → `CompilationUnit`. `ASTParser` is cached per-thread
   (it isn't thread-safe, and constructing one is costly); compiler options are re-applied on
   every call because JDT resets them to Java-8 compliance after each `createAST`.
2. **`CommentMap.build`** — attaches every comment to the nearest AST node as `leading`,
   `trailing`, or `dangling`. Identity-keyed (never by source position after build), so
   attachments are stable across reformats. Throws if any eligible comment goes unattached.
3. **`AstToDoc`** (an `ASTVisitor`, ~4200 lines — the heart of the formatter) walks the AST and
   builds a `Doc` IR. It also emits attached comments inline, and in `postVisit` runs a
   statement/declaration-level "orphan mop-up" that catches comments no construct-specific
   handler emitted.
4. **`Doc`** — the pretty-print IR: `Text`, `Line`, `SoftLine`, `HardLine`, `Indent`, `Concat`,
   `Group` (fit-flat-or-break), `ConditionalGroup` (first alternative that fits). Two special
   nodes: `BoundaryLine` (renders like `SoftLine` but always terminates the line during an
   enclosing group's fit check — used by the method-chain printer) and `CommentBreak` (forced
   line end after a trailing line comment that absorbs a following break so it doesn't stack
   into a blank line).
5. **`DocPrinter`** — renders the `Doc` to a string. Uses a two-phase, rest-aware `fits` check
   (walk the candidate, then the in-flight rest of the document) and array-backed work/scratch
   stacks to do zero allocation per fit check.

`ImportOrderer` groups/sorts imports (consulted by `AstToDoc`).

`ModuleInfoToDoc` is a companion to `AstToDoc` for `module-info.java`: `visit(CompilationUnit)`
delegates the module declaration and its directives to it. It is not an `ASTVisitor` — module
directives are an expression-free sublanguage rendered straight from the AST — but it shares
`AstToDoc`'s `CommentMap` and emission ledger (via a back-reference) so the comment-conservation
law spans both. `ModuleDirectiveOrderer` groups directives by kind (the module analogue of
`ImportOrderer`). Module parsing requires the parser be told the unit name (`module-info.java`),
so `Formatter.format(source, fileName)` threads it through; the no-filename overload can't format
a module.

### The comment conservation law

Comment handling is the subtlest part of the codebase and is guarded by a two-sided invariant:

- **Attachment** (`CommentMap`): every eligible comment is attached to exactly one node, or
  `build` throws.
- **Emission** (`CommentLedger`): every *attached* comment must be *emitted*. `AstToDoc` records
  each comment it renders (`emittedComments()`); the ledger diffs that against the attached set.

The `javafmt.commentLedger` system property selects behavior for an un-emitted comment:

- `warn` (production default) — force-append it at the end of the compilation unit so it can
  never be lost.
- `strict` — no fallback; an un-emitted comment is a loud, located, build-breaking failure.
  **Tests and the corpus run `strict`** so dropped comments surface during development rather
  than being silently papered over.
- `off` — legacy escape hatch, neither ledger nor fallback.

`///` Markdown-style Javadoc is excluded from `CommentMap` attachment; it's consumed directly by
the visitor that owns the following declaration.

### IntelliJ dynamic loading

`plugin-idea` does not bundle `core`. A Gradle Tooling API model builder
(`JavaFmtToolingModelBuilder` + the serialized `dev.javafmt.tool.JavaFmtToolInfo`) reports the
project's resolved formatter classpath to the IDE; `JavaFmtFormatterLoader` then loads `core`
off a `URLClassLoader` and calls it through the `api` interfaces. `JavaFmtToolInfo` is duplicated
in `plugin-gradle` and `plugin-idea` and is serialized across the Tooling API boundary — the two
copies must stay structurally identical. The plugin's test wires a real `:core` jar set in via the
`javafmt.test.classpath` system property (the `formatterTestClasspath` configuration) so dynamic
loading is exercised without putting `core` on the plugin's own classpath.
