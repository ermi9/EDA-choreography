package com.eda.choreography.domain.plan;

import com.eda.choreography.domain.plan.PlanNode.Kind;
import com.eda.choreography.domain.plan.PlanNode.Split;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The plan one flow's instances run: its steps, the edges between them, and where it forks and
 * joins. It is what a {@code Planner} produces and what every step reads to choose its next hop.
 *
 * <p>A plan is checked whole when it is built, and one that cannot run as drawn is rejected
 * with an {@link InvalidPlanException}:
 * <ul>
 *   <li>it has one start step, reaches every step from there, and has no cycle;</li>
 *   <li>a step with several edges out is a fork (all edges taken) or a choice (the first edge
 *       whose condition holds, else its one default edge);</li>
 *   <li>the branches of a fork meet at exactly one join, each branch runs at least one step on
 *       the way, and nothing outside the fork enters a branch;</li>
 *   <li>a join closes exactly one fork, and forks are not nested.</li>
 * </ul>
 * Choices may sit anywhere, inside a branch too, and may come back together at a plain step.
 *
 * <p>A join waits for one branch per edge leaving its fork. A branch whose choice can reach the
 * join along two edges still arrives once.
 */
public final class PlanModel {

    private final String flowName;
    private final Map<String, PlanNode> nodes;
    private final Map<String, List<PlanEdge>> outgoing;
    private final String start;
    private final Map<String, String> enclosingJoins;
    private final Map<String, Integer> expectedBranches;
    private final Duration joinTimeout;

    private PlanModel(Builder builder) {
        if (builder.flowName == null || builder.flowName.isBlank()) {
            throw new InvalidPlanException("a flow needs a non-blank name");
        }
        this.flowName = builder.flowName;
        this.nodes = indexNodes(builder.nodes);
        this.outgoing = indexEdges(builder.edges);
        this.start = findStart(builder.edges);
        checkAcyclic();
        nodes.values().forEach(this::checkSplit);
        this.enclosingJoins = new HashMap<>();
        this.expectedBranches = new HashMap<>();
        nodes.values().stream().filter(node -> node.split() == Split.PARALLEL).forEach(this::closeFork);
        nodes.values().stream()
                .filter(node -> node.kind() == Kind.JOIN && !expectedBranches.containsKey(node.id()))
                .findFirst()
                .ifPresent(join -> {
                    throw invalid("join " + join.id() + " closes no fork");
                });
        if (builder.joinTimeout != null && (builder.joinTimeout.isNegative() || builder.joinTimeout.isZero())) {
            throw invalid("the join timeout must be positive, was " + builder.joinTimeout);
        }
        this.joinTimeout = builder.joinTimeout;
    }

    public static Builder builder(String flowName) {
        return new Builder(flowName);
    }

    public String flowName() {
        return flowName;
    }

    /** The step every instance starts at. */
    public String start() {
        return start;
    }

    public Optional<PlanNode> node(String stepId) {
        return Optional.ofNullable(nodes.get(stepId));
    }

    /** The edges leaving a step, in the order they were drawn; a choice tries its conditions in this order. */
    public List<PlanEdge> outgoing(String stepId) {
        return outgoing.getOrDefault(stepId, List.of());
    }

    /** The join that a step between a fork and that join ends at. */
    public Optional<String> enclosingJoin(String stepId) {
        return Optional.ofNullable(enclosingJoins.get(stepId));
    }

    /** How many branches a join waits for: one per edge leaving its fork. */
    public int expectedBranches(String joinId) {
        var branches = expectedBranches.get(joinId);
        if (branches == null) {
            throw new IllegalArgumentException(joinId + " is not a join of flow " + flowName);
        }
        return branches;
    }

    /** How long this flow's joins wait for their last branch, if the flow says. */
    public Optional<Duration> joinTimeout() {
        return Optional.ofNullable(joinTimeout);
    }

    private Map<String, PlanNode> indexNodes(List<PlanNode> drawn) {
        if (drawn.isEmpty()) {
            throw invalid("it has no steps");
        }
        var index = new LinkedHashMap<String, PlanNode>();
        for (var node : drawn) {
            if (index.putIfAbsent(node.id(), node) != null) {
                throw invalid("step " + node.id() + " appears twice");
            }
        }
        return Map.copyOf(index);
    }

    private Map<String, List<PlanEdge>> indexEdges(List<PlanEdge> drawn) {
        var index = new HashMap<String, List<PlanEdge>>();
        var seen = new HashSet<List<String>>();
        for (var edge : drawn) {
            for (var end : List.of(edge.from(), edge.to())) {
                if (!nodes.containsKey(end)) {
                    throw invalid("the edge " + edge.from() + " -> " + edge.to() + " names an unknown step " + end);
                }
            }
            if (!seen.add(List.of(edge.from(), edge.to()))) {
                throw invalid("the edge " + edge.from() + " -> " + edge.to() + " appears twice");
            }
            index.computeIfAbsent(edge.from(), from -> new ArrayList<>()).add(edge);
        }
        index.replaceAll((from, edges) -> List.copyOf(edges));
        return Map.copyOf(index);
    }

    private String findStart(List<PlanEdge> drawn) {
        var starts = new LinkedHashSet<>(nodes.keySet());
        drawn.forEach(edge -> starts.remove(edge.to()));
        if (starts.size() != 1) {
            throw invalid("it needs exactly one start step, a step nothing leads to, but has " + starts);
        }
        return starts.iterator().next();
    }

