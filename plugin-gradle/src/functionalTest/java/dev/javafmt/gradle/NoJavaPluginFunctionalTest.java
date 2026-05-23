package dev.javafmt.gradle;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoJavaPluginFunctionalTest extends FunctionalTestSupport {

    @Test
    void buildSucceedsAndWarnsWhenJavaPluginAbsent() throws IOException {
        writeFile(settingsFile(), "");
        writeFile(buildFile(), """
                plugins {
                    id("dev.javafmt.gradle")
                }
                """);

        var result = runner("help").build();

        assertTrue(result.getOutput().contains("black: java plugin not applied"),
                "expected warning in output; got:\n" + result.getOutput());
    }

    @Test
    void formatJavaTaskIsNotRegisteredWithoutJavaPlugin() throws IOException {
        writeFile(settingsFile(), "");
        writeFile(buildFile(), """
                plugins {
                    id("dev.javafmt.gradle")
                }
                """);

        var result = runner("tasks", "--all").build();

        var output = result.getOutput();
        assertFalse(output.contains("formatJava"),
                "expected no formatJava task; got:\n" + output);
        assertFalse(output.contains("checkFormat"),
                "expected no checkFormat task; got:\n" + output);
    }
}
