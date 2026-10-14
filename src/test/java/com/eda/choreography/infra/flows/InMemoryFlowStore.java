package com.eda.choreography.infra.flows;

import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Test double: a flow store in a map. */
class InMemoryFlowStore implements FlowStore {

    final Map<String, String> flows = new ConcurrentHashMap<>();

    @Override
    public void save(String name, String bpmn) {
        flows.put(name, bpmn);
    }

    @Override
    public Optional<String> find(String name) {
        return Optional.ofNullable(flows.get(name));
    }

    @Override
    public SortedSet<String> names() {
        return new TreeSet<>(flows.keySet());
    }
}
