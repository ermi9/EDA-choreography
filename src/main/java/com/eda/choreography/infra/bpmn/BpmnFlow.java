package com.eda.choreography.infra.bpmn;

import java.nio.charset.StandardCharsets;

/**
 * A drawn flow: its name, which every instance's messages carry, and its BPMN 2.0 XML as the
 * editor exported it.
 */
public record BpmnFlow(String name, String xml) {

    public BpmnFlow {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a flow needs a non-blank name");
        }
        if (xml == null) {
            throw new IllegalArgumentException("flow " + name + " has no BPMN");
        }
    }

    byte[] bytes() {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
