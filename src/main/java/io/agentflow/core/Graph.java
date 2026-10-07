package io.agentflow.core;

import io.agentflow.model.Impact;
import io.agentflow.model.Node;
import io.agentflow.model.NodeStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The delivery graph: explicit dependencies, readiness, downstream blocking and rendering. */
public class Graph {

    public static class GraphException extends RuntimeException {
        GraphException(String message) {
            super(message);
        }
    }

    public final Map<String, Node> nodes = new LinkedHashMap<>();

    public Graph(Collection<Node> nodes) {
        nodes.forEach(n -> this.nodes.put(n.id, n));
        validate();
    }

    public Node get(String id) {
        return nodes.get(id);
    }

    public Collection<Node> all() {
        return nodes.values();
    }

    public void put(Node node) {
        nodes.put(node.id, node);
    }

    public void validate() {
        for (Node n : nodes.values()) {
            List<String> missing = n.deps.stream().filter(d -> !nodes.containsKey(d)).toList();
            if (!missing.isEmpty()) {
                throw new GraphException(n.id + " depends on unknown nodes " + missing);
            }
        }
        topologicalOrder();
    }

    /** Kahn's algorithm with deterministic (sorted) tie-breaking; throws on cycles. */
    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new HashMap<>();
        nodes.values().forEach(n -> indegree.put(n.id, n.deps.size()));
        TreeSet<String> ready = new TreeSet<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(ready);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String child : new TreeSet<>(dependents(id))) {
                if (indegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }
        if (order.size() != nodes.size()) {
            TreeSet<String> cyclic = new TreeSet<>(nodes.keySet());
            cyclic.removeAll(order);
            throw new GraphException("dependency cycle among " + cyclic);
        }
        return order;
    }

    public List<String> dependents(String id) {
        return nodes.values().stream().filter(n -> n.deps.contains(id)).map(n -> n.id).toList();
    }

    public Set<String> downstream(String id) {
        Set<String> out = new TreeSet<>();
        Deque<String> frontier = new ArrayDeque<>(List.of(id));
        while (!frontier.isEmpty()) {
            for (String child : dependents(frontier.pop())) {
                if (out.add(child)) {
                    frontier.push(child);
                }
            }
        }
        return out;
    }

    /** Pending nodes whose dependencies all succeeded (or were skipped), in topological order. */
    public List<Node> ready() {
        Set<NodeStatus> ok = EnumSet.of(NodeStatus.SUCCEEDED, NodeStatus.SKIPPED);
        List<Node> out = new ArrayList<>();
        for (String id : topologicalOrder()) {
            Node n = nodes.get(id);
            if (n.status == NodeStatus.PENDING && n.deps.stream().allMatch(d -> ok.contains(nodes.get(d).status))) {
                out.add(n);
            }
        }
        return out;
    }

    public List<String> blockDownstream(String id, String reason) {
        List<String> blocked = new ArrayList<>();
        for (String d : downstream(id)) {
            Node n = nodes.get(d);
            if (EnumSet.of(NodeStatus.PENDING, NodeStatus.WAITING, NodeStatus.STOPPED).contains(n.status)) {
                n.status = NodeStatus.BLOCKED;
                n.error = reason;
                blocked.add(d);
            }
        }
        return blocked;
    }

    public List<Node> consumersOf(String artifact) {
        return nodes.values().stream().filter(n -> n.inputs.contains(artifact)).toList();
    }

    public boolean isFinished() {
        return nodes.values().stream().allMatch(n -> NodeStatus.TERMINAL.contains(n.status));
    }

    public String toMermaid() {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        for (String id : topologicalOrder()) {
            Node n = nodes.get(id);
            String label = id + "<br/><small>" + n.status.value() + "</small>";
            String shape = n.impact == Impact.HIGH ? "{\"" + label + "\"}" : "[\"" + label + "\"]";
            String cls = switch (n.status) {
                case SUCCEEDED -> ":::ok";
                case FAILED, BLOCKED -> ":::bad";
                case WAITING -> ":::wait";
                case SKIPPED -> ":::skip";
                default -> "";
            };
            sb.append("  ").append(mid(id)).append(shape).append(cls).append('\n');
            for (String dep : n.deps) {
                sb.append("  ").append(mid(dep)).append(" --> ").append(mid(id)).append('\n');
            }
        }
        sb.append("  classDef ok fill:#d4edda,stroke:#2e7d32\n")
                .append("  classDef bad fill:#f8d7da,stroke:#c62828\n")
                .append("  classDef wait fill:#fff3cd,stroke:#f9a825\n")
                .append("  classDef skip fill:#eeeeee,stroke:#9e9e9e");
        return sb.toString();
    }

    private static String mid(String id) {
        return id.replace('.', '_').replace('-', '_');
    }
}
