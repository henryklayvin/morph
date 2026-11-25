package dev.morph.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morph.application.MorphRuntime;
import dev.morph.contracts.ResourceLimits;
import dev.morph.engine.EngineConfig;
import dev.morph.local.ApiViews.CapabilitiesView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the JSON contract with the interface. The same file feeds the TypeScript tests, so a change on one side fails
 * on the other. To accept an intended change: {@code ./mvnw test -pl morph-local-web -am -Dmorph.updateContract}.
 */
class ApiContractTest {

    private static final Path CONTRACT = Path.of("..", "web", "contract", "capabilities.json");

    @TempDir
    Path temp;

    @Test
    void capabilitiesMatchTheContractSharedWithTheInterface() throws Exception {
        JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        String actual;
        try (MorphRuntime runtime = MorphRuntime.start(new EngineConfig(temp, 1, Duration.ofSeconds(1),
                ResourceLimits.defaults()))) {
            actual = json.writeValueAsString(CapabilitiesView.of(runtime.engine())).replace("\r\n", "\n") + "\n";
        }
        if (System.getProperty("morph.updateContract") != null) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, actual);
        }
        assertEquals(Files.readString(CONTRACT).replace("\r\n", "\n"), actual);
    }
}
