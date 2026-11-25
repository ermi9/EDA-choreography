package com.eda.choreography.infra.participant;

import com.eda.choreography.domain.compensation.CompensationAction;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.StepAction;
import com.eda.choreography.domain.step.StepRejectedException;
import com.eda.choreography.domain.step.UncertainOutcomeException;
import com.eda.choreography.domain.trace.TraceEntry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls one participant's step and undo operations. The address is looked up on every call, so a
 * service that registers again elsewhere is called there.
 *
 * <p>Every call names the run it belongs to in {@code Idempotency-Key}. A step keeps its key
 * across retries and redeliveries, and its undo carries the same key, so a service that remembers
 * the keys it has seen does each run's work once.
 *
 * <p>What an answer to a step means:
 * <ul>
 *   <li>2xx: done, and the body is the result;</li>
 *   <li>422: refused for good, nothing was committed;</li>
 *   <li>409: this run was already undone, so it must not be done (an attempt that comes late);</li>
 *   <li>anything else, or no answer: unknown. The work may have happened.</li>
 * </ul>
 */
final class ParticipantClient implements StepAction, CompensationAction {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final int REFUSED = 422;
    private static final int ALREADY_UNDONE = 409;
    private static final int QUOTED_CHARS = 300;

    private final String service;
    private final Function<String, Optional<String>> urls;
    private final Duration timeout;
    private final HttpClient http;

    ParticipantClient(String service, Function<String, Optional<String>> urls, Duration timeout, HttpClient http) {
        this.service = Objects.requireNonNull(service, "service");
        this.urls = Objects.requireNonNull(urls, "urls");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public Map<String, ?> execute(ChoreographyMessage message) {
        var step = message.step() != null ? message.step() : service;
        var body = bodyOf(message, step);
        body.put("visit", message.runsOf(step) + 1);
        body.put("results", resultsByStep(message));
        HttpResponse<String> response;
        try {
            response = post(ParticipantContract.STEP_PATH, step, message.entryIdFor(step), body);
        } catch (IOException e) {
            throw new UncertainOutcomeException("no answer from " + service + " for step " + step + ": " + e, e);
        }
        if (response.statusCode() == REFUSED || response.statusCode() == ALREADY_UNDONE) {
            throw new StepRejectedException(service + " refused step " + step + ": " + quote(response));
        }
        if (response.statusCode() / 100 != 2) {
            throw new UncertainOutcomeException(service + " answered step " + step + " with " + quote(response));
        }
        if (response.body().isBlank()) {
            return Map.of();
        }
        try {
            var result = JSON.readValue(response.body(), OBJECT);
            return result == null ? Map.of() : result;
        } catch (JacksonException e) {
            throw new UncertainOutcomeException(service + " did step " + step + " but its result is not a JSON object", e);
        }
    }

    @Override
    public void undo(TraceEntry entry, ChoreographyMessage instance) {
        var body = bodyOf(instance, entry.stepId());
        // Absent when the step's answer was never seen.
        body.put("result", instance.results().get(entry.resultRef()));
        HttpResponse<String> response;
        try {
            response = post(ParticipantContract.UNDO_PATH, entry.stepId(), entry.resultRef(), body);
        } catch (IOException e) {
            throw new IllegalStateException("no answer from " + service + " for undoing " + entry.stepId() + ": " + e, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(service + " answered undoing " + entry.stepId() + " with " + quote(response));
        }
    }

    private static Map<String, Object> bodyOf(ChoreographyMessage message, String step) {
        var body = new LinkedHashMap<String, Object>();
        body.put("correlationId", message.correlationId());
        body.put("flowName", message.flowName());
        body.put("step", step);
        body.put("input", message.input());
        return body;
    }

    /** What earlier steps answered, by step; a step that ran several times gives its latest answer. */
    private static Map<String, Object> resultsByStep(ChoreographyMessage message) {
        var byStep = new LinkedHashMap<String, Object>();
        message.trace().stream()
                .filter(entry -> entry.outcome() == TraceEntry.Outcome.COMPLETED)
                .filter(entry -> message.results().containsKey(entry.resultRef()))
                .forEach(entry -> byStep.put(entry.stepId(), message.results().get(entry.resultRef())));
        return byStep;
    }

    private HttpResponse<String> post(String path, String step, String key, Map<String, Object> body) throws IOException {
        var base = urls.apply(service).orElseThrow(() -> new IOException("discovery knows no address of " + service));
        URI uri;
        try {
            uri = URI.create(base.replaceAll("/+$", "") + path + encoded(step));
        } catch (IllegalArgumentException e) {
            throw new IOException("the registered address is not a URL: " + base, e);
        }
        try {
            var request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", headerSafe(key))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IllegalArgumentException e) {
            throw new IOException("the registered address cannot be called: " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + service, e);
        }
    }

    private static String encoded(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A header takes plain ASCII, and a step drawn with another name would not fit. */
    private static String headerSafe(String key) {
        return key.chars().allMatch(c -> c > ' ' && c < 127) ? key : encoded(key);
    }

    private static String quote(HttpResponse<String> response) {
        var body = response.body();
        return response.statusCode() + " " + (body.length() > QUOTED_CHARS ? body.substring(0, QUOTED_CHARS) + "…" : body);
    }
}
