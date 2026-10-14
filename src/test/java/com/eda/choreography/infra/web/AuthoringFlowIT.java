package com.eda.choreography.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eda.choreography.infra.AbstractInfraIT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authoring pipeline end to end: a drawn flow is stored through the API, started through
 * the API, and run by services this engine hosts, each on its own topic, choreographing the
 * instance over the real broker and Redis. The outcome is read back through the API.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "choreography.host.services="
                + "auth-assess,auth-review,auth-reserve,auth-pay,auth-ship,auth-confirm")
class AuthoringFlowIT extends AbstractInfraIT {

    private static final List<String> SERVICES = List.of("assess", "review", "reserve", "pay", "ship", "confirm");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private String flow;

    @BeforeEach
    void storeTheDrawnFlow() throws Exception {
        registerWithDiscovery(SERVICES.stream().map(service -> "auth-" + service).toArray(String[]::new));
        // The demo services answer with the request, so the choice tests the order's amount.
        var drawn = fixture("order").replace("#result['risk'] == 'HIGH'", "#result['amount'] &gt; 1000");
        for (var service : SERVICES) {
            drawn = drawn.replace("name=\"" + service + "\"", "name=\"auth-" + service + "\"");
        }
        flow = "order-" + UUID.randomUUID().toString().substring(0, 8);
        var stored = send("PUT", "/api/flows/" + flow, drawn, "application/xml");
        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(json(stored).get("lateBound").values()).isEmpty();
    }

    @Test
    void aStoredFlowStartedThroughTheApiRunsToCompletionAcrossTheHostedServices() throws Exception {
        var run = runAndAwait("{\"amount\": 5000}");

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("trace").values()).extracting(entry -> entry.get("step").asString())
                .containsExactlyInAnyOrder("auth-assess", "auth-review", "auth-reserve", "auth-pay", "auth-ship", "auth-confirm");
    }

    @Test
    void aRunWhoseBranchFailsIsUndoneAndReportedAsCompensated() throws Exception {
        var run = runAndAwait("{\"amount\": 20, \"failAt\": \"auth-ship\"}");

        assertThat(run.get("status").asString()).isEqualTo("COMPENSATED");
        assertThat(run.get("trace").values())
                .filteredOn(entry -> entry.get("step").asString().equals("auth-ship"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.get("outcome").asString()).isEqualTo("FAILED"));
    }

    private JsonNode runAndAwait(String input) throws Exception {
        var started = send("POST", "/api/flows/" + flow + "/runs", input, "application/json");
        assertThat(started.statusCode()).isEqualTo(202);
        var correlationId = json(started).get("correlationId").asString();
        var outcome = new JsonNode[1];
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).until(() -> {
            outcome[0] = json(send("GET", "/api/runs/" + correlationId, null, "application/json"));
            return !outcome[0].get("status").asString().equals("RUNNING");
        });
        return outcome[0];
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String fixture(String name) throws IOException {
        try (var in = AuthoringFlowIT.class.getResourceAsStream("/bpmn/" + name + ".bpmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
