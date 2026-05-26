package dev.javafmt;

import dev.javafmt.api.Formatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FormatterReleaseTest {

    @Test
    void release25AcceptsSwitchArrow() {
        var fmt = Formatter.create(Formatter.Config.defaults().withRelease(25));
        var result = fmt.format("""
                class M {
                    int x = switch (1) { case 1 -> 1; default -> 0; };
                }
                """);
        assertInstanceOf(Formatter.Result.Success.class, result);
    }

    @Test
    void release25AcceptsTypePatterns() {
        var fmt = Formatter.create(Formatter.Config.defaults().withRelease(25));
        var result = fmt.format("""
                class M {
                    static String describe(Object o) {
                        return switch (o) {
                            case Integer i -> "int " + i;
                            case String s -> "str " + s;
                            default -> "?";
                        };
                    }
                }
                """);
        assertInstanceOf(Formatter.Result.Success.class, result);
    }

    @Test
    void parserStateSurvivesMultipleFormatCalls() {
        // JDT's ASTParser.createAST() resets compilerOptions to JavaCore defaults (1.8)
        // after each parse, so a naively cached parser only respects the configured
        // release on the first call. Format twice to guard the re-apply path.
        var fmt = Formatter.create(Formatter.Config.defaults().withRelease(25));
        var src = """
                class M {
                    int x = switch (1) { case 1 -> 1; default -> 0; };
                }
                """;
        assertInstanceOf(Formatter.Result.Success.class, fmt.format(src));
        assertInstanceOf(Formatter.Result.Success.class, fmt.format(src));
    }

    @Test
    void release8RejectsRecord() {
        var fmt = Formatter.create(Formatter.Config.defaults().withRelease(8));
        var result = fmt.format("record R(int x) {}\n");
        assertInstanceOf(Formatter.Result.SyntaxError.class, result);
    }
}
