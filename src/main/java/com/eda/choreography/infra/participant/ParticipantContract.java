package com.eda.choreography.infra.participant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether a service's OpenAPI document declares the participant contract: a POST that does a
 * step and a POST that undoes one. The name of the path parameter is the service's choice.
 */
final class ParticipantContract {

    static final String STEP_PATH = "/choreography/steps/";
    static final String UNDO_PATH = "/choreography/undo/";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Pattern> REQUIRED = Map.of(
            "POST /choreography/steps/{step}", Pattern.compile("/choreography/steps/\\{[^/}]+}"),
            "POST /choreography/undo/{step}", Pattern.compile("/choreography/undo/\\{[^/}]+}"));

    private ParticipantContract() {
    }

    /** The operations the document lacks; empty when it declares the whole contract. */
    static List<String> missingFrom(String openapi) {
        JsonNode paths;
        try {
            paths = JSON.readTree(openapi).path("paths");
        } catch (JacksonException e) {
            return List.of("an OpenAPI document in JSON");
        }
        var missing = new ArrayList<String>();
        REQUIRED.forEach((operation, path) -> {
            if (paths.propertyStream().noneMatch(declared ->
                    path.matcher(declared.getKey()).matches() && declared.getValue().has("post"))) {
                missing.add(operation);
            }
        });
        missing.sort(null);
        return missing;
    }
}
