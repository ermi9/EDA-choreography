package com.eda.choreography.domain.plan;

import com.eda.choreography.domain.plan.PlanNode.Kind;
import com.eda.choreography.domain.plan.PlanNode.Split;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
 *   <li>it has one start step and reaches every step from there;</li>
 *   <li>a step with several edges out is a fork (all edges taken) or a choice (the first edge
 *       whose condition holds, else its one default edge);</li>
 *   <li>the branches of a fork meet at exactly one join, each branch runs at least one step on
 *       the way, and nothing outside the fork enters a branch;</li>
 *   <li>a join closes exactly one fork, and forks are not nested;</li>
 *   <li>every loop holds a choice with an edge leaving the loop, and no fork or join.</li>
 * </ul>
 * Choices may sit anywhere, inside a branch too, and may come back together at a plain step.
 *
 * <p>A loop runs its steps again, each run a new entry of the trace, so the trace stays acyclic
 * and is undone in reverse like any other. A join keeps one state per instance, so it cannot run
 * twice, and neither can the fork it closes. A loop inside a branch is fine: it stays on one
 * branch, which reaches the join once.
 *
 * <p>A step names the service that runs it, and one service may run several steps. A join is
 * the exception: it is named after its service, because its state and its timeout notice are
 * addressed by that name.
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
        checkReachable();
        nodes.values().forEach(this::checkSplit);
        loops().forEach(this::checkLoop);
        this.enclosingJoins = new HashMap<>();
        this.expectedBranches = new HashMap<>();
        var forks = nodes.values().stream().filter(node -> node.split() == Split.PARALLEL).toList();
        // Every fork is walked before any is closed, so a nested fork is reported as such
        // whichever fork comes first.
        var branches = new LinkedHashMap<PlanNode, Set<String>>();
        forks.forEach(fork -> branches.put(fork, walkBranches(fork)));
        branches.forEach(this::closeFork);
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

    /** The services the plan's steps run on, in drawn order, each once. */
    public Set<String> services() {
        var services = new LinkedHashSet<String>();
        nodes.values().forEach(node -> services.add(node.service()));
        return Collections.unmodifiableSet(services);
    }

    /** The service that runs a step. */
    public String serviceOf(String stepId) {
        var node = nodes.get(stepId);
        if (node == null) {
            throw new IllegalArgumentException(stepId + " is not a step of flow " + flowName);
        }
        return node.service();
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
        return Collections.unmodifiableMap(index);
    }

    private Map<String, List<PlanEdge>> indexEdges(List<PlanEdge> drawn) {
        var index = new LinkedHashMap<String, List<PlanEdge>>();
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
        return Collections.unmodifiableMap(index);
    }

    private String findStart(List<PlanEdge> drawn) {
        var starts = new LinkedHashSet<>(nodes.keySet());
        drawn.forEach(edge -> starts.remove(edge.to()));
        if (starts.isEmpty()) {
            throw invalid("it needs exactly one start step, a step nothing leads to, but has none; a flow cannot start inside a loop");
        }
        if (starts.size() != 1) {
            throw invalid("it needs exactly one start step, a step nothing leads to, but has " + starts);
        }
        return starts.iterator().next();
    }

    private void checkReachable() {
        var reached = new HashSet<String>();
        var pending = new ArrayDeque<>(List.of(start));
        while (!pending.isEmpty()) {
            var id = pending.poll();
            if (reached.add(id)) {
                outgoing(id).forEach(edge -> pending.add(edge.to()));
            }
        }
        var unreached = nodes.keySet().stream().filter(id -> !reached.contains(id)).toList();
        if (!unreached.isEmpty()) {
            throw invalid("steps " + unreached + " cannot be reached from start step " + start);
        }
    }

    /**
     * Tarjan's algorithm: a strongly connected set of more than one step, or a step with an edge to
     * itself, is a loop. Each loop lists its steps in drawn order.
     */
    private List<List<String>> loops() {
        var tarjan = new Tarjan();
        nodes.keySet().forEach(id -> {
            if (!tarjan.index.containsKey(id)) {
                tarjan.visit(id);
            }
        });
        var order = new ArrayList<>(nodes.keySet());
        return tarjan.components.stream()
                .filter(component -> component.size() > 1 || outgoing(component.iterator().next()).stream()
                        .anyMatch(edge -> component.contains(edge.to())))
                .map(component -> component.stream().sorted((a, b) -> order.indexOf(a) - order.indexOf(b)).toList())
                .toList();
    }

    private void checkLoop(List<String> loop) {
        for (var id : loop) {
            var node = nodes.get(id);
            if (node.kind() == Kind.JOIN) {
                throw invalid("join " + id + " is inside the loop through " + loop + "; a join runs once per instance");
            }
            if (node.split() == Split.PARALLEL) {
                throw invalid("fork " + id + " is inside the loop through " + loop + "; a fork runs once per instance");
            }
        }
        var wayOut = loop.stream()
                .filter(id -> nodes.get(id).split() == Split.EXCLUSIVE)
                .flatMap(id -> outgoing(id).stream())
                .anyMatch(edge -> !loop.contains(edge.to()));
        if (!wayOut) {
            throw invalid("the loop through " + loop + " has no way out; give it a choice with an edge leaving the loop");
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

    /** The steps on the branches of a fork, up to and not including the one join they meet at. */
    private Set<String> walkBranches(PlanNode fork) {
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
        return branch;
    }

    /**
     * Checks that nothing outside a fork enters its branches or its join, and records the join
     * for each step on the branches.
     */
    private void closeFork(PlanNode fork, Set<String> branch) {
        var join = outgoing.values().stream()
                .flatMap(List::stream)
                .filter(edge -> branch.contains(edge.from()) && nodes.get(edge.to()).kind() == Kind.JOIN)
                .map(PlanEdge::to)
                .findFirst()
                .orElseThrow();
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

    /** One run of Tarjan's algorithm over the plan's steps; plans are small, so it recurses. */
    private final class Tarjan {

        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final ArrayDeque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<Set<String>> components = new ArrayList<>();

        void visit(String id) {
            index.put(id, index.size());
            lowLink.put(id, index.get(id));
            stack.push(id);
            onStack.add(id);
            for (var edge : outgoing(id)) {
                var next = edge.to();
                if (!index.containsKey(next)) {
                    visit(next);
                    lowLink.put(id, Math.min(lowLink.get(id), lowLink.get(next)));
                } else if (onStack.contains(next)) {
                    lowLink.put(id, Math.min(lowLink.get(id), index.get(next)));
                }
            }
            if (lowLink.get(id).equals(index.get(id))) {
                var component = new HashSet<String>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(id));
                components.add(component);
            }
        }
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
            return task(id, id, split);
        }

        /** A step run by a service it is not named after, such as the second step of one service. */
        public Builder task(String id, String service, Split split) {
            nodes.add(new PlanNode(id, service, Kind.TASK, split));
            return this;
        }

        public Builder join(String id) {
            return join(id, Split.NONE);
        }

        /** A join that, once it has run, leaves along a choice (a join cannot fork again: no nesting). */
        public Builder join(String id, Split split) {
            nodes.add(new PlanNode(id, id, Kind.JOIN, split));
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
