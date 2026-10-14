package com.eda.choreography.infra.web;

import com.eda.choreography.domain.plan.InvalidPlanException;
import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.domain.resolution.UnresolvedServiceException;
import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.infra.flows.FlowStore;
import com.eda.choreography.infra.flows.Flows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the editor needs: the services it can draw with, storing a drawn flow, starting it, and
 * seeing how a run ended.
 *
 * <ul>
 *   <li>{@code GET /api/catalog}: the services discovery's stream announced, with liveness;</li>
 *   <li>{@code GET /api/flows}, {@code GET /api/flows/{name}}: the stored flows, and one as BPMN;</li>
 *   <li>{@code PUT /api/flows/{name}}: stores a flow if it can run, and says which services are
 *       late-bound; a flow that cannot run is 400 with the reason;</li>
 *   <li>{@code POST /api/flows/{name}/runs}: starts an instance with the JSON body as its input;
 *       202 with its correlation id;</li>
 *   <li>{@code GET /api/runs/{id}}: COMPLETED or COMPENSATED with the trace and results once the
 *       instance ended, RUNNING until then.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class AuthoringApi {

    private final ServiceTable catalog;
    private final FlowStore store;
    private final Flows flows;
    private final RunOutcomes outcomes;

    public AuthoringApi(ServiceTable catalog, FlowStore store, Flows flows, RunOutcomes outcomes) {
        this.catalog = catalog;
        this.store = store;
        this.flows = flows;
        this.outcomes = outcomes;
    }

    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog() {
        return catalog.services().entrySet().stream()
                .map(service -> Map.<String, Object>of("name", service.getKey(), "live", service.getValue().live()))
                .toList();
    }

    @GetMapping("/flows")
    public SortedSet<String> flows() {
        return store.names();
    }

    @GetMapping(path = "/flows/{name}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> flow(@PathVariable String name) {
        return ResponseEntity.of(store.find(name));
    }

    @PutMapping("/flows/{name}")
    public Map<String, Object> define(@PathVariable String name, @RequestBody String bpmn) {
        var defined = flows.define(name, bpmn);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("start", defined.plan().start());
        body.put("services", defined.plan().services());
        body.put("bound", defined.bindings().bound());
        body.put("lateBound", defined.bindings().lateBound());
        return body;
    }

    @PostMapping("/flows/{name}/runs")
    public ResponseEntity<Map<String, String>> run(
            @PathVariable String name, @RequestBody(required = false) Map<String, Object> input) {
        var correlationId = flows.start(name, input == null ? Map.of() : input);
        return ResponseEntity.accepted().body(Map.of("correlationId", correlationId));
    }

    @GetMapping("/runs/{correlationId}")
    public Map<String, Object> runOf(@PathVariable String correlationId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("correlationId", correlationId);
        outcomes.of(correlationId).ifPresentOrElse(outcome -> {
            body.put("status", outcome.status().name());
            body.put("trace", outcome.instance().trace().stream().map(AuthoringApi::entry).toList());
            body.put("results", outcome.instance().results());
        }, () -> body.put("status", "RUNNING"));
        return body;
    }

    private static Map<String, Object> entry(TraceEntry entry) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", entry.id());
        body.put("step", entry.stepId());
        body.put("service", entry.service());
        body.put("outcome", entry.outcome().name());
        body.put("parents", entry.parents());
        body.put("resultRef", entry.resultRef());
        return body;
    }

    @ExceptionHandler({InvalidPlanException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> rejected(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(Flows.UnknownFlowException.class)
    ResponseEntity<Map<String, String>> unknown(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(UnresolvedServiceException.class)
    ResponseEntity<Map<String, String>> unavailable(RuntimeException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