    /** Kahn's algorithm: whatever cannot be ordered lies on a cycle or behind one. */
    private void checkAcyclic() {
        var incoming = new HashMap<String, Integer>();
        nodes.keySet().forEach(id -> incoming.put(id, 0));
        outgoing.values().forEach(edges -> edges.forEach(edge -> incoming.merge(edge.to(), 1, Integer::sum)));
        var ready = new ArrayDeque<String>();
        incoming.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        var ordered = 0;
        while (!ready.isEmpty()) {
            var id = ready.poll();
            ordered++;
            for (var edge : outgoing(id)) {
                if (incoming.merge(edge.to(), -1, Integer::sum) == 0) {
                    ready.add(edge.to());
                }
            }
        }
        if (ordered != nodes.size()) {
            var stuck = new HashSet<String>();
            incoming.forEach((id, count) -> {
                if (count > 0) {
                    stuck.add(id);
                }
            });
            throw invalid("it has a cycle through " + stuck);
        }
    }

    private void checkSplit(PlanNode node) {
        var edges = outgoing(node.id());
        var conditional = edges.stream().filter(PlanEdge::isConditional).count();
        var defaults = edges.stream().filter(PlanEdge::isDefault).count();
        switch (node.split()) {
            case NONE -> {
                if (edges.size() > 1) {
                    throw invalid("step " + node.id() + " has " + edges.size() + " edges out but is neither a fork nor a choice");
                }
                if (conditional + defaults > 0) {
                    throw invalid("step " + node.id() + " has a conditional or default edge but is not a choice");
                }
            }
            case PARALLEL -> {
                if (edges.size() < 2) {
                    throw invalid("fork " + node.id() + " needs at least two branches, has " + edges.size());
                }
                if (conditional + defaults > 0) {
                    throw invalid("fork " + node.id() + " takes every branch, so its edges carry no condition");
                }
            }
            case EXCLUSIVE -> {
                if (edges.size() < 2) {
                    throw invalid("choice " + node.id() + " needs at least two edges, has " + edges.size());
                }
                if (defaults > 1 || conditional + defaults != edges.size()) {
                    throw invalid("every edge of choice " + node.id() + " needs a condition, except at most one default");
                }
            }
        }
    }

    /**
     * Walks every branch of a fork up to the join they meet at, and records that join for each
     * step on the way.
     */
    private void closeFork(PlanNode fork) {
        var branch = new LinkedHashSet<String>();
        var joins = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>();
        for (var edge : outgoing(fork.id())) {
            if (nodes.get(edge.to()).kind() == Kind.JOIN) {
                throw invalid("fork " + fork.id() + " has a branch with no step before join " + edge.to());
            }
            pending.add(edge.to());
        }
        while (!pending.isEmpty()) {
            var id = pending.poll();
            if (!branch.add(id)) {
                continue;
            }
            var node = nodes.get(id);
            if (node.split() == Split.PARALLEL) {
                throw invalid("step " + id + " forks inside fork " + fork.id() + "; forks cannot be nested");
            }
            if (outgoing(id).isEmpty()) {
                throw invalid("a branch of fork " + fork.id() + " ends at " + id + " without reaching a join");
            }
            for (var edge : outgoing(id)) {
                if (nodes.get(edge.to()).kind() == Kind.JOIN) {
                    joins.add(edge.to());
                } else {
                    pending.add(edge.to());
                }
            }
        }
        if (joins.size() != 1) {
            throw invalid("the branches of fork " + fork.id() + " must meet at one join, but meet at " + joins);
        }
        var join = joins.iterator().next();
        var inside = new HashSet<>(branch);
        inside.add(fork.id());
        for (var edges : outgoing.values()) {
            for (var edge : edges) {
                if (branch.contains(edge.to()) && !inside.contains(edge.from())) {
                    throw invalid("step " + edge.to() + " is inside fork " + fork.id() + " but is entered from " + edge.from());
                }
                if (edge.to().equals(join) && !branch.contains(edge.from())) {
                    throw invalid("join " + join + " of fork " + fork.id() + " is entered from " + edge.from());
                }
            }
        }
        if (expectedBranches.putIfAbsent(join, outgoing(fork.id()).size()) != null) {
            throw invalid("join " + join + " closes more than one fork");
        }
        branch.forEach(id -> enclosingJoins.put(id, join));
    }

    private InvalidPlanException invalid(String reason) {
        return new InvalidPlanException("flow " + flowName + " cannot run: " + reason);
    }

    public static final class Builder {

        private final String flowName;
        private final List<PlanNode> nodes = new ArrayList<>();
        private final List<PlanEdge> edges = new ArrayList<>();
        private Duration joinTimeout;

        private Builder(String flowName) {
            this.flowName = flowName;
        }

        public Builder task(String id) {
            return task(id, Split.NONE);
        }

        public Builder task(String id, Split split) {
            nodes.add(new PlanNode(id, Kind.TASK, split));
            return this;
        }

        public Builder join(String id) {
            return join(id, Split.NONE);
        }

        /** A join that, once it has run, leaves along a choice (a join cannot fork again: no nesting). */
        public Builder join(String id, Split split) {
            nodes.add(new PlanNode(id, Kind.JOIN, split));
            return this;
        }

        public Builder edge(String from, String to) {
            edges.add(new PlanEdge(from, to, null, false));
            return this;
        }

        public Builder edge(String from, String to, String condition) {
            edges.add(new PlanEdge(from, to, condition, false));
            return this;
        }

        public Builder defaultEdge(String from, String to) {
            edges.add(new PlanEdge(from, to, null, true));
            return this;
        }

        public Builder joinTimeout(Duration timeout) {
            this.joinTimeout = timeout;
            return this;
        }

        public PlanModel build() {
            return new PlanModel(this);
        }
    }
}
