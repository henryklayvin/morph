package io.github.henryklayvin.morph.contracts;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declares one setting of a conversion. Specs are data: the CLI uses them for help and validation, the Web UI to
 * render controls, and providers as typed keys into {@link ResolvedOptions}.
 */
public sealed interface OptionSpec<T> {

    String key();

    String description();

    T defaultValue();

    /** Parses raw user input, throwing {@link ErrorCode#INVALID_OPTION} when it is not acceptable. */
    T parse(String raw) throws MorphException;

    record IntegerOption(String key, String description, Integer defaultValue, int min, int max)
            implements OptionSpec<Integer> {
        public IntegerOption {
            requireKey(key);
            Objects.requireNonNull(description, "description");
            if (min > max || defaultValue < min || defaultValue > max) {
                throw new IllegalArgumentException("Inconsistent range");
            }
        }

        @Override
        public Integer parse(String raw) throws MorphException {
            try {
                int value = Integer.parseInt(raw.strip());
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // reported below with the accepted range
            }
            throw new MorphException(ErrorCode.INVALID_OPTION,
                    "A opção '%s' aceita números inteiros de %d a %d.".formatted(key, min, max));
        }
    }

    record BooleanOption(String key, String description, Boolean defaultValue) implements OptionSpec<Boolean> {
        public BooleanOption {
            requireKey(key);
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(defaultValue, "defaultValue");
        }

        @Override
        public Boolean parse(String raw) throws MorphException {
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> throw new MorphException(ErrorCode.INVALID_OPTION,
                        "A opção '%s' aceita apenas true ou false.".formatted(key));
            };
        }
    }

    record ChoiceOption(String key, String description, String defaultValue, List<String> choices)
            implements OptionSpec<String> {
        public ChoiceOption {
            requireKey(key);
            Objects.requireNonNull(description, "description");
            choices = List.copyOf(choices);
            if (!choices.contains(defaultValue)) {
                throw new IllegalArgumentException("The default must be one of the choices");
            }
        }

        @Override
        public String parse(String raw) throws MorphException {
            String value = raw.strip().toLowerCase(Locale.ROOT);
            if (choices.contains(value)) {
                return value;
            }
            throw new MorphException(ErrorCode.INVALID_OPTION,
                    "A opção '%s' aceita: %s.".formatted(key, String.join(", ", choices)));
        }
    }

    /** Opaque sRGB colour written as {@code #rrggbb}. */
    record ColorOption(String key, String description, String defaultValue) implements OptionSpec<String> {
        private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-f]{6}");

        public ColorOption {
            requireKey(key);
            Objects.requireNonNull(description, "description");
            if (!HEX_COLOR.matcher(defaultValue).matches()) {
                throw new IllegalArgumentException("Invalid default colour");
            }
        }

        @Override
        public String parse(String raw) throws MorphException {
            String value = raw.strip().toLowerCase(Locale.ROOT);
            if (HEX_COLOR.matcher(value).matches()) {
                return value;
            }
            throw new MorphException(ErrorCode.INVALID_OPTION,
                    "A opção '%s' aceita uma cor no formato #rrggbb.".formatted(key));
        }
    }

    private static void requireKey(String key) {
        if (!key.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid option key");
        }
    }
}
