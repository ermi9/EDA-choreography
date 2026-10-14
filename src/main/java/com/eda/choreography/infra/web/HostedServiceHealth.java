package com.eda.choreography.infra.web;

import com.eda.choreography.infra.host.ServiceHost;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What discovery probes for each hosted service: up while this process hosts it. */
@RestController
public class HostedServiceHealth {

    private final ServiceHost host;

    public HostedServiceHealth(ServiceHost host) {
        this.host = host;
    }

    @GetMapping("/services/{name}/health")
    public ResponseEntity<Map<String, String>> health(@PathVariable String name) {
        if (host.isRunning() && host.services().contains(name)) {
            return ResponseEntity.ok(Map.of("status", "UP", "service", name));
        }
        return ResponseEntity.notFound().build();
    }
}
