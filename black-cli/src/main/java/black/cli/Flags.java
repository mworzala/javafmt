package black.cli;

import org.jspecify.annotations.Nullable;

import java.util.*;

/// A simple, Go-flag-style command-line flag parser.
///
/// Usage:
///   Flags flags = new Flags("myapp");
///   Flags.Value<Integer> port = flags.intFlag("port", 8080, "port to listen on");
///   Flags.Value<String> host = flags.stringFlag("host", "localhost", "host to bind to");
///   Flags.Value<Boolean> verbose = flags.boolFlag("verbose", false, "enable verbose logging");
///   flags.parse(args);
///
///   System.out.println("Listening on " + host.get() + ":" + port.get());
///   if (verbose.get()) { ... }
///
/// Supports: --flag=value, --flag value, -flag=value, -flag value
/// Boolean flags can be passed as --verbose (implies true), --verbose=false, etc.
final class Flags {
    private final String programName;
    private final @Nullable String usage;
    private final Map<String, FlagEntry<?>> registered = new LinkedHashMap<>();
    private final Map<String, FlagEntry<?>> shortcuts = new HashMap<>();
    private final List<String> remaining = new ArrayList<>();

    public Flags(String programName) {
        this(programName, null);
    }

    public Flags(String programName, @Nullable String usage) {
        this.programName = programName;
        this.usage = usage;
    }

    public Value<String> stringFlag(String name, String shortName, String defaultVal, String usage) {
        return register(name, shortName, defaultVal, usage, s -> s);
    }

    public Value<String> stringFlag(String name, String defaultVal, String usage) {
        return stringFlag(name, null, defaultVal, usage);
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

    public void parse(String[] args) {
        int i = 0;
        while (i < args.length) {
            String arg = args[i];

            if (arg.equals("--")) {
                i++;
                while (i < args.length) remaining.add(args[i++]);
                return;
            }

            if (!arg.startsWith("-")) {
                remaining.add(arg);
                i++;
                continue;
            }

            String name;
            String value = null;

            if (arg.startsWith("--")) {
                // long form: --flag or --flag=value
                String raw = arg.substring(2);
                int eq = raw.indexOf('=');
                if (eq >= 0) {
                    name = raw.substring(0, eq);
                    value = raw.substring(eq + 1);
                } else {
                    name = raw;
                }
            } else {
                // short form: -v, -t5, -t=5, -t 5
                String raw = arg.substring(1);
                int eq = raw.indexOf('=');
                if (eq >= 0) {
                    name = raw.substring(0, eq);
                    value = raw.substring(eq + 1);
                } else if (raw.length() == 1) {
                    name = raw;
                } else {
                    // could be -t5 (short flag with inline value)
                    // try single char first, if it's a known short flag, treat rest as value
                    String maybe = raw.substring(0, 1);
                    if (shortcuts.containsKey(maybe)) {
                        name = maybe;
                        value = raw.substring(1);
                    } else {
                        // treat whole thing as a long-ish name like -verbose
                        name = raw;
                    }
                }
            }

            if (name.equals("help") || name.equals("h")) {
                printUsage();
                System.exit(0);
            }

            FlagEntry<?> entry = lookup(name);
            if (entry == null) {
                System.err.println("unknown flag: -" + name);
                printUsage();
                System.exit(1);
            }

            if (entry.isBool && value == null) {
                entry.setRaw("true");
            } else if (value != null) {
                entry.setRaw(value);
            } else {
                i++;
                if (i >= args.length) {
                    System.err.println("flag -" + name + " requires a value");
                    System.exit(1);
                }
                entry.setRaw(args[i]);
            }
            i++;
        }
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
            System.err.println(usage);
            return;
        }

        System.err.println("Usage of " + programName + ":");
        for (FlagEntry<?> e : registered.values()) {
            String def = e.defaultVal == null ? "" : " (default: " + e.defaultVal + ")";
            String label = e.shortName != null
                    ? "--" + e.name + ", -" + e.shortName
                    : "--" + e.name;
            System.err.printf("  %-24s %s%s%n", label, e.usage, def);
        }
    }

    private <T> Value<T> register(String name, String shortName, T defaultVal, String usage, Parser<T> parser) {
        if (registered.containsKey(name)) {
            throw new IllegalArgumentException("flag already defined: " + name);
        }
        FlagEntry<T> entry = new FlagEntry<>(name, shortName, defaultVal, usage, parser);
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
    interface Parser<T> { T parse(String s); }

    public static class Value<T> {
        T val;
        Value(T val) { this.val = val; }
        public T get() { return val; }
    }

    private static class FlagEntry<T> extends Value<T> {
        final String name;
        final String shortName;
        final T defaultVal;
        final String usage;
        final Parser<T> parser;
        final boolean isBool;

        FlagEntry(String name, String shortName, T defaultVal, String usage, Parser<T> parser) {
            super(defaultVal);
            this.name = name;
            this.shortName = shortName;
            this.defaultVal = defaultVal;
            this.usage = usage;
            this.parser = parser;
            this.isBool = defaultVal instanceof Boolean;
        }

        void setRaw(String raw) {
            try {
                this.val = parser.parse(raw);
            } catch (Exception e) {
                System.err.println("invalid value \"" + raw + "\" for flag -" + name + ": " + e.getMessage());
                System.exit(1);
            }
        }
    }
}