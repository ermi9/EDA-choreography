package com.eda.choreography.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eda.choreography.infra.AbstractInfraIT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The {@code fork-skip} demo flow from {@code deploy/flows}, stored and run through the API by
 * the demo services this engine hosts, as the editor's scenarios run it. The services carry a
 * prefix so they share the broker with other tests without sharing topics.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "choreography.host.services="
                + "skip-assess,skip-ship,skip-pay,skip-charge,skip-notify,skip-confirm")
class ForkSkipDemoIT extends AbstractInfraIT {

    private static final List<String> SERVICES = List.of("assess", "ship", "pay", "charge", "notify", "confirm");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private String flow;

    @BeforeEach
    void storeTheDemoFlow() throws Exception {
        registerWithDiscovery(SERVICES.stream().map(service -> "skip-" + service).toArray(String[]::new));
        var drawn = Files.readString(Path.of("deploy/flows/fork-skip.bpmn"));
        for (var service : SERVICES) {
            drawn = drawn.replace("choreo:service=\"" + service + "\"", "choreo:service=\"skip-" + service + "\"");
        }
        flow = "fork-skip-" + UUID.randomUUID().toString().substring(0, 8);
        var stored = send("PUT", "/api/flows/" + flow, drawn, "application/xml");
        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(json(stored).get("lateBound").values()).isEmpty();
    }

    @Test
    void withoutAFailureBothBranchesRunAndTheFlowCompletes() throws Exception {
        var run = runAndAwait("{\"amount\": 20}");

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(steps(run)).containsExactlyInAnyOrder(
                "skip-assess", "skip-ship", "skip-pay", "skip-charge", "skip-notify", "skip-confirm");
    }

    @Test
    void whenShipGivesUpWhilePayIsWorkingChargeIsSkippedAndNotifyIsNeverReached() throws Exception {
        // Every step takes 3 s; ship fails at once and gives up after its two retries, while pay
        // is still working, so the fork is marked failed before charge would start.
        var run = runAndAwait("{\"amount\": 20, \"failAt\": \"skip-ship\", \"delayMs\": 3000}");

        assertThat(run.get("status").asString()).isEqualTo("COMPENSATED");
        assertThat(outcomeOf(run, "skip-ship")).isEqualTo("FAILED");
        assertThat(outcomeOf(run, "skip-pay")).isEqualTo("COMPLETED");
        assertThat(outcomeOf(run, "skip-charge")).isEqualTo("SKIPPED");
        assertThat(steps(run)).doesNotContain("skip-notify", "skip-confirm");
    }

    @Test
    void whenShipGivesUpAfterTheLongBranchFinishedNothingIsLeftToSkip() throws Exception {
        // No delay: pay, charge and notify are done long before ship's retries run out.
        var run = runAndAwait("{\"amount\": 20, \"failAt\": \"skip-ship\"}");

        assertThat(run.get("status").asString()).isEqualTo("COMPENSATED");
        assertThat(outcomeOf(run, "skip-ship")).isEqualTo("FAILED");
        for (var step : List.of("skip-pay", "skip-charge", "skip-notify")) {
            assertThat(outcomeOf(run, step)).as(step).isEqualTo("COMPLETED");
        }
    }

    private static List<String> steps(JsonNode run) {
        return run.get("trace").values().stream().map(entry -> entry.get("step").asString()).toList();
    }

    private static String outcomeOf(JsonNode run, String step) {
        return run.get("trace").values().stream()
                .filter(entry -> entry.get("step").asString().equals(step))
                .map(entry -> entry.get("outcome").asString())
                .findFirst()
                .orElseThrow(() -> new AssertionError(step + " is not in the trace"));
    }

    private JsonNode runAndAwait(String input) throws Exception {
        var started = send("POST", "/api/flows/" + flow + "/runs", input, "application/json");
        assertThat(started.statusCode()).isEqualTo(202);
        var correlationId = json(started).get("correlationId").asString();
        var outcome = new JsonNode[1];
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> {
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
}
