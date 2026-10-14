package com.eda.choreography.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.infra.AbstractInfraIT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The authoring API over real HTTP, with the flow store in the real Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthoringApiIT extends AbstractInfraIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aFlowThatCanRunIsStoredAndReportsItsLateBoundServices() throws Exception {
        var assess = "assess-" + UUID.randomUUID().toString().substring(0, 8);
        registerWithDiscovery(assess);
        var name = "loan-" + UUID.randomUUID().toString().substring(0, 8);

        var stored = send("PUT", "/api/flows/" + name, fixture("loan").replace("name=\"assess\"", "name=\"" + assess + "\""));

        assertThat(stored.statusCode()).isEqualTo(200);
        var body = json(stored);
        assertThat(body.get("start").asString()).isEqualTo(assess);
        assertThat(body.get("bound").values()).extracting(JsonNode::asString).containsExactly(assess);
        assertThat(body.get("lateBound").values()).extracting(JsonNode::asString).containsExactlyInAnyOrder("review", "approve");
        assertThat(json(send("GET", "/api/flows", null)).values()).extracting(JsonNode::asString).contains(name);
        assertThat(send("GET", "/api/flows/" + name, null).body()).contains("Task_assess");
    }

    @Test
    void aFlowThatCannotRunIsRejectedWithTheReason() throws Exception {
        var rejected = send("PUT", "/api/flows/broken", "<not-bpmn/>");

        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(json(rejected).get("error").asString()).contains("not valid BPMN");
    }

    @Test
    void theCatalogListsWhatDiscoveryAnnouncedAndAnUnknownFlowCannotRun() throws Exception {
        var payment = "payment-" + UUID.randomUUID().toString().substring(0, 8);
        registerWithDiscovery(payment);

        var catalog = json(send("GET", "/api/catalog", null));
        assertThat(catalog.values()).anySatisfy(service -> {
            assertThat(service.get("name").asString()).isEqualTo(payment);
            assertThat(service.get("live").asBoolean()).isTrue();
        });
        assertThat(send("POST", "/api/flows/nothing-here/runs", "{}").statusCode()).isEqualTo(404);
        assertThat(json(send("GET", "/api/runs/nobody", null)).get("status").asString()).isEqualTo("RUNNING");
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", path.endsWith("/runs") ? "application/json" : "application/xml")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String fixture(String name) throws IOException {
        try (var in = AuthoringApiIT.class.getResourceAsStream("/bpmn/" + name + ".bpmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
