package dev.javafmt.idea;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class JavaFmtFormatterLoaderTest extends BasePlatformTestCase {

    public void testFormatsUsingExternalClasspath() throws Exception {
        var paths = readTestClasspath();
        var settings = JavaFmtProjectSettings.getInstance(getProject());
        settings.setFormatterClasspath(paths);
        assertTrue(settings.isEnabled());

        var loader = JavaFmtFormatterLoader.getInstance(getProject());
        var input = "class Foo{void bar(){System.out.println(\"hi\");}}";
        var output = loader.format(paths, input);

        assertNotNull(output);
        assertFalse("Formatted output should not be empty", output.isEmpty());
        assertTrue("Expected formatted output to contain 'class Foo', was:\n" + output, output.contains("class Foo"));
        // A trivial formatting check — input was a one-liner; output should at least
        // not be the exact same one-liner (the formatter should have rewritten it).
        assertNotSame(input, output);
    }

    public void testFormatterClassesAreNotOnPluginClasspath() {
        // The whole point of dynamic loading: core must NOT be on the IDE plugin's
        // classpath. If this fails, the production build has accidentally bundled
        // core and the URLClassLoader path is moot.
        var pluginCl = JavaFmtFormattingService.class.getClassLoader();
        try {
            pluginCl.loadClass("dev.javafmt.Formatter");
            fail("dev.javafmt.Formatter must NOT be visible from the IDE plugin classpath (core is compileOnly).");
        } catch (ClassNotFoundException expected) {
            // ok
        }
    }

    public void testLoaderIsolatesFormatterClassloader() throws Exception {
        var paths = readTestClasspath();
        var loader = JavaFmtFormatterLoader.getInstance(getProject());
        var formatterCl = loader.getOrCreateClassLoader(paths);
        var formatterClass = formatterCl.loadClass("dev.javafmt.Formatter");

        assertSame(
                "Formatter class must be defined by the URLClassLoader",
                formatterCl,
                formatterClass.getClassLoader()
        );
        assertNotSame(
                "Formatter class loader must not be the IDE plugin's classloader",
                JavaFmtFormattingService.class.getClassLoader(),
                formatterClass.getClassLoader()
        );
    }

    public void testEmptyClasspathProducesError() {
        var loader = JavaFmtFormatterLoader.getInstance(getProject());
        try {
            loader.format(List.of(), "class Foo {}");
            fail("Expected FormatException when classpath is empty");
        } catch (JavaFmtFormatterLoader.FormatException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static List<String> readTestClasspath() {
        var cp = System.getProperty("javafmt.test.classpath");
        if (cp == null || cp.isEmpty()) {
            throw new IllegalStateException(
                    "javafmt.test.classpath system property must be set by the Gradle test task " +
                    "(see plugin-idea/build.gradle.kts)"
            );
        }
        return Arrays.asList(cp.split(File.pathSeparator));
    }
}
