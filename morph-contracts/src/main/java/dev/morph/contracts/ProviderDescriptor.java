package dev.morph.contracts;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Identity and bill of materials of a provider; feeds {@code morph doctor} and the dependency audit. */
public record ProviderDescriptor(String id, String displayName, List<Dependency> dependencies) {

    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9-]*");

    public ProviderDescriptor {
        Objects.requireNonNull(displayName, "displayName");
        dependencies = List.copyOf(dependencies);
        if (!VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid provider id");
        }
    }

    public record Dependency(String name, Kind kind, String license, String requirement) {
        public enum Kind {
            /** Pure Java code running inside the Morph process. */
            JAVA_LIBRARY,
            /** Native code loaded into the Morph process (JNI). */
            NATIVE_LIBRARY,
            /** Separate program executed by Morph; must already be installed. */
            EXTERNAL_TOOL
        }

        public Dependency {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(license, "license");
            Objects.requireNonNull(requirement, "requirement");
        }
    }
}
