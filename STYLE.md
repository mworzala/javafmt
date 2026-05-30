# The javafmt Code Style

javafmt is an opinionated formatter. There is one style. Every formatting decision is
made for you. The only knobs are line length and the JLS release you're targeting.

The two invariants every reformat upholds:

1. **AST equivalence.** The reparsed output has the same AST as the input. Reformatting
   never changes program meaning.
2. **Idempotence.** `format(format(x)) == format(x)`. A second pass over already-formatted
   code is always a no-op.

---

## Contents

- [Width and indentation](#width-and-indentation)
- [Files: package, imports, types](#files-package-imports-types)
- [Blank lines](#blank-lines)
- [Type declarations](#type-declarations)
- [Records](#records)
- [Enums](#enums)
- [Methods and fields](#methods-and-fields)
- [Statements](#statements)
- [Method invocation arguments](#method-invocation-arguments)
- [Tuple-style arguments](#tuple-style-arguments)
- [Method chains](#method-chains)
- [Binary operators](#binary-operators)
- [Conditional (ternary) expressions](#conditional-ternary-expressions)
- [Assignments](#assignments)
- [Lambdas](#lambdas)
- [Switch](#switch)
- [try-with-resources](#try-with-resources)
- [`throws` clauses](#throws-clauses)
- [Annotations](#annotations)
- [Comments](#comments)
- [Javadoc](#javadoc)
- [Pattern matching](#pattern-matching)
- [Array initializers](#array-initializers)
- [Known limitations and quirks](#known-limitations-and-quirks)

---

## Width and indentation

- **Default line length: 100 columns.** Override on the CLI with `--line-length`.
- **Indentation: 4 spaces, never tabs.**
- Continuation indent is also 4 spaces (one level), measured from the parent's indent.
- Trailing whitespace on a line is always removed.
- Files end with a single newline.

The line length is a soft target. Where no break is available — a long string literal,
a long identifier — the line will exceed it. javafmt never inserts a line break that
would change meaning.

---

## Files: package, imports, types

The order is fixed: package declaration, then imports, then types. 
Exactly one blank line between each block.

```java
package com.example.foo;

import java.util.List;
import java.util.Map;
import static java.lang.Math.PI;
import a.b.C;

public class Foo {

    private int x;
    private int y;

    public Foo() {}
}
```

**Imports are kept in the order you wrote them.** javafmt does not currently sort or reorganize imports. 
(This may change in a future version.)

---

## Blank lines

Multiple consecutive blank lines collapse to one. Between two members of a class the
formatter preserves whether you had a blank line there or not so you keep grouping
information.

```java
class A {

    private int x;
    private int y;


    private int z;        // two blank lines above this collapse to one
}
```

becomes

```java
class A {

    private int x;
    private int y;

    private int z;
}
```

---

## Type declarations

`class`, `interface`, `enum`, `record`, and `@interface` all use the same general shape:
modifiers, keyword, name, type parameters, extends/implements/permits, then the body.

```java
class A {}

interface I {}

enum E {}

@interface Marker {}

record R() {}
```

Empty bodies render as `{}` on the same line as the declaration. Members are separated
by one blank line. Top-level types are separated by one blank line.

---

## Records

Record components are formatted like a parameter list: kept on one line if they fit,
otherwise broken vertically, one per line, with the closing `)` on its own line.

```java
public record User(String firstName, String lastName, int age) {}
```

If the header doesn't fit:

```java
public record User(
    String firstName,
    String lastName,
    String emailAddress,
    int age,
    boolean active,
    String role
) {}
```

---

## Enums

Enum constants are one per line, followed by `;` and then the body (if any). A blank
line separates the constants from the rest of the body.

```java
enum Color {
    RED("ff0000"),
    GREEN("00ff00"),
    BLUE("0000ff");

    private final String hex;

    Color(String hex) {
        this.hex = hex;
    }

    public String hex() {
        return hex;
    }
}
```

---

## Methods and fields

Method declarations fit on one line when they can. Parameters break vertically 
when the header is too wide.

```java
void shortMethod(int a, int b) {}

void wideMethod(
    String firstArg,
    String secondArg,
    String thirdArg,
    String fourthArg
) {
    // ...
}
```

Empty method bodies render as `{}` on the same line as the header:

```java
void f() {}
```

Otherwise the body opens on the same line as the header and closes on its own line.

Field declarations follow modifier / type / name / `=` initializer. Multi-fragment
field declarations (`int a, b, c;`) are kept on one line when short.

---

## Statements

A block always uses newlines for its statements, regardless of how short they are:

```java
class A {
    void f() {}        // empty body stays inline
    void g() {
        return;        // any body with content opens
    }
}
```

`if`, `while`, `for`, `synchronized`, etc. follow C-style spacing: `keyword (cond)`
followed by either a brace-block or a single statement.

```java
for (int i = 0; i < arr.length; i++) System.out.println(arr[i]);
for (var v : arr) System.out.println(v);
```

When an `if` body uses braces, the `else` follows the closing brace on the same line:

```java
if (x > 0) {
    doA();
} else {
    doB();
}
```

When an `if` body does NOT use braces, `else` moves to its own line so the non-braced
then-body stays visible:

```java
if (x > 0) doA();
else doB();

if (x > 0) doA();
else if (x < 0) doB();
else doC();
```

The decision is made per-branch based on whether *that branch's* then-body uses
braces, so mixed chains render exactly as written:

```java
if (x > 0) {
    doA();
} else doB();

if (x > 0) doA();
else {
    doB();
}
```

A labeled statement puts the label on its own line:

```java
outer:
for (int i = 0; i < 10; i++) { ... }
```

---

## Method invocation arguments

The first attempt is always flat:

```java
someMethod(a, b, c);
```

If the call doesn't fit, arguments break vertically, one per line, with the closing
`)` on its own line:

```java
someMethodWithVeryLongName(
    argumentOne,
    argumentTwo,
    argumentThree,
    argumentFour,
    argumentFive
);
```

**Trailing block-style arguments are special.** When the last argument is a
block-bodied lambda, a switch expression, or an anonymous-class `new X() { ... }`,
the closing `)` glues to the closing `}` and the block stays where it is:

```java
list.forEach(x -> {
    System.out.println(x);
    count++;
});

executor.submit(() -> {
    doSomething();
    doSomethingElse();
});
```

If there are other arguments before the trailing block, they break vertically and the
block stays trailing:

```java
register(
    "foo",
    config,
    () -> {
        run();
    }
);
```

---

## Tuple-style arguments

If your call has arguments arranged across multiple source lines in a "uniform rows
plus a smaller trailing row" shape (for example, a matrix or a coordinate list),
javafmt preserves the row structure rather than collapsing it to one-arg-per-line.

The trigger conditions are all of:

- 3 or more arguments
- Arguments span 2 or more source lines
- The first row has 2 or more arguments
- Every row except the last has the same number of arguments
- The last row has fewer arguments than the others
- The call doesn't fit flat on one line

When all of those hold, javafmt emits:

```java
callMyMatrixOperationLong(
    rowOneA, rowOneB, rowOneC,
    rowTwoA, rowTwoB, rowTwoC,
    rowThreeA, rowThreeB, rowThreeC,
    trailing
);
```

If you write the same call as a single source line (or break the row symmetry),
javafmt falls back to the standard one-per-line break:

```java
callMyMatrixOperationLong(
    rowOneA,
    rowOneB,
    rowOneC,
    rowTwoA,
    rowTwoB,
    rowTwoC
);
```

This means **you control row width by where you put your line breaks in the source.**
The formatter will keep your `2×N`, `3×N`, etc. layout intact. If the call already fits
flat, the rows collapse to a single line regardless of how the source was written.

> Why does this exist? Things like coordinate lists, matrix literals, and
> `register(NAME, value, NAME, value, ...)` calls become unreadable when broken to one
> argument per line. Tuple grouping lets you keep them compact without forcing the
> formatter to guess your intent.

---

## Method chains

A chain of **3 or more** method calls is formatted as a unit. There are three layouts;
javafmt picks the first one that fits.

**1. Flat:**

```java
stream.filter(x -> x > 0).map(x -> x * 2).collect(Collectors.toList());
```

**2. First segment stays with root, rest break:**

```java
aaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbb(predicate)
    .cccccccccccccccc(transformer)
    .dddddddddddddd(more);
```

This layout is used when the root is a simple name or qualified name, or when the
first call takes at most one argument.

**3. Every segment breaks, including the first:**

```java
compute(longArg)
    .bbbbbbbbbbbbb(predicate)
    .cccccccccccccc(transformer)
    .dddddddddddddd(more);
```

This layout is used for chains whose root is a method call or other complex expression.

A chain of only 1 or 2 calls usually stays flat (or, when it overflows, just wraps the
final call's own arguments). It still breaks at the `.`, though, when wrapping the
arguments alone would read badly — specifically when the first call carries arguments,
or when the receiver is a complex expression (a `new`, a cast, a parenthesized
expression — anything that isn't a plain name, field/array access, `this`, or a
literal). For example:

```java
this.button = new Button(3, 3)
    .onLeftClickAsync(() -> this.onVerifyPublish(hostSupplier.get(), onPublish));
```

breaks at the `.` rather than exploding the `new Button(3, 3)` arguments.

---

## Binary operators

Every non-assignment binary operator wraps the same way: when the expression doesn't
fit on one line, it breaks **before** each operator, with the operator at the start of
the continuation line, indented one level. This applies uniformly to arithmetic
(`+`, `-`, `*`, `/`, `%`), comparison (`<`, `>`, `<=`, `>=`, `==`, `!=`), shift
(`<<`, `>>`, `>>>`), logical (`&&`, `||`), bitwise (`&`, `|`, `^`), and `instanceof`.

```java
return aLongVariableName > 0 && anotherOneHere > 0 && yetAnotherVariableName > 0
    && extraExtra;
```

```java
return aaaaaaaaaaaaaaaa
    + bbbbbbbbbbbbbbbb
    + ccccccccccccccccc
    + dddddddddddddddd
    + eeeeeeeeeeeeeeeeee;
```

```java
return "this is a really long prefix string "
    + aLongVar
    + " middle bit "
    + bLongVar
    + " end";
```

```java
return aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    instanceof aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
```

Mixed-precedence expressions break at the **outermost** operator first; inner
subexpressions stay flat unless they themselves overflow:

```java
return aLongVariableName + somethingElseHere
    > anotherLongVariable + somethingElseEntirelyy + z;
```

Assignment operators (`=`, `+=`, `-=`, etc.) are handled separately — see
[Assignments](#assignments).

---

## Conditional (ternary) expressions

A ternary that fits stays flat:

```java
return condition ? branchA() : branchB();
```

A ternary that doesn't fit breaks with `?` and `:` at the start of continuation lines,
both indented:

```java
return conditionWithLongName
    ? expensiveComputationOneVeryLong()
    : alternativeBranchExpression();
```

---

## Assignments

The `=` always stays on the same line as the left-hand side. If the assignment
doesn't fit flat, the RHS breaks internally — never after the `=`.

Short assignments stay inline:

```java
int x = foo(a);
var y = bar.baz();
```

When the RHS is a method call that doesn't fit, the call's arguments break vertically:

```java
int aLocalVariableName = someMethodCallThatReturnsAnInteger(
    arg1,
    arg2,
    arg3,
    arg4,
    arg5,
    arg6
);
```

When the RHS is a lambda, anonymous class, switch expression, or array creation, the
block opens on the same line as `=` and its body breaks below:

```java
var x = new ArrayList<String>() {
    @Override
    public boolean add(String s) {
        return super.add(s.trim());
    }
};
```

When the RHS has no internal break point — a long string literal, a single very long
identifier — the line stays on one line and exceeds the line length. javafmt will
never insert a meaning-preserving break that splits the assignment.

```java
String reallyLongVariableName = "a string literal that pushes this field declaration past the line length limit";
```

Compound assignments (`+=`, `-=`, etc.) follow the same rule.

---

## Lambdas

Single-expression lambdas stay flat:

```java
Runnable r = () -> System.out.println("hi");
var g = (Integer a, Integer b) -> a + b;
```

Block-bodied lambdas keep their braces and break their body:

```java
list.forEach(x -> {
    System.out.println(x);
    count++;
});
```

A single-identifier parameter list drops the parentheses (`x -> ...`); multi-parameter
or typed parameter lists keep them.

---

## Switch

Both classic switch statements and the newer switch expressions are supported.

```java
String f(int x) {
    return switch (x) {
        case 1 -> "one";
        case 2, 3 -> "few";
        case 4 -> {
            var s = "four";
            yield s + s;
        }
        default -> "many";
    };
}
```

Arrow-style `case` rules keep the body on the same line. Block bodies open inline,
just like methods. Colon-style cases use a standard indented body.

---

## try-with-resources

Resources go inside `(...)`, separated by `;`. When they fit on one line, they stay
inline:

```java
try (var a = open("a"); var b = open("b")) {
    use(a, b);
}
```

When they don't fit, each resource goes on its own line, indented one level under the
opening `(`, with the closing `)` on its own line at the outer indent — the same shape
as a broken method parameter list:

```java
try (
    var firstResource = open("a");
    var secondResource = open("b");
    var thirdResource = open("c")
) {
    use(firstResource);
}
```

`catch` and `finally` continue to follow the closing `}` on the same line, as with any
braced block.

---

## `throws` clauses

A short `throws` clause stays on the method header:

```java
void f() throws IOException {}
```

A `throws` clause that pushes the header over the line length breaks vertically, with
each exception on its own line:

```java
void someLongMethodNameForce() throws
    ExceptionOne,
    ExceptionTwoLongName,
    ExceptionThreeAnotherLongName,
    ExceptionFour {}
```

---

## Annotations

**Declaration annotations** (annotations applied to a class, method, field, or
parameter declaration) go on their own line:

```java
@Deprecated
    @SuppressWarnings("rawtypes")
class A {

    @Deprecated
    @SuppressWarnings("unchecked")
    public List foo() { ... }

    @Deprecated
    private int x;
}
```

**Type-use annotations** (annotations that bind to a type, typically appearing after
modifiers) stay inline with the type:

```java
private static @Nullable String compute() { ... }

void g(@Nullable String s) {}
```

The formatter distinguishes the two cases by where the annotation appears: an
annotation that comes after a keyword modifier (`private`, `static`, etc.) is treated
as type-use; one that comes before any keyword modifiers is treated as declarational.

---

## Comments

Comments are preserved verbatim and attached to the nearest AST node. The formatter
classifies every comment as one of:

- **leading** — appears before a declaration or statement, on its own lines
- **trailing** — appears on the same line as the end of a declaration or statement
- **dangling** — comments inside an otherwise empty block, or after the last
  statement before a closing brace

```java
class A {
    // leading
    int x;
    int y; // trailing

    /* leading block */
    int z;

    // before method
    void f() {
        // inside
    }

    // dangling at end of class
}
```

Line comments keep their original text (with trailing whitespace stripped). Block
comments keep their original text and re-indent multi-line `/* ... */` blocks to the
new column.

---

## Javadoc

Both classic `/** ... */` and Markdown-style `///` Javadoc are preserved.

```java
class A {
    /**
     * Old style javadoc.
     * @param x the arg
     */
    void foo(int x) {}

    /// Markdown style javadoc.
    /// More detail here.
    void bar() {}
}
```

**Javadoc content is not reformatted.** javafmt re-indents the lines to the new
column, but does not reflow paragraphs, normalize `@param` spacing, or sort tags.

---

## Pattern matching

`instanceof` patterns and switch patterns format naturally:

```java
if (o instanceof String s && s.length() > 5) {
    System.out.println(s);
}

return switch (s) {
    case Circle(var r) -> Math.PI * r * r;
    case Square(var side) when side > 0 -> side * side;
    case Square sq -> 0;
};
```

Record patterns with many components follow the same break rule as record headers and
method parameters: flat when they fit, one-per-line when they don't.

---

## Array initializers

Short literals stay inline:

```java
int[] small = {1, 2, 3};
int[][] m = {{1, 2}, {3, 4}};
int[] arr = new int[] {10, 20, 30};
```

Literals that don't fit break vertically, **with a trailing comma**:

```java
int[] big =
    {
        111111111,
        222222222,
        333333333,
        444444444,
        555555555,
        666666666,
        777777777,
        888888888,
        999999999,
        100000000,
    };
```

The trailing comma keeps subsequent edits (adding a row) to a one-line diff.

---

## Known limitations and quirks

These are documented behaviors of the current version. Some are intentional, some are
rough edges that will improve over time. They are listed here so you know what to
expect.

- **No import sorting or grouping.** Imports stay in the order you wrote them.
- **Javadoc prose is not reformatted.** Lines are re-indented but the text inside
  `/** */` or `///` is untouched.
- **Module declarations (`module-info.java`) are not yet supported.** The formatter
  will throw on them.
- **Single-fragment field declarations** with very long RHS expressions may not
  always pick the most aesthetic break point. Extract to a local if it bothers you.

If you hit something not covered here, the formatter's behavior is the source of
truth: pipe a sample through `javafmt format -` and see what it does. If the result
surprises you, please open an issue.
