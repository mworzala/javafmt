package dev.javafmt.cli;

import org.jspecify.annotations.Nullable;
import java.io.PrintStream;
import java.util.*;

/// A simple, Go-flag-style command-line flag parser.
///
/// Usage:
///   Flags flags = new Flags("myapp");
///   Flags.Value<Integer> port = flags.intFlag("port", 8080, "port to listen on");
///   Flags.Value<String> host = flags.stringFlag("host", "localhost", "host to bind to");
///   Flags.Value<Boolean> verbose = flags.boolFlag("verbose", false, "enable verbose logging");
///   var rc = flags.parse(args);
///   if (rc.isPresent()) return rc.getAsInt();
///
///   System.out.println("Listening on " + host.get() + ":" + port.get());
///   if (verbose.get()) { ... }
///
/// Supports: --flag=value, --flag value, -flag=value, -flag value
/// Boolean flags can be passed as --verbose (implies true), --verbose=false, etc.
final class Flags {
    private final String programName;
    private final @Nullable String usage;
    private final PrintStream err;
    private final Map<String, FlagEntry<?>> registered = new LinkedHashMap<>();
    private final Map<String, FlagEntry<?>> shortcuts = new HashMap<>();
    private final List<String> remaining = new ArrayList<>();

    public Flags(String programName) {
        this(programName, null, System.err);
    }

    public Flags(String programName, @Nullable String usage) {
        this(programName, usage, System.err);
    }

    public Flags(String programName, @Nullable String usage, PrintStream err) {
        this.programName = programName;
        this.usage = usage;
        this.err = err;
    }

    public Value<String> stringFlag(String name, String shortName, String defaultVal, String usage) {
        return register(name, shortName, defaultVal, usage, s -> s);
    }

    public Value<String> stringFlag(String name, String defaultVal, String usage) {
        return stringFlag(name, null, defaultVal, usage);
    }

    /// A string flag whose value is optional: `--flag` (bare) marks the flag present while
    /// keeping `defaultVal`, `--flag=value` sets it to `value`, and omitting the flag leaves it
    /// unset. Unlike a regular string flag, the bare form never consumes the following argument,
    /// so `javafmt check --only-changed src/` treats `src/` as a positional argument. Use
    /// [Value#isSet()] to tell "present (bare)" apart from "absent".
    public Value<String> optionalStringFlag(String name, String defaultVal, String usage) {
        FlagEntry<String> entry = (FlagEntry<String>) register(name, null, defaultVal, usage, s -> s);
        entry.optionalValue = true;
        return entry;
    }

    public Value<Integer> intFlag(String name, String shortName, int defaultVal, String usage) {
        return register(name, shortName, defaultVal, usage, Integer::parseInt);
    }

    public Value<Integer> intFlag(String name, int defaultVal, String usage) {
        return intFlag(name, null, defaultVal, usage);
    }

    public Value<Long> longFlag(String name, String shortName, long defaultVal, String usage) {
        return register(name, shortName, defaultVal, usage, Long::parseLong);
    }

    public Value<Long> longFlag(String name, long defaultVal, String usage) {
        return longFlag(name, null, defaultVal, usage);
    }

    public Value<Double> doubleFlag(String name, String shortName, double defaultVal, String usage) {
        return register(name, shortName, defaultVal, usage, Double::parseDouble);
    }

    public Value<Double> doubleFlag(String name, double defaultVal, String usage) {
        return doubleFlag(name, null, defaultVal, usage);
    }

    public Value<Boolean> boolFlag(String name, String shortName, boolean defaultVal, String usage) {
        return register(name, shortName, defaultVal, usage, Flags::parseBool);
    }

    public Value<Boolean> boolFlag(String name, boolean defaultVal, String usage) {
        return boolFlag(name, null, defaultVal, usage);
    }

