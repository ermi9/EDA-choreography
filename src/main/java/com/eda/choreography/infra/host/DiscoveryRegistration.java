package com.eda.choreography.infra.host;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers hosted services with the discovery platform through its public registration path,
 * {@code POST /register {name, url, openapiUrl}}, as any service does on startup. Each service's
 * url is {@code <public url>/services/<name>}, so discovery probes {@code .../health} there.
 *
 * <p>Discovery may come up after this process, so registration is retried every few seconds in
 * the background until it succeeds. Without a discovery url nothing is registered: tests and
 * local runs announce services themselves.
 */
public class DiscoveryRegistration {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryRegistration.class);
    private static final Duration RETRY_EVERY = Duration.ofSeconds(5);

    private final String discoveryUrl;
    private final String publicUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * @param discoveryUrl where discovery listens, or blank to register nothing
     * @param publicUrl    where discovery reaches this process
     */
    public DiscoveryRegistration(String discoveryUrl, String publicUrl) {
        this.discoveryUrl = discoveryUrl == null ? "" : discoveryUrl.replaceAll("/+$", "");
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    public void registerInBackground(List<String> services) {
        if (discoveryUrl.isBlank()) {
            return;
        }
        var thread = new Thread(() -> services.forEach(this::registerUntilAccepted), "discovery-registration");
        thread.setDaemon(true);
        thread.start();
    }

    public void deregister(List<String> services) {
        if (discoveryUrl.isBlank()) {
            return;
        }
        for (var service : services) {
            try {
                http.send(HttpRequest.newBuilder(URI.create(discoveryUrl + "/services/" + service)).DELETE()
                        .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                LOG.warn("could not deregister {}: {}", service, e.toString());
            }
        }
    }

    private void registerUntilAccepted(String service) {
        var url = publicUrl + "/services/" + service;
        var body = "{\"name\":\"" + service + "\",\"url\":\"" + url + "\",\"openapiUrl\":\"" + url + "/openapi\"}";
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(discoveryUrl + "/register"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    LOG.info("registered {} with discovery at {}", service, url);
                    return;
                }
                LOG.warn("discovery refused {}: {} {}", service, response.statusCode(), response.body());
            } catch (Exception e) {
                LOG.info("discovery not reachable yet for {}: {}", service, e.toString());
            }
            try {
                Thread.sleep(RETRY_EVERY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
