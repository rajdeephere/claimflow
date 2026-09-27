package com.claimflow.common.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps each service's published API contract (docs/openapi/&lt;service&gt;.v1.json) in sync with the code
 * (ADR-0030). Shared by the services through common's test-jar.
 *
 * <ul>
 *   <li>normal build: the live /v3/api-docs/v1 must equal the committed file, otherwise the test
 *       fails, so an API change can't slip through unnoticed</li>
 *   <li>{@code mvn verify -Dopenapi.update=true}: rewrite the file; the change then shows up as a diff
 *       in code review, where "is this breaking?" gets decided</li>
 * </ul>
 */
public final class OpenApiContract {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);   // stable key order = stable diffs

    private OpenApiContract() {
    }

    public static void assertMatchesCommittedContract(String liveJson, String serviceName) throws IOException {
        String live = canonical(liveJson);
        // failsafe runs with the module directory as working directory
        Path file = Path.of("..", "docs", "openapi", serviceName + ".v1.json");

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, live, StandardCharsets.UTF_8);
            return;
        }
        assertThat(file)
                .as("No committed API contract for %s. Generate it with: mvn verify -Dopenapi.update=true", serviceName)
                .exists();
        String committed = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(live)
                .as("The %s API differs from %s. If the change is intended, regenerate with "
                        + "'mvn verify -Dopenapi.update=true' and review the diff: removed or renamed fields, "
                        + "new required fields or changed types are BREAKING and need /api/v2 (ADR-0030).",
                        serviceName, file.normalize())
                .isEqualTo(committed);
    }

    private static String canonical(String json) throws IOException {
        Object tree = CANONICAL.readValue(json, Object.class);   // Maps, so ORDER_MAP_ENTRIES_BY_KEYS applies
        return CANONICAL.writeValueAsString(tree).replace("\r\n", "\n") + "\n";
    }
}
