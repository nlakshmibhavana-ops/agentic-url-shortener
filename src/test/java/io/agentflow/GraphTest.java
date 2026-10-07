package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentflow.core.Graph;
import io.agentflow.model.Node;
import io.agentflow.model.NodeStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphTest {

    static Node n(String id, String... deps) {
        Node node = new Node(id, "stage", "x", id);
        node.deps = new java.util.ArrayList<>(List.of(deps));
        return node;
    }

    @Test
    void rejectsCyclesAndUnknownDependencies() {
        assertThatThrownBy(() -> new Graph(List.of(n("a", "b"), n("b", "a")))).hasMessageContaining("cycle");
        assertThatThrownBy(() -> new Graph(List.of(n("a", "missing")))).hasMessageContaining("unknown");
    }

    @Test
    void readyRespectsDependenciesAndAllowsParallelBranches() {
        Graph g = new Graph(List.of(n("a"), n("b", "a"), n("c", "a"), n("d", "b", "c")));
        assertThat(g.ready()).extracting(x -> x.id).containsExactly("a");
        g.get("a").status = NodeStatus.SUCCEEDED;
        assertThat(g.ready()).extracting(x -> x.id).containsExactlyInAnyOrder("b", "c");
        g.get("b").status = NodeStatus.SUCCEEDED;
        assertThat(g.ready()).extracting(x -> x.id).containsExactly("c"); // d waits for the join
        g.get("c").status = NodeStatus.SKIPPED;
        assertThat(g.ready()).extracting(x -> x.id).containsExactly("d");
    }

    @Test
    void blockDownstreamMarksTransitiveDependents() {
        Graph g = new Graph(List.of(n("a"), n("b", "a"), n("c", "b"), n("x")));
        assertThat(g.blockDownstream("a", "boom")).containsExactly("b", "c");
        assertThat(g.get("x").status).isEqualTo(NodeStatus.PENDING);
    }

    @Test
    void mermaidRendersEveryEdge() {
        assertThat(new Graph(List.of(n("a"), n("b.c", "a"))).toMermaid()).contains("a --> b_c");
    }
}