    public OptionalInt parse(String[] args) {
        int i = 0;
        while (i < args.length) {
            String arg = args[i];

            if (arg.equals("--")) {
                i++;
                while (i < args.length) remaining.add(args[i++]);
                return OptionalInt.empty();
            }

            if (!arg.startsWith("-") || arg.equals("-")) {
                remaining.add(arg);
                i++;
                continue;
            }

            String name;
            String value = null;

            if (arg.startsWith("--")) {
                String raw = arg.substring(2);
                int eq = raw.indexOf('=');
                if (eq >= 0) {
                    name = raw.substring(0, eq);
                    value = raw.substring(eq + 1);
                } else {
                    name = raw;
                }
            } else {
                String raw = arg.substring(1);
                int eq = raw.indexOf('=');
                if (eq >= 0) {
                    name = raw.substring(0, eq);
                    value = raw.substring(eq + 1);
                } else if (raw.length() == 1) {
                    name = raw;
                } else {
                    String maybe = raw.substring(0, 1);
                    if (shortcuts.containsKey(maybe)) {
                        name = maybe;
                        value = raw.substring(1);
                    } else {
                        name = raw;
                    }
                }
            }

            if (name.equals("help") || name.equals("h")) {
                printUsage();
                return OptionalInt.of(0);
            }

            FlagEntry<?> entry = lookup(name);
            if (entry == null) {
                err.println("unknown flag: " + arg);
                printUsage();
                return OptionalInt.of(2);
            }

            if (entry.isBool && value == null) {
                if (!entry.setRaw("true")) return OptionalInt.of(2);
            } else if (value != null) {
                if (!entry.setRaw(value)) return OptionalInt.of(2);
            } else if (entry.optionalValue) {
                entry.set = true;
            } else {
                i++;
                if (i >= args.length) {
                    err.println("flag -" + name + " requires a value");
                    return OptionalInt.of(2);
                }
                if (!entry.setRaw(args[i])) return OptionalInt.of(2);
            }
            i++;
        }
        return OptionalInt.empty();
    }

    private FlagEntry<?> lookup(String name) {
        FlagEntry<?> e = registered.get(name);
        if (e != null) return e;
        return shortcuts.get(name);
    }

    public List<String> args() {
        return new ArrayList<>(remaining);
    }

    public void printUsage() {
        if (usage != null) {
            err.println(usage);
            return;
        }

        err.println("Usage of " + programName + ":");
        for (FlagEntry<?> e : registered.values()) {
            String def = e.defaultVal == null ? "" : " (default: " + e.defaultVal + ")";
            String label = e.shortName != null ? "--" + e.name + ", -" + e.shortName : "--" + e.name;
            err.printf("  %-24s %s%s%n", label, e.usage, def);
        }
    }

    private <T> Value<T> register(
        String name,
        String shortName,
        T defaultVal,
        String usage,
        Parser<T> parser
    ) {
        if (registered.containsKey(name)) {
            throw new IllegalArgumentException("flag already defined: " + name);
        }
        FlagEntry<T> entry = new FlagEntry<>(name, shortName, defaultVal, usage, parser, err);
        registered.put(name, entry);
        if (shortName != null) {
            shortcuts.put(shortName, entry);
        }
        return entry;
    }

    private static boolean parseBool(String s) {
        return switch (s.toLowerCase()) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("invalid boolean: " + s);
        };
    }

    @FunctionalInterface
    interface Parser<T> {
        T parse(String s);
    }

    public static class Value<T> {
        T val;
        boolean set;
        Value(T val) {
            this.val = val;
        }
        public T get() {
            return val;
        }
        /// Whether the flag was present on the command line (as opposed to using its default).
        public boolean isSet() {
            return set;
        }
    }

    private static class FlagEntry<T> extends Value<T> {
        final String name;
        final String shortName;
        final T defaultVal;
        final String usage;
        final Parser<T> parser;
        final PrintStream err;
        final boolean isBool;
        boolean optionalValue;

        FlagEntry(String name, String shortName, T defaultVal, String usage, Parser<T> parser, PrintStream err) {
            super(defaultVal);
            this.name = name;
            this.shortName = shortName;
            this.defaultVal = defaultVal;
            this.usage = usage;
            this.parser = parser;
            this.err = err;
            this.isBool = defaultVal instanceof Boolean;
        }

        boolean setRaw(String raw) {
            try {
                this.val = parser.parse(raw);
                this.set = true;
                return true;
            } catch (Exception e) {
                err.println(
                    "invalid value \"" + raw + "\" for flag --" + name + ": " + e.getMessage()
                );
                return false;
            }
        }
    }
}
