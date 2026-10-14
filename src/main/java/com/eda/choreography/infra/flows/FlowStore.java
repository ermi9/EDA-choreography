package com.eda.choreography.infra.flows;

import java.util.Optional;
import java.util.SortedSet;

/** Drawn flows, as BPMN XML, kept by name. */
public interface FlowStore {

    /** Stores a flow, replacing one of the same name. */
    void save(String name, String bpmn);

    Optional<String> find(String name);

    SortedSet<String> names();
}
