package com.eda.choreography.infra.web;

import com.eda.choreography.infra.host.DemoSpec;
import com.eda.choreography.infra.host.ServiceHost;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The OpenAPI spec each hosted service registers with discovery, served while this process hosts it. */
@RestController
public class HostedServiceSpec {

    private final ServiceHost host;

    public HostedServiceSpec(ServiceHost host) {
        this.host = host;
    }

    @GetMapping("/services/{name}/openapi")
    public ResponseEntity<Map<String, Object>> spec(@PathVariable String name) {
        if (host.services().contains(name)) {
            return ResponseEntity.ok(DemoSpec.of(name));
        }
        return ResponseEntity.notFound().build();
    }
}
