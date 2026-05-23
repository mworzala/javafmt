package dev.javafmt.idea;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.javafmt.api.Formatter;

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
        assertNotSame(input, output);
    }

    public void testImplClassIsNotOnPluginClasspath() {
        // The whole point of dynamic loading: the impl (`dev.javafmt.FormatterImpl`)
        // must NOT be on the IDE plugin's classpath. If this fails, the production
        // build has accidentally bundled core and the URLClassLoader path is moot.
        var pluginCl = JavaFmtFormattingService.class.getClassLoader();
        try {
            pluginCl.loadClass("dev.javafmt.FormatterImpl");
            fail("dev.javafmt.FormatterImpl must NOT be visible from the IDE plugin classpath (core is not bundled).");
        } catch (ClassNotFoundException expected) {
            // ok
        }
    }

    public void testApiClassIsOnPluginClasspath() throws Exception {
        // Inverse of the above: `dev.javafmt.api.Formatter` IS bundled with the
        // plugin (via implementation(:api)). It must resolve from the plugin's
        // own classloader — otherwise the BridgeAwareClassLoader override has
        // nothing to delegate to.
        var pluginCl = JavaFmtFormattingService.class.getClassLoader();
        var formatterApi = pluginCl.loadClass("dev.javafmt.api.Formatter");
        assertNotNull(formatterApi);
    }

    public void testLoaderIsolatesFormatterClassloader() throws Exception {
        var paths = readTestClasspath();
        var loader = JavaFmtFormatterLoader.getInstance(getProject());
        var formatterCl = loader.getOrCreateClassLoader(paths);
        var formatterImpl = formatterCl.loadClass("dev.javafmt.FormatterImpl");

        assertSame(
                "FormatterImpl must be defined by the URLClassLoader",
                formatterCl,
                formatterImpl.getClassLoader()
        );
        assertNotSame(
                "FormatterImpl loader must not be the IDE plugin's classloader",
                JavaFmtFormattingService.class.getClassLoader(),
                formatterImpl.getClassLoader()
        );
    }

    public void testBridgeApiTypesUnifyAcrossClassloaders() throws Exception {
        // The structural guarantee: the api types seen by the URLClassLoader
        // are the SAME Class objects as the api types seen by the plugin.
        // Without that, instanceof/cast at the api boundary would fail.
        var paths = readTestClasspath();
        var loader = JavaFmtFormatterLoader.getInstance(getProject());
        var formatterCl = loader.getOrCreateClassLoader(paths);

        var apiFromPlugin = JavaFmtFormattingService.class.getClassLoader()
                .loadClass("dev.javafmt.api.Formatter");
        var apiFromBridgeLoader = formatterCl.loadClass("dev.javafmt.api.Formatter");

        assertSame(
                "dev.javafmt.api.Formatter must be the same Class object on both sides",
                apiFromPlugin,
                apiFromBridgeLoader
        );

        // End-to-end: the formatter returned via the dynamic load path is castable
        // to the plugin's Formatter interface.
        var result = loader.format(paths, "class A {}");
        assertNotNull(result);
        // If the classloader override is wrong, the loader's internal cast already
        // threw before we got here — so reaching this point is the meaningful signal.
        // For added evidence, verify the cached formatter satisfies instanceof.
        var formatter = (Formatter) formatterApiInstance(loader);
        assertNotNull(formatter);
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

    /// Pull the cached formatter via reflection (test-only) to assert its type cleanly
    /// without exposing a public accessor purely for the test.
    private static Object formatterApiInstance(JavaFmtFormatterLoader loader) throws Exception {
        var f = JavaFmtFormatterLoader.class.getDeclaredField("cachedFormatter");
        f.setAccessible(true);
        return f.get(loader);
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
