package com.eda.choreography.infra.web;

import com.eda.choreography.domain.resolution.ServiceTable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches a service's OpenAPI spec for the editor, which cannot reach the services itself: their
 * addresses are compose or cluster hostnames, and a browser would be stopped by CORS anyway.
 *
 * <p>The only address it ever fetches is the one the service registered with discovery, read
 * from the {@link ServiceTable}. Nothing in a request names a URL, so the editor's API cannot be
 * used to make the engine fetch arbitrary addresses. Only http and https addresses are fetched.
 *
 * <p>A fetch is bounded: it gives up after {@code timeout} and refuses a body over
 * {@code maxBytes}. A spec that was found is kept for {@code keepFor}, per service and address,
 * so a service that registers again with another address is fetched afresh.
 */
public class ServiceSpecs {

    /** What a fetch found. */
    public sealed interface Spec {
        /** The service registered no spec. */
        record None() implements Spec {}

        /** The service named a spec that could not be fetched, and why. */
        record Unreachable(String reason) implements Spec {}

        /** The spec as the service served it. */
        record Found(String body, String contentType) implements Spec {}
    }

    private record Kept(String url, Spec.Found spec, Instant until) {}

    private final ServiceTable table;
    private final Duration timeout;
    private final int maxBytes;
    private final Duration keepFor;
    private final Clock clock;
    private final HttpClient http;
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    public ServiceSpecs(ServiceTable table, Duration timeout, int maxBytes, Duration keepFor, Clock clock) {
        this.table = Objects.requireNonNull(table, "table");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxBytes = maxBytes;
        this.keepFor = Objects.requireNonNull(keepFor, "keepFor");
        this.clock = Objects.requireNonNull(clock, "clock");
        // Plain HTTP/1.1: not every server in every language copes with an offer to upgrade.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Whether the service registered a spec, without fetching it. */
    public boolean has(String service) {
        return table.openapiUrl(service).isPresent();
    }

    public Spec fetch(String service) {
        var url = table.openapiUrl(service).orElse(null);
        if (url == null) {
            return new Spec.None();
        }
        var known = kept.get(service);
        if (known != null && known.url().equals(url) && clock.instant().isBefore(known.until())) {
            return known.spec();
        }
        var spec = get(url);
        if (spec instanceof Spec.Found found) {
            kept.put(service, new Kept(url, found, clock.instant().plus(keepFor)));
        }
        return spec;
    }

    private Spec get(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new Spec.Unreachable("the registered spec address is not a URL: " + url);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return new Spec.Unreachable("only http and https spec addresses are fetched: " + url);
        }
        var request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    return new Spec.Unreachable("the service answered " + response.statusCode());
                }
                var bytes = readAtMost(body);
                if (bytes == null) {
                    return new Spec.Unreachable("the spec is larger than " + maxBytes + " bytes");
                }
                var contentType = response.headers().firstValue("Content-Type").orElse("application/json");
                return new Spec.Found(new String(bytes, StandardCharsets.UTF_8), contentType);
            }
        } catch (IOException e) {
            return new Spec.Unreachable("could not reach " + url + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Spec.Unreachable("interrupted while fetching " + url);
        }
    }

    /** The whole body, or {@code null} when it is longer than allowed. */
    private byte[] readAtMost(InputStream body) throws IOException {
        var bytes = body.readNBytes(maxBytes + 1);
        return bytes.length > maxBytes ? null : bytes;
    }
}
