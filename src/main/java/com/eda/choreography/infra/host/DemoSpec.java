package com.eda.choreography.infra.host;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * DEMO: the OpenAPI document a hosted demo service serves, so the editor can show what it does.
 *
 * <p>A hosted service takes its work from Kafka, not over HTTP, so its only HTTP operation is the
 * health check discovery probes. What a flow designer needs is in {@code x-choreography}: the
 * topic the step is read from, the topic its undo requests are read from, the input fields that
 * steer {@link DemoAction}, and the fields of its result that conditions can test.
 */
public final class DemoSpec {

    private DemoSpec() {
    }

    public static Map<String, Object> of(String service) {
        var spec = new LinkedHashMap<String, Object>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of(
                "title", service,
                "version", "demo",
                "description", "A demo service hosted by the choreography engine. It takes its steps "
                        + "and undo requests from Kafka, not over HTTP; x-choreography says where, and "
                        + "what its result holds."));
        spec.put("paths", Map.of("/services/" + service + "/health", Map.of("get", Map.of(
                "summary", "Up while this engine hosts the service; discovery probes it",
                "responses", Map.of("200", Map.of("description", "the service is up"))))));
        spec.put("components", Map.of("schemas", Map.of(
                "StepInput", schema("The instance's input. These fields steer the demo step; any other field is passed on.",
                        Map.of(
                                "failAt", field("string", "a step or service that throws, to watch an instance being undone"),
                                "failAtVisit", field("integer", "with failAt, throw only on this visit of the step"),
                                "delayMs", field("integer", "a pause before the step finishes, up to 5000"))),
                "StepResult", schema("The instance's input, without the steering fields, plus these. "
                                + "A condition after this step reads it as #result.",
                        Map.of(
                                "service", field("string", "the service that ran the step"),
                                "step", field("string", "the step that ran"),
                                "visits", field("integer", "how many times this step has run in the instance, counting this one"))))));
        spec.put("x-choreography", Map.of(
                "step", Map.of(
                        "topic", service + ".in",
                        "input", Map.of("$ref", "#/components/schemas/StepInput"),
                        "result", Map.of("$ref", "#/components/schemas/StepResult")),
                "undo", Map.of(
                        "topic", service + ".compensate",
                        "description", "Undoes the step it is sent for. The demo only logs it.")));
        return spec;
    }

    private static Map<String, Object> schema(String description, Map<String, Object> properties) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("properties", new TreeMap<>(properties));
        schema.put("additionalProperties", true);
        return schema;
    }

    private static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
